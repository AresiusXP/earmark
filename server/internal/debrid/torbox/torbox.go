// Package torbox implements debrid.Provider for TorBox (https://torbox.app).
//
// Rate limits: TorBox allows roughly 300 requests/minute per key. This package never retries
// on its own; HTTP 429 and the *_LIMIT error codes surface as debrid.ErrRateLimited.
//
// Secrets: the API key is sent as "Authorization: Bearer" everywhere, and additionally as the
// "token" query parameter of requestdl (the only endpoint that requires it). Every error
// returned from this package has the key (and the URL query) stripped.
package torbox

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

const (
	// DefaultBaseURL is the production TorBox API.
	DefaultBaseURL = "https://api.torbox.app/v1"

	checkBatchSize = 100
	listPageSize   = 100
	// maxListPages bounds the account scan (2000 torrents) so one Resolve cannot burn the
	// 300 req/min budget.
	maxListPages = 20
	maxBodyBytes = 32 << 20
)

// Client implements debrid.Provider.
type Client struct {
	BaseURL string // no trailing slash needed; empty means DefaultBaseURL
	HTTP    *http.Client
}

var _ debrid.Provider = (*Client)(nil)

// New returns a Client. An empty baseURL selects DefaultBaseURL, a nil hc a client with a 30s timeout.
func New(baseURL string, hc *http.Client) *Client {
	if hc == nil {
		hc = &http.Client{Timeout: 30 * time.Second}
	}
	return &Client{BaseURL: baseURL, HTTP: hc}
}

// Name implements debrid.Provider.
func (c *Client) Name() string { return "torbox" }

// Validate implements debrid.Provider.
func (c *Client) Validate(ctx context.Context, key string) error {
	if key == "" {
		return debrid.ErrBadKey
	}
	_, err := c.do(ctx, key, http.MethodGet, "/api/user/me", nil, nil)
	return err
}

type apiFile struct {
	ID    *int64 `json:"id"`
	Name  string `json:"name"`
	Size  int64  `json:"size"`
	Short string `json:"short_name"`
}

type cachedEntry struct {
	Hash  string    `json:"hash"`
	Files []apiFile `json:"files"`
}

func toFiles(files []apiFile) []model.File {
	out := make([]model.File, 0, len(files))
	for i, f := range files {
		idx := i
		if f.ID != nil {
			idx = int(*f.ID)
		}
		out = append(out, model.File{Index: idx, Path: f.Name, Size: f.Size})
	}
	return out
}

// CheckCached implements debrid.Provider. Hashes are lowercased, de-duplicated and sent in
// batches of at most 100.
func (c *Client) CheckCached(ctx context.Context, key string, hashes []string) (map[string]debrid.Cached, error) {
	seen := make(map[string]bool, len(hashes))
	var uniq []string
	for _, h := range hashes {
		h = strings.ToLower(strings.TrimSpace(h))
		if h != "" && !seen[h] {
			seen[h] = true
			uniq = append(uniq, h)
		}
	}
	out := make(map[string]debrid.Cached)
	for start := 0; start < len(uniq); start += checkBatchSize {
		end := min(start+checkBatchSize, len(uniq))
		body, err := json.Marshal(map[string][]string{"hashes": uniq[start:end]})
		if err != nil {
			return nil, err
		}
		q := url.Values{"format": {"list"}, "list_files": {"true"}}
		env, err := c.do(ctx, key, http.MethodPost, "/api/torrents/checkcached?"+q.Encode(), body, nil)
		if err != nil {
			return nil, err
		}
		var entries []cachedEntry
		if err := decodeData(env.Data, &entries); err != nil {
			return nil, c.errorf(key, "checkcached: %v", err)
		}
		for _, e := range entries {
			h := strings.ToLower(e.Hash)
			if h == "" || !seen[h] {
				continue
			}
			out[h] = debrid.Cached{Files: toFiles(e.Files)}
		}
	}
	return out, nil
}

type item struct {
	ID               int64     `json:"id"`
	Hash             string    `json:"hash"`
	Files            []apiFile `json:"files"`
	Active           bool      `json:"active"`
	DownloadPresent  bool      `json:"download_present"`
	DownloadFinished bool      `json:"download_finished"`
	DownloadState    string    `json:"download_state"`
	Progress         float64   `json:"progress"` // 0..1
	QueuedID         *int64    `json:"queued_id"`
}

func (it *item) ready() bool { return it.DownloadPresent || it.DownloadFinished }

