package appapi

import (
	"context"
	"encoding/base32"
	"encoding/hex"
	"errors"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/AresiusXP/earmark/server/internal/debrid"
	"github.com/AresiusXP/earmark/server/internal/library"
	"github.com/AresiusXP/earmark/server/internal/linkhosts"
	"github.com/AresiusXP/earmark/server/internal/model"
	"github.com/AresiusXP/earmark/server/internal/sources"
)

const (
	maxMagnetLen        = 2048
	maxMagnetNameChars  = 200
	maxTorrentFilesBody = 2 << 20 // 2000 files x 512 bytes of path, JSON escapes included
	maxFileIndex        = 1 << 20
)

var hashRe = regexp.MustCompile(`^[0-9a-f]{40}$`)

type prepareDTO struct {
	State      string    `json:"state"`
	Progress   *int      `json:"progress,omitempty"`
	Reason     string    `json:"reason,omitempty"`
	Files      []fileDTO `json:"files,omitempty"`
	RetryAfter int       `json:"retry_after_s,omitempty"`
}

func toPrepareDTO(r prepResult) prepareDTO {
	d := prepareDTO{State: r.State, Reason: r.Reason}
	if r.HasProgress {
		p := max(0, min(100, r.Progress))
		d.Progress = &p
	}
	if len(r.Files) > 0 {
		d.Files = toFiles(r.Files)
	}
	if r.RetryAfter > 0 {
		d.RetryAfter = max(1, int((r.RetryAfter + 999_999_999).Seconds()))
	}
	return d
}

type httpErr struct {
	status    int
	code, msg string
}

// checkMagnet verifies that magnet is a well-formed magnet link for exactly hash and returns its
// display name (sanitised, may be empty).
func checkMagnet(hash, magnet string) (string, error) {
	if len(magnet) == 0 || len(magnet) > maxMagnetLen || !strings.HasPrefix(strings.ToLower(magnet), "magnet:?") {
		return "", errors.New("magnet must be a magnet link of at most 2048 bytes")
	}
	for _, r := range magnet {
		if r < 0x20 || r == 0x7f {
			return "", errors.New("magnet contains control characters")
		}
	}
	u, err := url.Parse(magnet)
	if err != nil || !strings.EqualFold(u.Scheme, "magnet") {
		return "", errors.New("magnet is not well-formed")
	}
	q, err := url.ParseQuery(u.RawQuery)
	if err != nil {
		return "", errors.New("magnet is not well-formed")
	}
	found := false
	for _, xt := range q["xt"] {
		rest, ok := strings.CutPrefix(strings.ToLower(xt), "urn:btih:")
		if !ok {
			continue
		}
		h := rest
		if len(rest) == 32 { // base32 form of the 20-byte hash
			raw, err := base32.StdEncoding.DecodeString(strings.ToUpper(rest))
			if err != nil {
				return "", errors.New("magnet has an invalid info hash")
			}
			h = hex.EncodeToString(raw)
		}
		if h != hash {
			return "", errors.New("magnet does not match the info hash")
		}
		found = true
	}
	if !found {
		return "", errors.New("magnet has no info hash")
	}
	name := strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, strings.TrimSpace(q.Get("dn")))
	if utf8.RuneCountInString(name) > maxMagnetNameChars {
		name = string([]rune(name)[:maxMagnetNameChars])
	}
	return strings.ToValidUTF8(name, ""), nil
}

// release returns the stored release, or (when unknown and a magnet is given) inserts it first.
// The stored magnet is rebuilt from the info hash and the sanitised name, so a client cannot
// plant its own trackers in a row other users read.
func (a *app) release(ctx context.Context, hash, magnet string) (model.Release, *httpErr) {
	rel, err := a.d.Store.GetRelease(ctx, hash)
	if err != nil {
		a.log.Error("load release failed", "err", err)
		return model.Release{}, &httpErr{http.StatusInternalServerError, "upstream_failed", "internal error"}
	}
	if rel != nil {
		return *rel, nil
	}
	if magnet == "" {
		return model.Release{}, &httpErr{http.StatusNotFound, "not_found", "unknown release; send its magnet"}
	}
	name, err := checkMagnet(hash, magnet)
	if err != nil {
		return model.Release{}, &httpErr{http.StatusBadRequest, "invalid_request", err.Error()}
	}
	n := model.Release{InfoHash: hash, Title: name, Source: "app", Magnet: sources.BuildMagnet(hash, name)}
	if _, err := a.d.Store.InsertReleaseIfAbsent(ctx, n); err != nil {
		a.log.Error("insert release failed", "err", err)
		return model.Release{}, &httpErr{http.StatusInternalServerError, "upstream_failed", "internal error"}
	}
	if rel, err = a.d.Store.GetRelease(ctx, hash); err != nil || rel == nil {
		return n, nil
	}
	return *rel, nil
}

