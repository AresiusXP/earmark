package appapi

import (
	"context"
	"errors"
	"strings"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

// Prepare states and reasons of the contract.
const (
	stateReady     = "ready"
	statePreparing = "preparing"
	stateFailed    = "failed"

	reasonNoSeeders = "no_seeders"
	reasonCooldown  = "debrid_cooldown"
	reasonFailed    = "debrid_failed"
	reasonNotFound  = "not_found"
)

const (
	flightTimeout = 40 * time.Second
	maxFiles      = 2000
)

// prepResult is the outcome of one prepare/download attempt. URL is set only for a ready download
// and is never serialised or logged.
type prepResult struct {
	State       string
	Progress    int
	HasProgress bool
	Reason      string
	Files       []model.File
	RetryAfter  time.Duration
	URL         string
}

// budgetError: the key's cold-add budget is spent.
type budgetError struct{ retryAfter time.Duration }

func (e *budgetError) Error() string { return "appapi: add budget spent" }

var (
	// errFileNotFound: the file (or any audio file) is not in the torrent.
	errFileNotFound = errors.New("appapi: file not found in the release")
	// errUpstream: the provider could not be asked or answered something unusable.
	errUpstream = errors.New("appapi: debrid service unavailable")
)

type prepIn struct {
	userID    string
	key       userkeys.Key
	provider  debrid.Provider
	rel       model.Release
	fileIndex int  // debrid.AutoFile for prepare
	allowAdd  bool // false: never add a torrent (HEAD)
	wantFiles bool // prepare: include the file list of a ready torrent
}

// prepare asks the caller's provider about one release using ONLY the caller's key. It adds the
// torrent at most once per key (the add budget applies to cold adds; polling a queued torrent is
// free), never on !allowAdd, and returns what the provider reports. Callers coalesce it per key.
func (a *app) prepare(ctx context.Context, in prepIn) (prepResult, error) {
	fp, name, hash := in.key.Fingerprint, in.provider.Name(), in.rel.InfoHash
	apiKey := in.key.APIKey()
	k := prepKey{fp, name, hash}

	if left := a.trk.coolingDown(fp, name); left > 0 {
		return cooldownResult(left), nil
	}
	st := a.trk.get(k)
	now := a.now()
	if !st.failedAt.IsZero() {
		if now.Sub(st.failedAt) < failedTTL || !in.allowAdd {
			return prepResult{State: stateFailed, Reason: st.failReason}, nil
		}
		if d, ok := in.provider.(debrid.Deleter); ok {
			_ = d.Delete(ctx, apiKey, hash) // best effort: a failed torrent would keep failing
		}
		a.trk.forget(k)
		st = prepState{}
	}
	if in.wantFiles && st.readyUntil.After(now) {
		return prepResult{State: stateReady, Files: st.files}, nil
	}

	known, freeAdd := st.inAccount, false
	var cachedFiles []model.File
	if !known {
		if lister, ok := in.provider.(debrid.FileLister); ok {
			// Read-only: tells whether the torrent is already in the caller's account.
			_, err := lister.Files(ctx, apiKey, hash)
			switch {
			case err == nil:
				known = true
			case errors.Is(err, debrid.ErrNotFound):
			default:
				return a.failure(in, k, err)
			}
		} else if in.allowAdd { // the cache check stores what it learns: not on HEAD
			cached, files, err := a.checkCached(ctx, in)
			switch {
			case err == nil:
				freeAdd, cachedFiles = cached, files
			case errors.Is(err, debrid.ErrBadKey), errors.Is(err, debrid.ErrRateLimited):
				return a.failure(in, k, err)
			} // anything else: cache state unknown, carry on as a cold add
		}
		if known {
			a.trk.update(k, func(s *prepState) { s.inAccount = true })
		}
	}
	if !known && !in.allowAdd {
		return prepResult{State: statePreparing}, nil
	}
	spent := false
	if !known && !freeAdd {
		ok, after := a.bud.spend(fp)
		if !ok {
			return prepResult{}, &budgetError{after}
		}
		spent = true
	}

	res, err := in.provider.Resolve(ctx, apiKey, debrid.ResolveRequest{InfoHash: hash, Magnet: in.rel.Magnet, FileIndex: in.fileIndex})
	if err != nil {
		if spent && (errors.Is(err, debrid.ErrBadKey) || errors.Is(err, debrid.ErrRateLimited)) {
			a.bud.refund(fp) // rejected before anything was added
		}
		return a.failure(in, k, err)
	}
	a.trk.update(k, func(s *prepState) { s.inAccount = true })
	perKey := !service.SharedCache(name)
	if res.Pending {
		if !known && perKey {
			a.recordUserAvail(ctx, fp, name, hash, false)
		}
		return prepResult{State: statePreparing, Progress: res.Progress, HasProgress: true}, nil
	}
	if perKey {
		a.recordUserAvail(ctx, fp, name, hash, true)
	}
	out := prepResult{State: stateReady, URL: res.URL}
	if in.wantFiles {
		out.Files = a.readyFiles(ctx, in, cachedFiles)
		a.trk.update(k, func(s *prepState) { s.readyUntil = now.Add(readyTTL); s.files = out.Files })
	}
	return out, nil
}

func cooldownResult(left time.Duration) prepResult {
	return prepResult{State: statePreparing, Reason: reasonCooldown, RetryAfter: left}
}

// failure maps a provider error. A rejected key marks the user's key invalid and drops the links
// cached for it; a rate limit starts a cooldown for the key; a failed torrent is remembered.
func (a *app) failure(in prepIn, k prepKey, err error) (prepResult, error) {
	fp, name := in.key.Fingerprint, in.provider.Name()
	switch {
	case errors.Is(err, debrid.ErrBadKey):
		a.links.evict(fp)
		mctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if e := a.key.MarkInvalid(mctx, in.userID, name); e != nil {
			a.log.Warn("mark debrid key invalid failed", "provider", name, "err", e)
		}
		return prepResult{}, userkeys.ErrBadKey
	case errors.Is(err, debrid.ErrRateLimited):
		var ra debrid.RetryAfterer
		var d time.Duration
		if errors.As(err, &ra) {
			d = ra.RetryAfterDuration()
		}
		return cooldownResult(a.trk.setCooldown(fp, name, d)), nil
	case errors.Is(err, debrid.ErrFailed):
		reason := failReason(err)
		a.trk.update(k, func(s *prepState) { s.failedAt = a.now(); s.failReason = reason; s.inAccount = true })
		return prepResult{State: stateFailed, Reason: reason}, nil
	case errors.Is(err, debrid.ErrNotFound):
		return prepResult{}, errFileNotFound
	}
	// Only the class is logged: provider errors are redacted by the providers, but a link or an
	// id has no business in a log line.
	a.log.Warn("debrid call failed", "provider", name, "kind", errKind(err))
	return prepResult{}, errUpstream
}

func errKind(err error) string {
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return "timeout"
	case errors.Is(err, context.Canceled):
		return "canceled"
	}
	return "other"
}

