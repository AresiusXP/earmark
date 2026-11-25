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

// coldOut is what the shared cold-add flight tells every caller of one (fingerprint, provider,
// hash). Either the flight ends the request itself (early: a cooldown, a failed torrent, a spent
// budget, a rejected key...), or the torrent is in the account afterwards and the caller resolves
// its own file. did says the flight made the provider's add call itself, for fileIndex.
type coldOut struct {
	early   bool
	res     prepResult
	err     error
	known   bool // the torrent was already in the account (no add was made)
	did     bool
	files   []model.File // file list learned by the cache check (TorBox)
	idx     int
	pending debrid.ResolveResult // the add call's answer when did and resErr == nil
	resErr  error                // the add call's index-specific error (file not in the torrent)
}

func coldKey(fp, provider, hash string) string { return fp + "\x00" + provider + "\x00" + hash }

// prepare asks the caller's provider about one release using ONLY the caller's key. It adds the
// torrent at most once per key (the add budget applies to cold adds; polling a queued torrent is
// free) and returns what the provider reports. Callers coalesce it per key (prepare) or per file
// (download); the cold add itself is ONE flight per (fingerprint, provider, hash) shared by both
// routes, so concurrent prepares and downloads of any file of a torrent add it and spend the
// budget once. A !allowAdd call (HEAD) only reads: see peek.
func (a *app) prepare(ctx context.Context, in prepIn) (prepResult, error) {
	if !in.allowAdd {
		return a.peek(ctx, in)
	}
	fp, name, hash := in.key.Fingerprint, in.provider.Name(), in.rel.InfoHash
	apiKey := in.key.APIKey()
	k := prepKey{fp, name, hash}

	if left := a.trk.coolingDown(fp, name); left > 0 {
		return cooldownResult(left), nil
	}
	st := a.trk.get(k)
	now := a.now()
	if !st.failedAt.IsZero() && now.Sub(st.failedAt) < failedTTL {
		return prepResult{State: stateFailed, Reason: st.failReason}, nil
	}
	if in.wantFiles && st.readyUntil.After(now) {
		return prepResult{State: stateReady, Files: st.files}, nil
	}

	cold := coldOut{known: true}
	if !st.inAccount || !st.failedAt.IsZero() {
		out, err := a.cold.do(ctx, coldKey(fp, name, hash), flightTimeout, nil, func(ctx context.Context) (coldOut, error) {
			return a.coldAdd(ctx, in), nil
		})
		if err != nil {
			return a.failure(in, k, err) // the wait ended (timeout, caller gone)
		}
		if out.early {
			return out.res, out.err
		}
		cold = out
	}

	var res debrid.ResolveResult
	var err error
	if cold.did && cold.idx == in.fileIndex {
		res, err = cold.pending, cold.resErr
	} else {
		res, err = in.provider.Resolve(ctx, apiKey, debrid.ResolveRequest{InfoHash: hash, Magnet: in.rel.Magnet, FileIndex: in.fileIndex})
	}
	if err != nil {
		return a.failure(in, k, err)
	}
	a.trk.update(k, func(s *prepState) { s.inAccount = true })
	perKey := !service.SharedCache(name)
	if res.Pending {
		if !cold.known && perKey {
			a.recordUserAvail(ctx, fp, name, hash, false)
		}
		return prepResult{State: statePreparing, Progress: res.Progress, HasProgress: true}, nil
	}
	if perKey {
		a.recordUserAvail(ctx, fp, name, hash, true)
	}
	out := prepResult{State: stateReady, URL: res.URL}
	if in.wantFiles {
		out.Files = a.readyFiles(ctx, in, cold.files)
		a.trk.update(k, func(s *prepState) { s.readyUntil = now.Add(readyTTL); s.files = out.Files })
	}
	return out, nil
}