func (a *app) writeErr(w http.ResponseWriter, e *httpErr) { WriteError(w, e.status, e.code, e.msg) }

// admit makes a flight start only when the work gate has room for one more unit for the key.
func (a *app) admit(fingerprint string) func() (func(), error) {
	return func() (func(), error) { return a.work.acquire(fingerprint) }
}

// prepareFail answers an error of a.prepare.
func (a *app) prepareFail(w http.ResponseWriter, err error) {
	var be *budgetError
	var busy *busyError
	switch {
	case errors.As(err, &be):
		writeRateLimited(w, be.retryAfter)
	case errors.As(err, &busy):
		writeRateLimited(w, busy.retryAfter)
	case errors.Is(err, errUpstream):
		WriteError(w, http.StatusBadGateway, "debrid_unavailable", "the debrid service could not be reached, try again")
	default:
		a.KeyError(w, err)
	}
}

func (a *app) prepareRoute(w http.ResponseWriter, r *http.Request) {
	p, _ := PrincipalFrom(r)
	hash := r.PathValue("hash")
	if !hashRe.MatchString(hash) {
		WriteError(w, http.StatusBadRequest, "invalid_request", "invalid info hash")
		return
	}
	var in struct {
		Provider string `json:"provider"`
		Magnet   string `json:"magnet"`
	}
	if !decode(w, r, &in) {
		return
	}
	prov, ok := a.d.Providers.Get(in.Provider)
	if !ok {
		WriteError(w, http.StatusBadRequest, "invalid_request", "unknown provider")
		return
	}
	key, err := a.DebridKey(r, in.Provider)
	if a.KeyError(w, err) {
		return
	}
	rel, he := a.release(r.Context(), hash, in.Magnet)
	if he != nil {
		a.writeErr(w, he)
		return
	}
	pin := prepIn{userID: p.UserID, key: key, provider: prov, rel: rel, fileIndex: debrid.AutoFile, allowAdd: true, wantFiles: true}
	res, err := a.prep.do(r.Context(), key.Fingerprint+"\x00"+in.Provider+"\x00"+hash, flightTimeout, a.admit(key.Fingerprint),
		func(ctx context.Context) (prepResult, error) { return a.prepare(ctx, pin) })
	if err != nil {
		if errors.Is(err, errFileNotFound) {
			WriteJSON(w, http.StatusOK, toPrepareDTO(prepResult{State: stateFailed, Reason: reasonNotFound}))
			return
		}
		a.prepareFail(w, err)
		return
	}
	if res.RetryAfter > 0 {
		w.Header().Set("Retry-After", strconv.Itoa(toPrepareDTO(res).RetryAfter))
	}
	res.URL = ""
	WriteJSON(w, http.StatusOK, toPrepareDTO(res))
}