// failReason tells a dead swarm from other failures by the provider's own status text.
func failReason(err error) string {
	m := strings.ToLower(err.Error())
	for _, w := range []string{"dead", "seed", "stalled", "no peers", "no_seeders"} {
		if strings.Contains(m, w) {
			return reasonNoSeeders
		}
	}
	return reasonFailed
}

// checkCached asks the provider's cache API with the CALLER's key (TorBox: the cache is the same
// for every account, so the answer is also stored for everybody) and keeps the file list when none
// is stored yet.
func (a *app) checkCached(ctx context.Context, in prepIn) (cached bool, files []model.File, err error) {
	hash, name := in.rel.InfoHash, in.provider.Name()
	res, err := in.provider.CheckCached(ctx, in.key.APIKey(), []string{hash})
	if err != nil {
		return false, nil, err
	}
	if res == nil {
		return false, nil, nil
	}
	c, cached := res[hash]
	if service.SharedCache(name) {
		if e := a.d.Store.SetAvailability(ctx, name, []service.Availability{{InfoHash: hash, Cached: cached, CheckedAt: a.now()}}); e != nil {
			a.log.Warn("store availability failed", "provider", name, "err", e)
		}
		if cached && len(c.Files) > 0 {
			a.saveFilesOnce(ctx, hash, c.Files)
		}
	}
	return cached, c.Files, nil
}

// saveFilesOnce stores a provider file list (TorBox indexes) unless one is stored already; the
// store decides atomically, so concurrent callers cannot interleave two lists.
func (a *app) saveFilesOnce(ctx context.Context, hash string, files []model.File) {
	if _, err := a.d.Store.SaveFilesIfAbsent(ctx, hash, files); err != nil {
		a.log.Warn("store files failed", "err", err)
	}
}

func (a *app) recordUserAvail(ctx context.Context, fp, provider, hash string, cached bool) {
	if err := a.d.Store.SetUserAvailability(ctx, fp, provider, hash, cached, a.now()); err != nil {
		a.log.Warn("store user availability failed", "provider", provider, "err", err)
	}
}

// readyFiles lists the audio files of a ready torrent with the PROVIDER's indexes: live from the
// caller's account when the provider can list them (Real-Debrid), otherwise the stored list
// (which only ever holds TorBox indexes) or a fresh cache check.
func (a *app) readyFiles(ctx context.Context, in prepIn, fromCheck []model.File) []model.File {
	hash := in.rel.InfoHash
	if lister, ok := in.provider.(debrid.FileLister); ok {
		fs, err := lister.Files(ctx, in.key.APIKey(), hash)
		if err != nil {
			a.log.Warn("list files failed", "provider", in.provider.Name(), "kind", errKind(err))
			return []model.File{}
		}
		return service.AudioFiles(fs, maxFiles)
	}
	if len(fromCheck) > 0 {
		return service.AudioFiles(fromCheck, maxFiles)
	}
	if have, err := a.d.Store.Files(ctx, []string{hash}); err == nil && len(have[hash]) > 0 && service.SharedCache(in.provider.Name()) {
		return service.AudioFiles(have[hash], maxFiles)
	}
	if _, files, err := a.checkCached(ctx, in); err == nil {
		return service.AudioFiles(files, maxFiles)
	}
	return []model.File{}
}