// coldAdd is the shared flight: it makes sure the torrent is in the caller's account. It looks the
// torrent up read-only first (RD: Files; TorBox: a cache check that is stored for everybody),
// spends one budget slot and calls the provider's add (Resolve) only when it is not there, and
// retries a failed torrent once its failure has aged. The flight leader's own file is resolved by
// that add call; every other caller resolves its file afterwards, when the torrent is known.
func (a *app) coldAdd(ctx context.Context, in prepIn) coldOut {
	fp, name, hash := in.key.Fingerprint, in.provider.Name(), in.rel.InfoHash
	apiKey := in.key.APIKey()
	k := prepKey{fp, name, hash}
	early := func(res prepResult, err error) coldOut { return coldOut{early: true, res: res, err: err} }

	if left := a.trk.coolingDown(fp, name); left > 0 {
		return early(cooldownResult(left), nil)
	}
	st := a.trk.get(k)
	if !st.failedAt.IsZero() {
		if a.now().Sub(st.failedAt) < failedTTL {
			return early(prepResult{State: stateFailed, Reason: st.failReason}, nil)
		}
		if d, ok := in.provider.(debrid.Deleter); ok {
			_ = d.Delete(ctx, apiKey, hash) // best effort: a failed torrent would keep failing
		}
		a.trk.forget(k)
		st = prepState{}
	}
	if st.inAccount {
		return coldOut{known: true}
	}

	var cachedFiles []model.File
	freeAdd := false
	if lister, ok := in.provider.(debrid.FileLister); ok {
		// Read-only: tells whether the torrent is already in the caller's account.
		_, err := lister.Files(ctx, apiKey, hash)
		switch {
		case err == nil:
			a.trk.update(k, func(s *prepState) { s.inAccount = true })
			return coldOut{known: true}
		case errors.Is(err, debrid.ErrNotFound):
		default:
			return early(a.failure(in, k, err))
		}
	} else {
		cached, files, err := a.checkCached(ctx, in)
		switch {
		case err == nil:
			freeAdd, cachedFiles = cached, files
		case errors.Is(err, debrid.ErrBadKey), errors.Is(err, debrid.ErrRateLimited):
			return early(a.failure(in, k, err))
		} // anything else: cache state unknown, carry on as a cold add
	}
	spent := false
	if !freeAdd {
		ok, after := a.bud.spend(fp)
		if !ok {
			return early(prepResult{}, &budgetError{after})
		}
		spent = true
	}
	res, err := in.provider.Resolve(ctx, apiKey, debrid.ResolveRequest{InfoHash: hash, Magnet: in.rel.Magnet, FileIndex: in.fileIndex})
	if err != nil {
		if spent && (errors.Is(err, debrid.ErrBadKey) || errors.Is(err, debrid.ErrRateLimited)) {
			a.bud.refund(fp) // rejected before anything was added
		}
		if errors.Is(err, debrid.ErrNotFound) {
			// Specific to the leader's file: the others resolve their own.
			return coldOut{did: true, idx: in.fileIndex, resErr: err, files: cachedFiles}
		}
		return early(a.failure(in, k, err))
	}
	a.trk.update(k, func(s *prepState) { s.inAccount = true })
	return coldOut{did: true, idx: in.fileIndex, pending: res, files: cachedFiles}
}

// peek answers a !allowAdd call (HEAD) with read-only knowledge only: the in-memory state of the
// key, and a read-only provider listing (Files). It never calls Resolve (which adds torrents and
// selects files), never spends budget and writes no availability. What it cannot prove read-only
// is answered as "preparing" (202).
func (a *app) peek(ctx context.Context, in prepIn) (prepResult, error) {
	fp, name, hash := in.key.Fingerprint, in.provider.Name(), in.rel.InfoHash
	k := prepKey{fp, name, hash}
	if left := a.trk.coolingDown(fp, name); left > 0 {
		return cooldownResult(left), nil
	}
	st := a.trk.get(k)
	if !st.failedAt.IsZero() {
		return prepResult{State: stateFailed, Reason: st.failReason}, nil // a HEAD never retries
	}
	if !st.inAccount {
		if lister, ok := in.provider.(debrid.FileLister); ok {
			_, err := lister.Files(ctx, in.key.APIKey(), hash)
			switch {
			case err == nil:
				a.trk.update(k, func(s *prepState) { s.inAccount = true })
			case errors.Is(err, debrid.ErrNotFound):
			default:
				return a.failure(in, k, err)
			}
		}
	}
	return prepResult{State: statePreparing}, nil
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
		left, ok := a.trk.setCooldown(fp, name, d)
		if !ok {
			return prepResult{}, &budgetError{left} // the cooldown table is full of running ones
		}
		return cooldownResult(left), nil
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