// download serves GET and HEAD of one file of a release. The app uses it both to stream (the
// player opens it, follows the 302, seeks with Range requests and reconnects often) and to
// download for offline use. The resolved link is cached in memory per key (linkcache.go) and
// concurrent requests of one key share one provider call.
func (a *app) download(w http.ResponseWriter, r *http.Request) {
	p, _ := PrincipalFrom(r)
	hash := r.PathValue("hash")
	idx, err := strconv.Atoi(r.PathValue("index"))
	if !hashRe.MatchString(hash) || err != nil || idx < 0 || idx > maxFileIndex {
		WriteError(w, http.StatusBadRequest, "invalid_request", "invalid info hash or file index")
		return
	}
	provider := r.URL.Query().Get("provider")
	prov, ok := a.d.Providers.Get(provider)
	if !ok {
		WriteError(w, http.StatusBadRequest, "invalid_request", "provider must be torbox or realdebrid")
		return
	}
	key, err := a.DebridKey(r, provider)
	if a.KeyError(w, err) {
		return
	}
	fp := key.Fingerprint
	if link, ok := a.links.get(fp, provider, hash, idx); ok {
		a.redirect(w, provider, link)
		return
	}
	rel, err := a.d.Store.GetRelease(r.Context(), hash)
	if err != nil {
		a.log.Error("load release failed", "err", err)
		WriteError(w, http.StatusInternalServerError, "upstream_failed", "internal error")
		return
	}
	if rel == nil {
		WriteError(w, http.StatusNotFound, "not_found", "unknown release")
		return
	}

	head := r.Method == http.MethodHead
	mode := "get"
	if head {
		mode = "head"
	}
	pin := prepIn{userID: p.UserID, key: key, provider: prov, rel: *rel, fileIndex: idx, allowAdd: !head}
	res, err := a.dl.do(r.Context(), strings.Join([]string{fp, provider, hash, strconv.Itoa(idx), mode}, "\x00"), flightTimeout, a.admit(fp), func(ctx context.Context) (prepResult, error) {
		res, err := a.prepare(ctx, pin)
		if err == nil && res.State == stateReady && !res.ReadyNoLink {
			// Validated and cached inside the flight, so every waiter gets the same checked link.
			loc, cerr := a.allow.Check(provider, res.URL)
			if cerr != nil {
				a.log.Warn("provider link rejected by the host allowlist", "provider", provider)
				return prepResult{}, linkhosts.ErrBadLink
			}
			res.URL = loc
			a.links.put(fp, provider, hash, idx, loc)
		}
		return res, err
	})
	switch {
	case errors.Is(err, errFileNotFound):
		WriteError(w, http.StatusNotFound, "not_found", "no such file in this release")
	case errors.Is(err, linkhosts.ErrBadLink):
		WriteError(w, http.StatusBadGateway, "upstream_failed", "the provider answered an unexpected download host")
	case err != nil:
		a.prepareFail(w, err)
	case res.State == stateReady && res.ReadyNoLink:
		w.WriteHeader(http.StatusOK) // HEAD: ready, a GET redirects; the link is not made read-only
	case res.State == stateReady:
		a.redirect(w, provider, res.URL)
	case res.State == stateFailed:
		WriteError(w, http.StatusBadGateway, "upstream_failed", "the release failed at the provider: "+res.Reason)
	default:
		if res.RetryAfter > 0 {
			w.Header().Set("Retry-After", strconv.Itoa(toPrepareDTO(res).RetryAfter))
		}
		res.URL = ""
		WriteJSON(w, http.StatusAccepted, toPrepareDTO(res))
	}
}

// redirect answers 302 to a link of the provider after checking it against the host allowlist
// once more (a cached link is checked again: the table may be stricter than when it was cached).
// The response is no-store (the API default) and carries no body worth logging.
func (a *app) redirect(w http.ResponseWriter, provider, link string) {
	loc, err := a.allow.Check(provider, link)
	if err != nil {
		a.log.Warn("provider link rejected by the host allowlist", "provider", provider)
		WriteError(w, http.StatusBadGateway, "upstream_failed", "the provider answered an unexpected download host")
		return
	}
	w.Header().Set("Location", loc)
	w.WriteHeader(http.StatusFound)
}

// ---- torrent-files --------------------------------------------------------------------------

// cleanTorrentPath normalises a reported path: separators become "/", empty and "." segments go,
// and NUL/control characters, ".." segments, invalid UTF-8 and over-long paths are rejected.
func cleanTorrentPath(p string) (string, bool) { return model.NormalizePath(p) }

func (a *app) torrentFiles(w http.ResponseWriter, r *http.Request) {
	hash := r.PathValue("hash")
	if !hashRe.MatchString(hash) {
		WriteError(w, http.StatusBadRequest, "invalid_request", "invalid info hash")
		return
	}
	var in struct {
		Files []struct {
			Index *int   `json:"index"`
			Path  string `json:"path"`
			Size  *int64 `json:"size"`
		} `json:"files"`
	}
	if err := readJSONLimit(w, r, &in, maxTorrentFilesBody, false); err != nil {
		WriteError(w, http.StatusBadRequest, "invalid_request", "invalid JSON body")
		return
	}
	if len(in.Files) == 0 || len(in.Files) > maxFiles {
		WriteError(w, http.StatusBadRequest, "invalid_request", "files must hold 1-2000 entries")
		return
	}
	files := make([]model.File, 0, len(in.Files))
	seen := make(map[int]bool, len(in.Files))
	for _, f := range in.Files {
		path, ok := cleanTorrentPath(f.Path)
		if !ok || f.Index == nil || f.Size == nil || *f.Index < 0 || *f.Index > maxFileIndex || *f.Size < 0 || *f.Size > 1<<50 || seen[*f.Index] {
			WriteError(w, http.StatusBadRequest, "invalid_request", "each file needs a unique index >= 0, a clean path of at most 512 bytes and a size >= 0")
			return
		}
		seen[*f.Index] = true
		files = append(files, model.File{Index: *f.Index, Path: path, Size: *f.Size})
	}
	rel, err := a.d.Store.GetRelease(r.Context(), hash)
	if err != nil {
		a.log.Error("load release failed", "err", err)
		WriteError(w, http.StatusInternalServerError, "upstream_failed", "internal error")
		return
	}
	if rel == nil {
		WriteError(w, http.StatusNotFound, "not_found", "unknown release")
		return
	}
	stored, _, err := a.d.Store.SaveClientFiles(r.Context(), hash, files)
	switch {
	case errors.Is(err, library.ErrUnknownRelease):
		WriteError(w, http.StatusNotFound, "not_found", "unknown release")
	case err != nil:
		a.log.Error("store client files failed", "err", err)
		WriteError(w, http.StatusInternalServerError, "upstream_failed", "internal error")
	default:
		WriteJSON(w, http.StatusOK, map[string]any{"torrent_files": toFiles(stored)})
	}
}

