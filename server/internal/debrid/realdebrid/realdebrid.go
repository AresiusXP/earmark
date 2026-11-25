// Package realdebrid implements debrid.Provider for Real-Debrid (https://real-debrid.com).
//
// There is no batch cache check: RD removed "instantAvailability" in 2024, so CheckCached
// returns (nil, nil). Whether a torrent is cached is only learned by adding it (RD answers
// "downloaded" right after the file selection), so probing a release = adding it. Callers
// must do that only on an explicit user action, never while rendering a page.
//
// Flow of Resolve: find the torrent in the account by info hash (or add the magnet) ->
// select the audio files once -> poll torrents/info -> unrestrict/link of the wanted file.
//
// File indexes: model.File.Index is the Real-Debrid file id as is (1-based, unique per
// torrent). Files and Resolve use the same ids, so an index returned by Files can be given to
// Resolve unchanged. debrid.AutoFile (-1) can never collide with an id.
//
// Links: RD file selection can be done only once per torrent, and info.links holds one link
// per SELECTED file in ascending file-id order. Resolve therefore maps a file id to its
// position among the selected files and takes the link at that position.
//
// Rate limits: RD allows 250 requests/minute per account. Requests per key are spaced by
// MinInterval (250 ms, so <= 4 req/s), and a 429 blocks that key for the Retry-After period
// (default 10 s, at most 5 min): calls during the block fail fast with debrid.ErrRateLimited
// without touching the network. This package never retries on its own.
//
// Secrets: the API key is sent only as "Authorization: Bearer". Throttle and add state is
// keyed by a SHA-256 fingerprint of the key (in memory only); every error returned from this
// package has the key stripped.
package realdebrid

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/AresiusXP/earmark/server/internal/debrid"
	"github.com/AresiusXP/earmark/server/internal/model"
)

const (
	// DefaultBaseURL is the production Real-Debrid API.
	DefaultBaseURL = "https://api.real-debrid.com/rest/1.0"

	// DefaultMinInterval spaces the requests of one key: 4 per second, well below 250/min.
	DefaultMinInterval = 250 * time.Millisecond

	listPageSize = 100
	// maxListPages bounds the account scan (2000 torrents).
	maxListPages = 20
	maxBodyBytes = 16 << 20

	defaultRetryAfter = 10 * time.Second
	maxRetryAfter     = 5 * time.Minute
	// recentTTL is how long an id returned by addMagnet is remembered: GET /torrents is
	// eventually consistent, so a poll right after the add may not see the torrent yet.
	recentTTL = 30 * time.Minute
)

// Client implements debrid.Provider, debrid.FileLister and debrid.Deleter.
type Client struct {
	BaseURL string // no trailing slash needed; empty means DefaultBaseURL
	HTTP    *http.Client
	// MinInterval is the minimum spacing of requests per key; zero disables the throttle.
	MinInterval time.Duration

	now  func() time.Time // tests only; nil means time.Now
	once sync.Once
	mu   sync.Mutex
	next map[string]time.Time // fingerprint -> next free request slot
	hold map[string]time.Time // fingerprint -> blocked until (429)
	adds map[string]recentAdd // fingerprint|hash -> torrent id returned by addMagnet
}

type recentAdd struct {
	id string
	at time.Time
}

var (
	_ debrid.Provider   = (*Client)(nil)
	_ debrid.FileLister = (*Client)(nil)
	_ debrid.Deleter    = (*Client)(nil)

	_ debrid.StatusReader = (*Client)(nil)
)

// New returns a Client. An empty baseURL selects DefaultBaseURL, a nil hc a client with a 30s timeout.
func New(baseURL string, hc *http.Client) *Client {
	if hc == nil {
		hc = &http.Client{Timeout: 30 * time.Second}
	}
	return &Client{BaseURL: baseURL, HTTP: hc, MinInterval: DefaultMinInterval}
}

// Name implements debrid.Provider.
func (c *Client) Name() string { return "realdebrid" }

// RateLimitError is a rate-limit answer; it unwraps to debrid.ErrRateLimited.
type RateLimitError struct {
	RetryAfter time.Duration // how long the key is blocked
	msg        string
}