func (it *item) failed() bool {
	s := strings.ToLower(it.DownloadState)
	return strings.HasPrefix(s, "error") || strings.HasPrefix(s, "failed")
}

func (it *item) percent() int {
	p := int(it.Progress*100 + 0.5)
	return max(0, min(100, p))
}

type created struct {
	TorrentID *int64 `json:"torrent_id"`
	QueuedID  *int64 `json:"queued_id"`
}

var hashRe = regexp.MustCompile(`^[0-9a-f]{40}$`)

var btihRe = regexp.MustCompile(`(?i)xt=urn:btih:([0-9a-f]{40})`)

// Resolve implements debrid.Provider. The torrent is looked up in the account by info hash,
// added if absent, and — once TorBox reports it present — a signed CDN link for the file is
// requested with redirect=false so the API key never reaches the player.
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
		return debrid.ResolveResult{}, fmt.Errorf("torbox: resolve: invalid info hash: %w", debrid.ErrNotFound)
	}
	magnet := req.Magnet
	if magnet == "" {
		magnet = "magnet:?xt=urn:btih:" + hash
	} else if m := btihRe.FindStringSubmatch(magnet); m == nil || strings.ToLower(m[1]) != hash {
		return debrid.ResolveResult{}, fmt.Errorf("torbox: resolve: magnet does not match info hash: %w", debrid.ErrNotFound)
	}

	it, err := c.findInList(ctx, key, hash, false)
	if err != nil {
		return debrid.ResolveResult{}, err
	}
	if it == nil {
		it, err = c.add(ctx, key, hash, magnet)
		if err != nil {
			return debrid.ResolveResult{}, err
		}
		if it == nil {
			return debrid.ResolveResult{Pending: true}, nil
		}
	}
	if it.failed() {
		return debrid.ResolveResult{}, fmt.Errorf("torbox: torrent state %q: %w", it.DownloadState, debrid.ErrFailed)
	}
	if !it.ready() {
		return debrid.ResolveResult{Pending: true, Progress: it.percent()}, nil
	}
	fileIndex := req.FileIndex
	if fileIndex == debrid.AutoFile {
		idx, ok := largestAudioFile(it.Files)
		if !ok {
			return debrid.ResolveResult{}, fmt.Errorf("torbox: no audio file in torrent: %w", debrid.ErrNotFound)
		}
		fileIndex = idx
	} else if len(it.Files) > 0 && !hasFile(it.Files, fileIndex) {
		return debrid.ResolveResult{}, fmt.Errorf("torbox: file %d not in torrent: %w", fileIndex, debrid.ErrNotFound)
	}
	link, err := c.requestDL(ctx, key, it.ID, fileIndex)
	if err != nil {
		return debrid.ResolveResult{}, err
	}
	return debrid.ResolveResult{URL: link}, nil
}

// largestAudioFile returns the id of the largest audio file (ids default to the position).
func largestAudioFile(files []apiFile) (int, bool) {
	best, bestSize, found := 0, int64(-1), false
	for i, f := range files {
		id := int64(i)
		if f.ID != nil {
			id = *f.ID
		}
		name := f.Name
		if name == "" {
			name = f.Short
		}
		if !model.IsAudioPath(name) || f.Size <= bestSize {
			continue
		}
		best, bestSize, found = int(id), f.Size, true
	}
	return best, found
}

func hasFile(files []apiFile, idx int) bool {
	for i, f := range files {
		id := int64(i)
		if f.ID != nil {
			id = *f.ID
		}
		if id == int64(idx) {
			return true
		}
	}
	return false
}

func (c *Client) findInList(ctx context.Context, key, hash string, bypassCache bool) (*item, error) {
	for page := range maxListPages {
		q := url.Values{
			"offset": {strconv.Itoa(page * listPageSize)},
			"limit":  {strconv.Itoa(listPageSize)},
		}
		if bypassCache {
			q.Set("bypass_cache", "true")
		}
		env, err := c.do(ctx, key, http.MethodGet, "/api/torrents/mylist?"+q.Encode(), nil, nil)
		if isItemNotFound(err) {
			break // an empty library answers 404 ITEM_NOT_FOUND ("No torrents found for this user")
		}
		if err != nil {
			return nil, err
		}
		var items []item
		if err := decodeData(env.Data, &items); err != nil {
			return nil, c.errorf(key, "mylist: %v", err)
		}
		for i := range items {
			if strings.ToLower(items[i].Hash) == hash {
				return &items[i], nil
			}
		}
		if len(items) < listPageSize {
			break
		}
	}
	return nil, nil
}

