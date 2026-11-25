package sources

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/AresiusXP/earmark/server/internal/model"
)

const (
	jsonIndexTimeout  = 15 * time.Second
	jsonIndexMaxBody  = 2 << 20
	jsonIndexMinSize  = 20 << 20 // anything smaller is not an audiobook
	jsonIndexMinQuery = 2        // runes
	jsonIndexMaxKept  = 200      // releases kept per cached search (highest seeders first)
	jsonIndexMaxQueue = 8        // loads waiting for the network slot / rate limiter
)

// ErrInvalidResponse: the index answered 2xx but the body was not the expected JSON.
var ErrInvalidResponse = errors.New("sources: invalid response")

// jsonIndexQuery normalises a title for a full-text index search: lowercase letters, digits and
// single spaces. ok is false when too little is left to search for.
func jsonIndexQuery(title string) (string, bool) {
	q := abbWords(title)
	return q, utf8.RuneCountInString(q) >= jsonIndexMinQuery
}

// noiseRe matches words that mark a result as an ebook or a video rather than an audiobook.
var noiseRe = regexp.MustCompile(`(?i)(?:^|[^a-z0-9])(?:epub|mobi|azw3|pdf|cbz|cbr|mp4|mkv|avi|iso|1080p|720p|2160p|x264|x265|hevc|bluray|webrip|web[-. ]?dl|hdtv|s\d{1,2}e\d{1,3})(?:[^a-z0-9]|$)`)

// looksLikeNoise reports whether a result is certainly not an audiobook: its name says ebook or
// video, or it is under 20 MB. An unknown size (<= 0) is not held against it.
func looksLikeNoise(title string, size int64) bool {
	if size > 0 && size < jsonIndexMinSize {
		return true
	}
	return noiseRe.MatchString(title)
}

// jsonIndexRelease builds a Release, or ok=false when the hash is invalid or the result is noise.
func jsonIndexRelease(source, hash, title string, size int64, seeders int, published time.Time) (model.Release, bool) {
	title = strings.TrimSpace(title)
	hash = strings.TrimSpace(hash)
	if title == "" || len(hash) != 40 {
		return model.Release{}, false
	}
	hash = normalizeHash(hash)
	if hash == "" || looksLikeNoise(title, size) {
		return model.Release{}, false
	}
	r := model.Release{InfoHash: hash, Title: title, Source: source, Magnet: BuildMagnet(hash, title), PublishedAt: published}
	if size > 0 {
		r.Size = size
	}
	if seeders > 0 {
		r.Seeders = seeders
	}
	return r, true
}

// hostClient follows redirects only within the given base URLs' hosts (at most 5 hops), so an
// index cannot bounce the server's request to an internal address.
func hostClient(source string, baseURLs ...string) *http.Client {
	hosts := make(map[string]bool, len(baseURLs))
	for _, b := range baseURLs {
		if u, err := url.Parse(strings.TrimSpace(b)); err == nil && u.Host != "" {
			hosts[strings.ToLower(u.Host)] = true
		}
	}
	return &http.Client{CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 5 {
			return fmt.Errorf("%s: too many redirects", source)
		}
		if (req.URL.Scheme != "http" && req.URL.Scheme != "https") || !hosts[strings.ToLower(req.URL.Host)] {
			return fmt.Errorf("%s: redirect to a foreign host", source)
		}
		if len(via) > 0 && via[0].URL.Scheme == "https" && req.URL.Scheme != "https" {
			return fmt.Errorf("%s: redirect downgrades https to http", source)
		}
		return nil
	}}
}

// jsonIndexGet fetches one URL with the common limits. The header of the answer is returned
// whenever there was one (also with an HTTPError), so callers can read rate-limit headers.
func jsonIndexGet(ctx context.Context, hc *http.Client, source, ua, u string, timeout time.Duration, maxBody int64) ([]byte, http.Header, error) {
	if timeout <= 0 || timeout > jsonIndexTimeout {
		timeout = jsonIndexTimeout
	}
	if maxBody <= 0 {
		maxBody = jsonIndexMaxBody
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, nil, err
	}
	if ua == "" {
		ua = defaultUserAgent
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "application/json")
	resp, err := hc.Do(req)
	if err != nil {
		return nil, nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		he := &HTTPError{Source: source, Status: resp.StatusCode}
		if n, err := strconv.Atoi(resp.Header.Get("Retry-After")); err == nil && n >= 0 {
			he.RetryAfter = time.Duration(n) * time.Second
		}
		return nil, resp.Header, he
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxBody+1))
	if err != nil {
		return nil, resp.Header, err
	}
	if int64(len(raw)) > maxBody {
		return nil, resp.Header, ErrBodyTooLarge
	}
	return raw, resp.Header, nil
}

// capReleases keeps the first jsonIndexMaxKept releases (DedupeByHash orders by seeders), so a
// cached search has a predictable size.
func capReleases(rels []model.Release) []model.Release {
	if len(rels) > jsonIndexMaxKept {
		rels = rels[:jsonIndexMaxKept:jsonIndexMaxKept]
	}
	return rels
}

// queueBound limits how many loads may wait for a rate limiter or a network slot.
type queueBound struct{ n atomic.Int32 }

// enter reserves a place in the queue; false means the queue is full.
func (q *queueBound) enter() bool {
	if q.n.Add(1) > jsonIndexMaxQueue {
		q.n.Add(-1)
		return false
	}
	return true
}

func (q *queueBound) leave() { q.n.Add(-1) }

// errQueueFull is what a load gets when too many others are already waiting.
func errQueueFull(source string) error {
	return &HTTPError{Source: source, Status: http.StatusTooManyRequests, RetryAfter: time.Second}
}