func (e *RateLimitError) Error() string { return e.msg }
func (e *RateLimitError) Unwrap() error { return debrid.ErrRateLimited }

// RetryAfterDuration implements debrid.RetryAfterer.
func (e *RateLimitError) RetryAfterDuration() time.Duration { return e.RetryAfter }

// Validate implements debrid.Provider.
func (c *Client) Validate(ctx context.Context, key string) error {
	if key == "" {
		return debrid.ErrBadKey
	}
	_, err := c.do(ctx, key, http.MethodGet, "/user", nil, nil)
	return err
}

// CheckCached implements debrid.Provider. Real-Debrid has no cache-check API any more, so this
// never calls the network and returns (nil, nil): callers fall back to learned availability.
func (c *Client) CheckCached(context.Context, string, []string) (map[string]debrid.Cached, error) {
	return nil, nil
}

// torrent is the subset of GET /torrents/info/{id} (and of the list items) that is used.
type torrent struct {
	ID       string   `json:"id"`
	Hash     string   `json:"hash"`
	Status   string   `json:"status"`
	Progress int      `json:"progress"` // 0..100
	Files    []file   `json:"files"`
	Links    []string `json:"links"`
}

type file struct {
	ID       int    `json:"id"`
	Path     string `json:"path"`
	Bytes    int64  `json:"bytes"`
	Selected int    `json:"selected"`
}

func (t *torrent) percent() int { return max(0, min(100, t.Progress)) }

func failedStatus(s string) bool {
	switch s {
	case "magnet_error", "error", "virus", "dead":
		return true
	}
	return false
}

func pendingStatus(s string) bool {
	switch s {
	case "magnet_conversion", "queued", "downloading", "compressing", "uploading":
		return true
	}
	return false
}

// rank orders duplicates of one hash in the account: best first.
func (t *torrent) rank() int {
	switch {
	case t.Status == "downloaded":
		return 0
	case failedStatus(t.Status):
		return 3
	case t.Status == "waiting_files_selection":
		return 2
	}
	return 1
}

func (f file) cleanPath() string { return strings.TrimPrefix(f.Path, "/") }

func toFiles(files []file) []model.File {
	out := make([]model.File, 0, len(files))
	for _, f := range files {
		out = append(out, model.File{Index: f.ID, Path: f.cleanPath(), Size: f.Bytes})
	}
	return out
}

func (t *torrent) selected() []file {
	var sel []file
	for _, f := range t.Files {
		if f.Selected == 1 {
			sel = append(sel, f)
		}
	}
	sort.Slice(sel, func(i, j int) bool { return sel[i].ID < sel[j].ID })
	return sel
}

// largestAudio returns the id of the largest audio file.
func largestAudio(files []file) (int, bool) {
	best, bestSize, found := 0, int64(-1), false
	for _, f := range files {
		if !model.IsAudioPath(f.Path) || f.Bytes <= bestSize {
			continue
		}
		best, bestSize, found = f.ID, f.Bytes, true
	}
	return best, found
}

func hasFile(files []file, id int) bool {
	for _, f := range files {
		if f.ID == id {
			return true
		}
	}
	return false
}

var (
	hashRe = regexp.MustCompile(`^[0-9a-f]{40}$`)
	btihRe = regexp.MustCompile(`(?i)xt=urn:btih:([0-9a-f]{40})`)
)