// isItemNotFound reports TorBox's "ITEM_NOT_FOUND" answer of the library listing.
func isItemNotFound(err error) bool {
	var ae *apiError
	return errors.As(err, &ae) && ae.code == "ITEM_NOT_FOUND"
}

func (c *Client) getByID(ctx context.Context, key string, id int64) (*item, error) {
	q := url.Values{"id": {strconv.FormatInt(id, 10)}, "bypass_cache": {"true"}}
	env, err := c.do(ctx, key, http.MethodGet, "/api/torrents/mylist?"+q.Encode(), nil, nil)
	if isItemNotFound(err) {
		return nil, nil // not visible (yet): callers report Pending
	}
	if err != nil {
		return nil, err
	}
	data := strings.TrimSpace(string(env.Data))
	if strings.HasPrefix(data, "[") {
		var items []item
		if err := decodeData(env.Data, &items); err != nil {
			return nil, c.errorf(key, "mylist: %v", err)
		}
		if len(items) == 0 {
			return nil, nil
		}
		return &items[0], nil
	}
	var it item
	if err := decodeData(env.Data, &it); err != nil {
		return nil, c.errorf(key, "mylist: %v", err)
	}
	if it.ID == 0 {
		return nil, nil
	}
	return &it, nil
}

// add creates the torrent. A nil item with a nil error means "accepted but not visible yet"
// (queued by TorBox, or just created); callers report Pending.
func (c *Client) add(ctx context.Context, key, hash, magnet string) (*item, error) {
	form := url.Values{"magnet": {magnet}, "allow_zip": {"false"}}
	env, err := c.do(ctx, key, http.MethodPost, "/api/torrents/createtorrent", []byte(form.Encode()),
		http.Header{"Content-Type": {"application/x-www-form-urlencoded"}})
	if err != nil {
		var ae *apiError
		if errors.As(err, &ae) && ae.alreadyThere() {
			it, lerr := c.findInList(ctx, key, hash, true)
			if lerr != nil {
				return nil, lerr
			}
			return it, nil
		}
		return nil, err
	}
	var cr created
	if err := decodeData(env.Data, &cr); err != nil {
		return nil, c.errorf(key, "createtorrent: %v", err)
	}
	if cr.TorrentID != nil && *cr.TorrentID != 0 {
		it, err := c.getByID(ctx, key, *cr.TorrentID)
		if err != nil {
			return nil, err
		}
		if it != nil && !strings.EqualFold(it.Hash, hash) {
			return nil, c.errorf(key, "createtorrent: returned torrent hash does not match the requested one")
		}
		return it, nil
	}
	if cr.QueuedID != nil && *cr.QueuedID != 0 {
		return nil, nil
	}
	return nil, c.errorf(key, "createtorrent: unexpected response data %s", truncate(string(env.Data), 200))
}

func (c *Client) requestDL(ctx context.Context, key string, torrentID int64, fileID int) (string, error) {
	q := url.Values{
		"token":      {key},
		"torrent_id": {strconv.FormatInt(torrentID, 10)},
		"file_id":    {strconv.Itoa(fileID)},
		"redirect":   {"false"},
	}
	env, err := c.do(ctx, key, http.MethodGet, "/api/torrents/requestdl?"+q.Encode(), nil, nil)
	if err != nil {
		return "", err
	}
	var link string
	if err := decodeData(env.Data, &link); err != nil || link == "" {
		return "", c.errorf(key, "requestdl: no download link in response: %s", truncate(string(env.Data), 100))
	}
	return link, nil
}

type envelope struct {
	Success *bool           `json:"success"`
	Error   *string         `json:"error"`
	Detail  json.RawMessage `json:"detail"`
	Data    json.RawMessage `json:"data"`
}

func (e *envelope) detail() string {
	var s string
	if json.Unmarshal(e.Detail, &s) == nil {
		return s
	}
	d := strings.TrimSpace(string(e.Detail))
	if d == "null" {
		return ""
	}
	return d
}

func decodeData(raw json.RawMessage, v any) error {
	if len(raw) == 0 || string(raw) == "null" {
		return nil
	}
	return json.Unmarshal(raw, v)
}

func (c *Client) base() string {
	if c.BaseURL == "" {
		return DefaultBaseURL
	}
	return strings.TrimRight(c.BaseURL, "/")
}

