package sources

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

const (
	// solidDefaultURL: solidtorrents.to redirects to bitsearch.to, which redirects to
	// bitsearch.eu (checked 2026-11-16). The API lives on the last one, and redirects to other
	// hosts are refused, so the canonical host is the default.
	solidDefaultURL = "https://bitsearch.eu"
	solidCacheTTL   = 6 * time.Hour
	solidCacheMax   = 512
	// solidMinRemaining: with this many requests (or fewer) left of the daily budget the source
	// stops querying until the budget resets.
	solidMinRemaining = 20
	solidDefaultLimit = 200
	solidFallbackWait = time.Hour
)

// SolidTorrents searches SolidTorrents (now bitsearch; JSON API, info hashes included). The free
// tier allows 200 requests per day, so results are cached for 6 hours and the x-ratelimit-*
// headers are honoured: at <= 20 requests left, or after an HTTP 429, the source answers
// ErrRateLimited without calling the site until the reset time (fallback: 1 hour). Cache misses
// go out one at a time; each is counted before it is sent and refused when the requests issued
// since the last reset would pass limit-20; at most 8 loads wait for their turn.
type SolidTorrents struct {
	// BaseURL overrides the site (tests); empty means https://bitsearch.eu.
	BaseURL      string
	HTTP         *http.Client // nil: a client that only follows redirects within BaseURL's host
	UserAgent    string
	MaxBodyBytes int64
	Timeout      time.Duration // per request, capped at 15s

	once     sync.Once
	now      func() time.Time
	searches *memo[[]model.Release]
	slot     chan struct{} // one network request at a time
	queue    queueBound

	mu           sync.Mutex
	blockedUntil time.Time
	limit        int       // daily request limit; 200 until a header says otherwise
	issued       int       // requests issued since the last reset (synced from the headers)
	resetAt      time.Time // when the daily budget resets; zero = unknown
}

// NewSolidTorrents returns a SolidTorrents source with defaults.
func NewSolidTorrents() *SolidTorrents { return &SolidTorrents{} }

// Name implements Source.
func (s *SolidTorrents) Name() string { return "solidtorrents" }

func (s *SolidTorrents) setup() {
	s.once.Do(func() {
		if s.now == nil {
			s.now = time.Now
		}
		s.searches = newMemo[[]model.Release](solidCacheTTL, solidCacheMax, s.now)
		s.slot = make(chan struct{}, 1)
		s.mu.Lock()
		if s.limit <= 0 {
			s.limit = solidDefaultLimit
		}
		s.mu.Unlock()
	})
}

type solidResponse struct {
	Success *bool       `json:"success"`
	Results *[]solidHit `json:"results"` // nil: key missing or null
}

type solidHit struct {
	InfoHash  string `json:"infohash"`
	Title     string `json:"title"`
	Size      int64  `json:"size"`
	Seeders   int    `json:"seeders"`
	CreatedAt string `json:"createdAt"`
}

// Search implements Source.
func (s *SolidTorrents) Search(ctx context.Context, q Query) ([]model.Release, error) {
	query, ok := jsonIndexQuery(q.Title)
	if !ok {
		return []model.Release{}, nil
	}
	s.setup()
	rels, err := s.searches.get(ctx, query, func(ctx context.Context) ([]model.Release, error) {
		return s.fetch(ctx, query)
	})
	if err != nil {
		return nil, err
	}
	return slices.Clone(rels), nil
}

// cooling returns an ErrRateLimited error while the cooldown lasts.
func (s *SolidTorrents) cooling() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if wait := s.blockedUntil.Sub(s.now()); wait > 0 {
		return &HTTPError{Source: "solidtorrents", Status: http.StatusTooManyRequests, RetryAfter: wait}
	}
	return nil
}

// reserve accounts for one request before it is issued: it refuses (ErrRateLimited) when the
// requests issued since the last reset, this one included, would exceed limit-20.
func (s *SolidTorrents) reserve() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := s.now()
	if !s.resetAt.IsZero() && !now.Before(s.resetAt) {
		s.issued, s.resetAt = 0, time.Time{}
	}
	if s.issued+1 > s.limit-solidMinRemaining {
		wait := solidFallbackWait
		if !s.resetAt.IsZero() {
			wait = s.resetAt.Sub(now)
		}
		return &HTTPError{Source: "solidtorrents", Status: http.StatusTooManyRequests, RetryAfter: wait}
	}
	if s.resetAt.IsZero() {
		s.resetAt = now.Add(24 * time.Hour) // until a header tells the real reset time
	}
	s.issued++
	return nil
}