// Resolve implements debrid.Provider. The torrent is found in the account by info hash or
// added; its audio files are selected once (all audio files plus the requested one, because
// the selection cannot be changed later and the caller usually wants the whole book); when RD
// reports it downloaded, the file's link is unrestricted into a short-lived https CDN URL.
func (c *Client) Resolve(ctx context.Context, key string, req debrid.ResolveRequest) (debrid.ResolveResult, error) {
	if key == "" {
		return debrid.ResolveResult{}, debrid.ErrBadKey
	}
	hash := strings.ToLower(strings.TrimSpace(req.InfoHash))
	if hash == "" {
		if m := btihRe.FindStringSubmatch(req.Magnet); m != nil {
			hash = strings.ToLower(m[1])
		}
	}
	if !hashRe.MatchString(hash) {
		return debrid.ResolveResult{}, fmt.Errorf("realdebrid: resolve: invalid info hash: %w", debrid.ErrNotFound)
	}
	magnet := req.Magnet
	if magnet == "" {
		magnet = "magnet:?xt=urn:btih:" + hash
	} else if m := btihRe.FindStringSubmatch(magnet); m == nil || strings.ToLower(m[1]) != hash {
		return debrid.ResolveResult{}, fmt.Errorf("realdebrid: resolve: magnet does not match info hash: %w", debrid.ErrNotFound)
	}

	t, err := c.locate(ctx, key, hash)
	if err != nil {
		return debrid.ResolveResult{}, err
	}
	if t == nil {
		id, err := c.addMagnet(ctx, key, magnet)
		if err != nil {
			return debrid.ResolveResult{}, err
		}
		c.remember(key, hash, id)
		t, err = c.info(ctx, key, id)
		if err != nil {
			return debrid.ResolveResult{}, err
		}
		if !strings.EqualFold(t.Hash, hash) {
			return debrid.ResolveResult{}, c.errorf(key, "addMagnet: returned torrent hash does not match the requested one")
		}
	}

	selected := false
	for {
		switch {
		case failedStatus(t.Status):
			return debrid.ResolveResult{}, fmt.Errorf("realdebrid: torrent status %q: %w", t.Status, debrid.ErrFailed)
		case pendingStatus(t.Status):
			return debrid.ResolveResult{Pending: true, Progress: t.percent()}, nil
		case t.Status == "waiting_files_selection":
			if selected {
				return debrid.ResolveResult{Pending: true}, nil
			}
			if len(t.Files) == 0 {
				return debrid.ResolveResult{Pending: true}, nil
			}
			ids, err := selection(t.Files, req.FileIndex)
			if err != nil {
				return debrid.ResolveResult{}, err
			}
			if err := c.selectFiles(ctx, key, t.ID, ids); err != nil {
				return debrid.ResolveResult{}, err
			}
			selected = true
			if t, err = c.info(ctx, key, t.ID); err != nil {
				return debrid.ResolveResult{}, err
			}
		case t.Status == "downloaded":
			return c.unrestrictFile(ctx, key, t, req.FileIndex)
		default:
			return debrid.ResolveResult{}, fmt.Errorf("realdebrid: unknown torrent status %q: %w", t.Status, debrid.ErrNotFound)
		}
	}
}

// selection returns the comma separated file ids to select: every audio file plus the
// requested one (so the requested file is always included and AutoFile needs an audio file).
func selection(files []file, want int) (string, error) {
	set := map[int]bool{}
	for _, f := range files {
		if model.IsAudioPath(f.Path) {
			set[f.ID] = true
		}
	}
	if want == debrid.AutoFile {
		if len(set) == 0 {
			return "", fmt.Errorf("realdebrid: no audio file in torrent: %w", debrid.ErrNotFound)
		}
	} else {
		if !hasFile(files, want) {
			return "", fmt.Errorf("realdebrid: file %d not in torrent: %w", want, debrid.ErrNotFound)
		}
		set[want] = true
	}
	ids := make([]int, 0, len(set))
	for id := range set {
		ids = append(ids, id)
	}
	sort.Ints(ids)
	parts := make([]string, len(ids))
	for i, id := range ids {
		parts[i] = strconv.Itoa(id)
	}
	return strings.Join(parts, ","), nil
}

func (c *Client) unrestrictFile(ctx context.Context, key string, t *torrent, want int) (debrid.ResolveResult, error) {
	sel := t.selected()
	if len(sel) == 0 {
		sel = append(sel, t.Files...) // no "selected" flags reported: assume every file
		sort.Slice(sel, func(i, j int) bool { return sel[i].ID < sel[j].ID })
	}
	if len(t.Links) == 0 {
		return debrid.ResolveResult{}, fmt.Errorf("realdebrid: downloaded torrent has no links: %w", debrid.ErrFailed)
	}
	id := want
	if want == debrid.AutoFile {
		var ok bool
		if id, ok = largestAudio(sel); !ok {
			return debrid.ResolveResult{}, fmt.Errorf("realdebrid: no audio file in torrent: %w", debrid.ErrNotFound)
		}
	}
	pos := -1
	for i, f := range sel {
		if f.ID == id {
			pos = i
			break
		}
	}
	if pos < 0 {
		return debrid.ResolveResult{}, fmt.Errorf("realdebrid: file %d was not selected in the torrent: %w", id, debrid.ErrNotFound)
	}
	if len(t.Links) != len(sel) {
		return debrid.ResolveResult{}, c.errorf(key, "torrent has %d links for %d selected files, cannot map file %d", len(t.Links), len(sel), id)
	}
	link, err := c.unrestrict(ctx, key, t.Links[pos])
	if err != nil {
		return debrid.ResolveResult{}, err
	}
	return debrid.ResolveResult{URL: link}, nil
}