// do performs one request. pathQuery is "/api/...[?query]". All returned errors are redacted.
func (c *Client) do(ctx context.Context, key, method, pathQuery string, body []byte, hdr http.Header) (*envelope, error) {
	endpoint, _, _ := strings.Cut(pathQuery, "?")
	var rdr io.Reader
	if body != nil {
		rdr = strings.NewReader(string(body))
	}
	req, err := http.NewRequestWithContext(ctx, method, c.base()+pathQuery, rdr)
	if err != nil {
		return nil, c.errorf(key, "%s %s: bad request", method, endpoint)
	}
	req.Header.Set("Authorization", "Bearer "+key)
	req.Header.Set("Accept", "application/json")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	for k, v := range hdr {
		req.Header[k] = v
	}
	hc := c.HTTP
	if hc == nil {
		hc = http.DefaultClient
	}
	resp, err := hc.Do(req)
	if err != nil {
		// *url.Error embeds the full URL, which carries the key for requestdl.
		var ue *url.Error
		if errors.As(err, &ue) {
			err = ue.Err
		}
		return nil, &safeError{msg: redact(key, fmt.Sprintf("torbox: %s %s: %v", method, endpoint, err)), err: err}
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBodyBytes))
	if err != nil {
		return nil, &safeError{msg: redact(key, fmt.Sprintf("torbox: %s %s: reading body: %v", method, endpoint, err)), err: err}
	}

	var env envelope
	parsed := json.Unmarshal(raw, &env) == nil
	ok := resp.StatusCode >= 200 && resp.StatusCode < 300
	if ok && parsed && (env.Success == nil || *env.Success) {
		return &env, nil
	}
	ae := &apiError{status: resp.StatusCode, method: method, endpoint: endpoint}
	if parsed {
		if env.Error != nil {
			ae.code = *env.Error
		}
		ae.detail = env.detail()
	} else {
		ae.detail = string(raw)
	}
	ae.detail = redact(key, truncate(ae.detail, 200))
	ae.code = redact(key, ae.code)
	if ok && !parsed {
		ae.detail = "invalid JSON response: " + ae.detail
	}
	return nil, ae
}

func (c *Client) errorf(key, format string, args ...any) error {
	return &safeError{msg: redact(key, "torbox: "+fmt.Sprintf(format, args...))}
}

// redact removes the key from s, also in the percent-encoded forms an upstream may echo
// back when it reflects the request URL.
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

// apiError is a non-success TorBox answer. It is already redacted and unwraps to the
// matching debrid sentinel error, if any.
type apiError struct {
	status       int
	code, detail string
	method       string
	endpoint     string
}

var (
	badKeyCodes = map[string]bool{
		"BAD_TOKEN": true, "AUTH_ERROR": true, "NO_AUTH": true,
		"PLAN_RESTRICTED_FEATURE": true, "PLAN_ID_FEATURE": true,
	}
	limitCodes = map[string]bool{
		"MONTHLY_LIMIT": true, "COOLDOWN_LIMIT": true, "ACTIVE_LIMIT": true, "RATE_LIMIT_EXCEEDED": true,
	}
)

func (e *apiError) Error() string {
	s := fmt.Sprintf("torbox: %s %s: HTTP %d", e.method, e.endpoint, e.status)
	if e.code != "" {
		s += " " + e.code
	}
	if e.detail != "" {
		s += ": " + e.detail
	}
	return s
}

func (e *apiError) alreadyThere() bool {
	d := strings.ToLower(e.detail)
	return strings.Contains(d, "already queued") || strings.Contains(d, "already exists")
}

func (e *apiError) sentinel() error {
	d := strings.ToLower(e.detail)
	switch {
	case e.status == http.StatusUnauthorized || e.status == http.StatusForbidden || badKeyCodes[e.code]:
		return debrid.ErrBadKey
	case e.status == http.StatusTooManyRequests || limitCodes[e.code] || strings.Contains(d, "maximum queued"):
		return debrid.ErrRateLimited
	case e.code == "DOWNLOAD_TOO_LARGE":
		return debrid.ErrFailed
	case e.code != "ENDPOINT_NOT_FOUND" && (e.status == http.StatusNotFound || strings.Contains(e.code, "NOT_FOUND")):
		return debrid.ErrNotFound
	}
	return nil
}

func (e *apiError) Unwrap() error { return e.sentinel() }