// parseRateLimitReset accepts an RFC 3339 time, Unix seconds or milliseconds, or a number of
// seconds from now.
func parseRateLimitReset(v string, now time.Time) (time.Time, bool) {
	v = strings.TrimSpace(v)
	if v == "" {
		return time.Time{}, false
	}
	if t, err := time.Parse(time.RFC3339, v); err == nil {
		return t, true
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil || n <= 0 {
		return time.Time{}, false
	}
	switch {
	case n >= 1e12:
		return time.UnixMilli(n), true
	case n >= 1e9:
		return time.Unix(n, 0), true
	}
	return now.Add(time.Duration(n) * time.Second), true
}

// note applies the rate-limit headers (and a 429 with its Retry-After) of an answer to the
// counters and the cooldown, and returns the time the cooldown lasts until (zero when none).
// After a 429 the cooldown is the longer of Retry-After and the reset time, or 1 h when the
// answer carries neither.
func (s *SolidTorrents) note(h http.Header, throttled bool, retryAfter time.Duration) time.Time {
	now := s.now()
	reset, haveReset := parseRateLimitReset(h.Get("X-Ratelimit-Reset"), now)
	haveReset = haveReset && reset.After(now)
	limit, _ := strconv.Atoi(strings.TrimSpace(h.Get("X-Ratelimit-Limit")))
	left, err := strconv.Atoi(strings.TrimSpace(h.Get("X-Ratelimit-Remaining")))
	haveLeft := err == nil && left >= 0

	s.mu.Lock()
	defer s.mu.Unlock()
	if limit > 0 {
		s.limit = limit
	}
	if haveLeft {
		s.issued = max(0, s.limit-left)
	}
	if haveReset {
		s.resetAt = reset
	}
	if !throttled && !(haveLeft && left <= solidMinRemaining) {
		return time.Time{}
	}
	var until time.Time
	if haveReset {
		until = reset
	}
	if retryAfter > 0 {
		if t := now.Add(retryAfter); t.After(until) {
			until = t
		}
	}
	if until.IsZero() {
		until = now.Add(solidFallbackWait)
	}
	if until.After(s.blockedUntil) {
		s.blockedUntil = until
	}
	return s.blockedUntil
}

// acquire takes the single network slot. Callers beyond jsonIndexMaxQueue waiting fail fast.
func (s *SolidTorrents) acquire(ctx context.Context) error {
	select {
	case s.slot <- struct{}{}:
		return nil
	default:
	}
	if !s.queue.enter() {
		return errQueueFull("solidtorrents")
	}
	defer s.queue.leave()
	select {
	case s.slot <- struct{}{}:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

func (s *SolidTorrents) fetch(ctx context.Context, query string) ([]model.Release, error) {
	if err := s.cooling(); err != nil {
		return nil, err
	}
	if err := s.acquire(ctx); err != nil {
		return nil, err
	}
	defer func() { <-s.slot }()
	// Callers that queued behind a request that hit a 429 or the low-budget mark stop here.
	if err := s.cooling(); err != nil {
		return nil, err
	}
	if err := s.reserve(); err != nil {
		return nil, err
	}
	base := strings.TrimRight(s.BaseURL, "/")
	if base == "" {
		base = solidDefaultURL
	}
	hc := s.HTTP
	if hc == nil {
		hc = hostClient("solidtorrents", base)
	}
	u := fmt.Sprintf("%s/api/v1/search?q=%s&sort=seeders", base, url.QueryEscape(query))
	raw, hdr, err := jsonIndexGet(ctx, hc, "solidtorrents", s.UserAgent, u, s.Timeout, s.MaxBodyBytes)
	if err != nil {
		var he *HTTPError
		if errors.As(err, &he) && he.Status == http.StatusTooManyRequests {
			until := s.note(hdr, true, he.RetryAfter)
			he.RetryAfter = max(0, until.Sub(s.now()))
		}
		return nil, err
	}
	s.note(hdr, false, 0)
	var resp solidResponse
	if err := json.Unmarshal(raw, &resp); err != nil {
		return nil, fmt.Errorf("%w: solidtorrents: %v", ErrInvalidResponse, err)
	}
	if resp.Success != nil && !*resp.Success {
		return nil, fmt.Errorf("%w: solidtorrents: success=false", ErrUpstream)
	}
	if resp.Success == nil || resp.Results == nil {
		return nil, fmt.Errorf("%w: solidtorrents: no success flag or results array", ErrInvalidResponse)
	}
	out := make([]model.Release, 0, len(*resp.Results))
	for _, h := range *resp.Results {
		var published time.Time
		if t, err := time.Parse(time.RFC3339, h.CreatedAt); err == nil {
			published = t.UTC()
		}
		if r, ok := jsonIndexRelease("solidtorrents", h.InfoHash, h.Title, h.Size, h.Seeders, published); ok {
			out = append(out, r)
		}
	}
	return capReleases(DedupeByHash(out)), nil
}