// Files implements debrid.FileLister. It never adds a torrent: a hash that is not in the
// account answers debrid.ErrNotFound. Before the selection it lists every file; afterwards
// only the selected (downloadable) files. The list is empty while RD still converts the magnet.
func (c *Client) Files(ctx context.Context, key, hash string) ([]model.File, error) {
	if key == "" {
		return nil, debrid.ErrBadKey
	}
	hash = strings.ToLower(strings.TrimSpace(hash))
	if !hashRe.MatchString(hash) {
		return nil, fmt.Errorf("realdebrid: files: invalid info hash: %w", debrid.ErrNotFound)
	}
	t, err := c.locate(ctx, key, hash)
	if err != nil {
		return nil, err
	}
	if t == nil {
		return nil, fmt.Errorf("realdebrid: torrent not in the account: %w", debrid.ErrNotFound)
	}
	if failedStatus(t.Status) {
		return nil, fmt.Errorf("realdebrid: torrent status %q: %w", t.Status, debrid.ErrFailed)
	}
	if sel := t.selected(); len(sel) > 0 {
		return toFiles(sel), nil
	}
	return toFiles(t.Files), nil
}

// Status implements debrid.StatusReader. It only reads (GET /torrents/info of the remembered id,
// else the account listing): it never adds, never selects files and never unrestricts a link.
// "downloaded" is ready; every other status (including waiting for the file selection, which only
// Resolve may do) is pending.
func (c *Client) Status(ctx context.Context, key, hash string) (debrid.Status, error) {
	if key == "" {
		return debrid.Status{}, debrid.ErrBadKey
	}
	hash = strings.ToLower(strings.TrimSpace(hash))
	if !hashRe.MatchString(hash) {
		return debrid.Status{}, fmt.Errorf("realdebrid: status: invalid info hash: %w", debrid.ErrNotFound)
	}
	t, err := c.locate(ctx, key, hash)
	if err != nil {
		return debrid.Status{}, err
	}
	if t == nil {
		return debrid.Status{}, fmt.Errorf("realdebrid: torrent not in the account: %w", debrid.ErrNotFound)
	}
	switch {
	case failedStatus(t.Status):
		return debrid.Status{}, fmt.Errorf("realdebrid: torrent status %q: %w", t.Status, debrid.ErrFailed)
	case t.Status == "downloaded":
		files := toFiles(t.Files)
		if sel := t.selected(); len(sel) > 0 {
			files = toFiles(sel)
		}
		return debrid.Status{State: debrid.StateReady, Progress: 100, Files: files}, nil
	}
	return debrid.Status{State: debrid.StatePending, Progress: t.percent()}, nil
}

// Delete implements debrid.Deleter: it removes the torrent with this hash from the account
// (all duplicates). A hash that is not in the account is not an error.
func (c *Client) Delete(ctx context.Context, key, hash string) error {
	if key == "" {
		return debrid.ErrBadKey
	}
	hash = strings.ToLower(strings.TrimSpace(hash))
	if !hashRe.MatchString(hash) {
		return fmt.Errorf("realdebrid: delete: invalid info hash: %w", debrid.ErrNotFound)
	}
	ids := map[string]bool{}
	if id, ok := c.recent(key, hash); ok {
		ids[id] = true
	}
	items, err := c.list(ctx, key, hash)
	if err != nil {
		return err
	}
	for _, it := range items {
		ids[it.ID] = true
	}
	c.forget(key, hash)
	for id := range ids {
		_, err := c.do(ctx, key, http.MethodDelete, "/torrents/delete/"+url.PathEscape(id), nil, nil)
		if err != nil && !errors.Is(err, debrid.ErrNotFound) {
			return err
		}
	}
	return nil
}