// ---- durations ------------------------------------------------------------------------------

// durations stores audio durations a phone measured, for files of this release only: every path is
// normalised and must be an audio file of the debrid or the client-reported file list. First report
// wins; nothing is ever replaced.
func (a *app) durations(w http.ResponseWriter, r *http.Request) {
	hash := r.PathValue("hash")
	if !hashRe.MatchString(hash) {
		WriteError(w, http.StatusBadRequest, "invalid_request", "invalid info hash")
		return
	}
	var in struct {
		Durations []struct {
			Path       string `json:"path"`
			DurationMs *int64 `json:"duration_ms"`
		} `json:"durations"`
	}
	if err := readJSONLimit(w, r, &in, maxTorrentFilesBody, false); err != nil {
		WriteError(w, http.StatusBadRequest, "invalid_request", "invalid JSON body")
		return
	}
	if len(in.Durations) == 0 || len(in.Durations) > library.MaxDurations {
		WriteError(w, http.StatusBadRequest, "invalid_request", "durations must hold 1-2000 entries")
		return
	}
	ds := make([]library.FileDuration, 0, len(in.Durations))
	seen := make(map[string]bool, len(in.Durations))
	for _, d := range in.Durations {
		path, ok := cleanTorrentPath(d.Path)
		if !ok || d.DurationMs == nil || *d.DurationMs < library.MinDurationMs || *d.DurationMs > library.MaxDurationMs || seen[path] {
			WriteError(w, http.StatusBadRequest, "invalid_request", "each entry needs a unique clean path of at most 512 bytes and duration_ms between 1000 (1 s) and 172800000 (48 h)")
			return
		}
		seen[path] = true
		ds = append(ds, library.FileDuration{Path: path, DurationMs: *d.DurationMs})
	}
	rel, err := a.d.Store.GetRelease(r.Context(), hash)
	if err != nil {
		a.log.Error("load release failed", "err", err)
		WriteError(w, http.StatusInternalServerError, "upstream_failed", "internal error")
		return
	}
	if rel == nil {
		WriteError(w, http.StatusNotFound, "not_found", "unknown release")
		return
	}
	known, err := a.knownAudioPaths(r.Context(), hash)
	if err != nil {
		a.log.Error("load release files failed", "err", err)
		WriteError(w, http.StatusInternalServerError, "upstream_failed", "internal error")
		return
	}
	matched := ds[:0:0]
	for _, d := range ds {
		if known[d.Path] {
			matched = append(matched, d)
		}
	}
	stored, err := a.d.Store.SaveFileDurations(r.Context(), hash, matched)
	switch {
	case errors.Is(err, library.ErrUnknownRelease):
		WriteError(w, http.StatusNotFound, "not_found", "unknown release")
		return
	case err != nil:
		a.log.Error("store file durations failed", "err", err)
		WriteError(w, http.StatusInternalServerError, "upstream_failed", "internal error")
		return
	}
	type durationDTO struct {
		Path       string `json:"path"`
		DurationMs int64  `json:"duration_ms"`
	}
	out := make([]durationDTO, len(stored))
	for i, d := range stored {
		out[i] = durationDTO{Path: d.Path, DurationMs: d.DurationMs}
	}
	WriteJSON(w, http.StatusOK, map[string]any{"durations": out, "ignored": len(ds) - len(matched)})
}

// knownAudioPaths is the set of normalised paths of the audio files in the release's debrid and
// client-reported file lists.
func (a *app) knownAudioPaths(ctx context.Context, hash string) (map[string]bool, error) {
	debrid, err := a.d.Store.Files(ctx, []string{hash})
	if err != nil {
		return nil, err
	}
	client, err := a.d.Store.ClientFiles(ctx, []string{hash})
	if err != nil {
		return nil, err
	}
	known := map[string]bool{}
	for _, fs := range [][]model.File{debrid[hash], client[hash]} {
		for _, f := range fs {
			if p, ok := model.NormalizePath(f.Path); ok && f.IsAudio() {
				known[p] = true
			}
		}
	}
	return known, nil
}