// locate finds the torrent of hash in the account and returns its full info; nil if absent.
func (c *Client) locate(ctx context.Context, key, hash string) (*torrent, error) {
	if id, ok := c.recent(key, hash); ok {
		t, err := c.info(ctx, key, id)
		if err == nil {
			return t, nil
		}
		if !errors.Is(err, debrid.ErrNotFound) {
			return nil, err
		}
		c.forget(key, hash)
	}
	items, err := c.list(ctx, key, hash)
	if err != nil || len(items) == 0 {
		return nil, err
	}
	best := items[0]
	for _, it := range items[1:] {
		if it.rank() < best.rank() {
			best = it
		}
	}
	return c.info(ctx, key, best.ID)
}

// list returns the torrents of the account whose hash matches.
func (c *Client) list(ctx context.Context, key, hash string) ([]torrent, error) {
	var out []torrent
	for page := 1; page <= maxListPages; page++ {
		q := url.Values{"page": {strconv.Itoa(page)}, "limit": {strconv.Itoa(listPageSize)}}
		raw, err := c.do(ctx, key, http.MethodGet, "/torrents", q, nil)
		if err != nil {
			return nil, err
		}
		var items []torrent
		if len(raw) > 0 { // an empty library answers 204 No Content
			if err := json.Unmarshal(raw, &items); err != nil {
				return nil, c.errorf(key, "torrents: %v", err)
			}
		}
		for _, it := range items {
			if strings.EqualFold(it.Hash, hash) && it.ID != "" {
				out = append(out, it)
			}
		}
		if len(items) < listPageSize {
			break
		}
	}
	return out, nil
}

func (c *Client) info(ctx context.Context, key, id string) (*torrent, error) {
	raw, err := c.do(ctx, key, http.MethodGet, "/torrents/info/"+url.PathEscape(id), nil, nil)
	if err != nil {
		return nil, err
	}
	var t torrent
	if err := json.Unmarshal(raw, &t); err != nil || t.ID == "" {
		return nil, c.errorf(key, "torrents/info: unexpected response: %s", truncate(string(raw), 100))
	}
	return &t, nil
}

func (c *Client) addMagnet(ctx context.Context, key, magnet string) (string, error) {
	raw, err := c.do(ctx, key, http.MethodPost, "/torrents/addMagnet", nil, url.Values{"magnet": {magnet}})
	if err != nil {
		return "", err
	}
	var r struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(raw, &r); err != nil || r.ID == "" {
		return "", c.errorf(key, "addMagnet: unexpected response: %s", truncate(string(raw), 100))
	}
	return r.ID, nil
}

func (c *Client) selectFiles(ctx context.Context, key, id, ids string) error {
	_, err := c.do(ctx, key, http.MethodPost, "/torrents/selectFiles/"+url.PathEscape(id), nil, url.Values{"files": {ids}})
	var ae *apiError
	if errors.As(err, &ae) && ae.code == errActionAlreadyDone {
		return nil // a concurrent Resolve selected first
	}
	return err
}

func (c *Client) unrestrict(ctx context.Context, key, link string) (string, error) {
	raw, err := c.do(ctx, key, http.MethodPost, "/unrestrict/link", nil, url.Values{"link": {link}})
	if err != nil {
		return "", err
	}
	var r struct {
		Download string `json:"download"`
	}
	if err := json.Unmarshal(raw, &r); err != nil || r.Download == "" {
		return "", c.errorf(key, "unrestrict/link: no download link in response")
	}
	u, err := url.Parse(r.Download)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return "", c.errorf(key, "unrestrict/link: refusing a download link that is not https")
	}
	return r.Download, nil
}

func (c *Client) base() string {
	if c.BaseURL == "" {
		return DefaultBaseURL
	}
	return strings.TrimRight(c.BaseURL, "/")
}

func (c *Client) clock() time.Time {
	if c.now != nil {
		return c.now()
	}
	return time.Now()
}

func (c *Client) init() {
	c.once.Do(func() {
		c.next = map[string]time.Time{}
		c.hold = map[string]time.Time{}
		c.adds = map[string]recentAdd{}
	})
}

// fingerprint identifies a key in the in-memory state without keeping the key itself.
func fingerprint(key string) string {
	sum := sha256.Sum256([]byte(key))
	return hex.EncodeToString(sum[:8])
}

func (c *Client) remember(key, hash, id string) {
	c.init()
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.clock()
	for k, a := range c.adds {
		if now.Sub(a.at) > recentTTL {
			delete(c.adds, k)
		}
	}
	c.adds[fingerprint(key)+"|"+hash] = recentAdd{id: id, at: now}
}

func (c *Client) recent(key, hash string) (string, bool) {
	c.init()
	c.mu.Lock()
	defer c.mu.Unlock()
	k := fingerprint(key) + "|" + hash
	a, ok := c.adds[k]
	if ok && c.clock().Sub(a.at) > recentTTL {
		delete(c.adds, k)
		ok = false
	}
	return a.id, ok
}

func (c *Client) forget(key, hash string) {
	c.init()
	c.mu.Lock()
	defer c.mu.Unlock()
	delete(c.adds, fingerprint(key)+"|"+hash)
}

// throttle blocks until the key may send its next request. A key that was rate limited fails
// fast until the block ends.
func (c *Client) throttle(ctx context.Context, key string) error {
	c.init()
	fp := fingerprint(key)
	c.mu.Lock()
	now := c.clock()
	if until, ok := c.hold[fp]; ok {
		if now.Before(until) {
			c.mu.Unlock()
			left := until.Sub(now)
			return &RateLimitError{RetryAfter: left, msg: fmt.Sprintf("realdebrid: rate limited, retry in %s", left.Round(time.Second))}
		}
		delete(c.hold, fp)
	}
	var wait time.Duration
	if c.MinInterval > 0 {
		slot := now
		if n := c.next[fp]; n.After(slot) {
			slot = n
		}
		c.next[fp] = slot.Add(c.MinInterval)
		wait = slot.Sub(now)
		if len(c.next) > 1024 {
			for k, n := range c.next {
				if n.Before(now) {
					delete(c.next, k)
				}
			}
		}
	}
	c.mu.Unlock()
	if wait <= 0 {
		return nil
	}
	t := time.NewTimer(wait)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (c *Client) block(key string, d time.Duration) {
	c.init()
	c.mu.Lock()
	defer c.mu.Unlock()
	c.hold[fingerprint(key)] = c.clock().Add(d)
}

func retryAfter(h string, now time.Time) time.Duration {
	h = strings.TrimSpace(h)
	d := defaultRetryAfter
	if secs, err := strconv.Atoi(h); err == nil && secs >= 0 {
		d = time.Duration(secs) * time.Second
	} else if t, err := http.ParseTime(h); err == nil {
		d = t.Sub(now)
	}
	return max(time.Second, min(d, maxRetryAfter))
}

// do performs one request and returns the body of a 2xx answer (empty for 204). The path is
// relative to the base URL. All returned errors are redacted.
func (c *Client) do(ctx context.Context, key, method, path string, query, form url.Values) ([]byte, error) {
	if err := c.throttle(ctx, key); err != nil {
		return nil, err
	}
	target := c.base() + path
	if len(query) > 0 {
		target += "?" + query.Encode()
	}
	var rdr io.Reader
	if form != nil {
		rdr = strings.NewReader(form.Encode())
	}
	req, err := http.NewRequestWithContext(ctx, method, target, rdr)
	if err != nil {
		return nil, c.errorf(key, "%s %s: bad request", method, path)
	}
	req.Header.Set("Authorization", "Bearer "+key)
	req.Header.Set("Accept", "application/json")
	if form != nil {
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	hc := c.HTTP
	if hc == nil {
		hc = http.DefaultClient
	}
	resp, err := hc.Do(req)
	if err != nil {
		var ue *url.Error
		if errors.As(err, &ue) {
			err = ue.Err
		}
		return nil, &safeError{msg: redact(key, fmt.Sprintf("realdebrid: %s %s: %v", method, path, err)), err: err}
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes))
	if err != nil {
		return nil, &safeError{msg: redact(key, fmt.Sprintf("realdebrid: %s %s: reading body: %v", method, path, err)), err: err}
	}
	if resp.StatusCode >= 200 && resp.StatusCode < 300 {
		return raw, nil
	}

	ae := &apiError{status: resp.StatusCode, method: method, endpoint: path}
	var body struct {
		Error   string `json:"error"`
		Code    int    `json:"error_code"`
		Details string `json:"error_details"`
	}
	if json.Unmarshal(raw, &body) == nil {
		ae.code = body.Code
		ae.detail = body.Error
		if body.Details != "" {
			ae.detail += ": " + body.Details
		}
	} else {
		ae.detail = string(raw)
	}
	ae.detail = redact(key, truncate(ae.detail, 200))
	if ae.sentinel() == debrid.ErrRateLimited && (resp.StatusCode == http.StatusTooManyRequests || ae.code == errTooManyRequests || ae.code == errSlowDown) {
		d := retryAfter(resp.Header.Get("Retry-After"), c.clock())
		c.block(key, d)
		return nil, &RateLimitError{RetryAfter: d, msg: ae.Error()}
	}
	return nil, ae
}

func (c *Client) errorf(key, format string, args ...any) error {
	return &safeError{msg: redact(key, "realdebrid: "+fmt.Sprintf(format, args...))}
}

// redact removes the key from s, also in the percent-encoded forms an upstream may echo back.
func redact(key, s string) string {
	if key == "" {
		return s
	}
	for _, k := range []string{key, url.QueryEscape(key), url.PathEscape(key)} {
		s = strings.ReplaceAll(s, k, "***")
	}
	return s
}

func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if len(s) > n {
		return s[:n] + "…"
	}
	return s
}

type safeError struct {
	msg string
	err error
}

func (e *safeError) Error() string { return e.msg }
func (e *safeError) Unwrap() error { return e.err }

// Real-Debrid error_code values that matter here (https://api.real-debrid.com/#api_errors).
const (
	errSlowDown          = 5
	errNotFound          = 7
	errBadToken          = 8
	errPermissionDenied  = 9
	errInvalidLogin      = 12
	errInvalidPassword   = 13
	errAccountLocked     = 14
	errNotActivated      = 15
	errHosterLimit       = 18
	errTooManyActive     = 21
	errTrafficExhausted  = 23
	errFileUnavailable   = 24
	errUploadTooBig      = 26
	errFileNotAllowed    = 28
	errTorrentTooBig     = 29
	errTorrentInvalid    = 30
	errActionAlreadyDone = 31
	errTooManyRequests   = 34
	errInfringingFile    = 35
	errFairUsage         = 36
)

// apiError is a non-2xx Real-Debrid answer. It is already redacted and unwraps to the
// matching debrid sentinel error, if any.
type apiError struct {
	status   int
	code     int
	detail   string
	method   string
	endpoint string
}

func (e *apiError) Error() string {
	s := fmt.Sprintf("realdebrid: %s %s: HTTP %d", e.method, e.endpoint, e.status)
	if e.code != 0 {
		s += " error_code " + strconv.Itoa(e.code)
	}
	if e.detail != "" {
		s += ": " + e.detail
	}
	return s
}

func (e *apiError) sentinel() error {
	switch e.code {
	case errBadToken, errPermissionDenied, errInvalidLogin, errInvalidPassword, errAccountLocked, errNotActivated:
		return debrid.ErrBadKey
	case errSlowDown, errTooManyRequests, errFairUsage, errTooManyActive, errHosterLimit, errTrafficExhausted:
		return debrid.ErrRateLimited
	case errNotFound:
		return debrid.ErrNotFound
	case errFileUnavailable, errUploadTooBig, errFileNotAllowed, errTorrentTooBig, errTorrentInvalid, errInfringingFile:
		return debrid.ErrFailed
	}
	switch e.status {
	case http.StatusUnauthorized:
		return debrid.ErrBadKey
	case http.StatusForbidden:
		if e.code == 0 {
			return debrid.ErrBadKey
		}
	case http.StatusTooManyRequests:
		return debrid.ErrRateLimited
	case http.StatusNotFound:
		return debrid.ErrNotFound
	}
	return nil
}

func (e *apiError) Unwrap() error { return e.sentinel() }
