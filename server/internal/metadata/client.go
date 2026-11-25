// Package metadata resolves audiobook metadata (Audible catalog + Audnexus).
//
// Findings from live checks (2026-11-16) that shape this package:
//   - Audible has no "nl" marketplace (api.audible.nl does not resolve). The Dutch catalogue
//     (small: self-publishers, Bookora, Storytel-like imprints) is reachable from every
//     marketplace, mostly com.
//   - The catalog API ignores any `language` parameter. What works: Dutch keywords plus
//     content_type=Product (excludes podcasts) and a client-side filter on the returned
//     `language` field ("dutch" -> "nl"). Dutch bestsellers are discovered by seeding the
//     keyword (see languageSeeds).
//   - Audnexus only knows au,ca,de,es,fr,in,it,jp,us,uk, and a book usually exists in a
//     single region; Dutch books found on com resolve with region=us.
package metadata

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	defaultAudibleBase  = "https://api.audible.%s" // %s = tld
	defaultAudnexusBase = "https://api.audnex.us"
	defaultUserAgent    = "earmark/0.1 (+https://github.com/AresiusXP/earmark)"
	defaultMaxBody      = 4 << 20
	defaultTimeout      = 15 * time.Second
	maxResults          = 50 // Audible rejects more than 50 per page
)

var (
	// ErrNotFound: the upstream says the item does not exist (404).
	ErrNotFound = errors.New("metadata: not found")
	// ErrRateLimited: upstream answered 429.
	ErrRateLimited = errors.New("metadata: rate limited")
	// ErrUpstream: upstream answered 5xx.
	ErrUpstream = errors.New("metadata: upstream failure")
	// ErrInvalidASIN: the ASIN does not match ^[A-Z0-9]{10}$.
	ErrInvalidASIN = errors.New("metadata: invalid ASIN")
	// ErrUnknownRegion: the region has no marketplace mapping.
	ErrUnknownRegion = errors.New("metadata: unknown region")
	// ErrBodyTooLarge: the response exceeded the size limit.
	ErrBodyTooLarge = errors.New("metadata: response too large")
)

var asinRe = regexp.MustCompile(`^[A-Z0-9]{10}$`)

// ValidASIN reports whether s has the shape of an ASIN.
func ValidASIN(s string) bool { return asinRe.MatchString(s) }

// HTTPError is a non-2xx answer. errors.Is maps it onto the sentinel errors above.
type HTTPError struct {
	Service    string // "audible" or "audnexus"
	Status     int
	RetryAfter time.Duration // from Retry-After when present
}

func (e *HTTPError) Error() string {
	return fmt.Sprintf("metadata: %s answered HTTP %d", e.Service, e.Status)
}

// Is implements errors.Is for the typed sentinels.
func (e *HTTPError) Is(target error) bool {
	switch target {
	case ErrNotFound:
		return e.Status == http.StatusNotFound
	case ErrRateLimited:
		return e.Status == http.StatusTooManyRequests
	case ErrUpstream:
		return e.Status >= 500
	}
	return false
}

// marketplaces maps our region codes to Audible tlds. "nl" is mapped by MarketplaceRegion.
var marketplaces = map[string]string{
	"us": "com", "uk": "co.uk", "de": "de", "fr": "fr",
	"it": "it", "es": "es", "ca": "ca", "au": "com.au", "in": "in",
}

// MarketplaceRegion resolves a user-facing region to a real Audible/Audnexus region.
// Audible has no Dutch marketplace, so "nl" resolves to "us" (where Dutch titles are
// searchable and Audnexus finds them).
func MarketplaceRegion(region string) (string, error) {
	r := strings.ToLower(strings.TrimSpace(region))
	if r == "nl" {
		r = "us"
	}
	if _, ok := marketplaces[r]; !ok {
		return "", fmt.Errorf("%w: %q", ErrUnknownRegion, region)
	}
	return r, nil
}

// languageSeeds are keywords used by Discover to bias a listing towards a language that the
// catalog API cannot filter on.
var languageSeeds = map[string]string{"nl": "verhaal"}

// Chapter is one chapter marker from Audnexus.
type Chapter struct {
	Title    string
	StartMs  int
	LengthMs int
}

// Client talks to the Audible catalog API and Audnexus. The zero value is usable.
type Client struct {
	// AudibleBaseURL overrides the Audible host for every region (tests). When empty,
	// https://api.audible.<tld> is used.
	AudibleBaseURL string
	// AudnexusBaseURL overrides the Audnexus host (tests).
	AudnexusBaseURL string
	HTTP            *http.Client
	UserAgent       string
	// MaxBodyBytes caps a response body; 0 means 4 MiB.
	MaxBodyBytes int64
	// Timeout bounds one request on top of the caller's context; 0 means 15s.
	Timeout time.Duration
	// Now overrides the clock of the chapter cache (tests).
	Now func() time.Time

	chapters chapterCache
}

func (c *Client) clock() time.Time {
	if c.Now != nil {
		return c.Now()
	}
	return time.Now()
}

// New returns a Client with sane defaults.
func New() *Client {
	return &Client{HTTP: &http.Client{Timeout: 20 * time.Second}}
}

func (c *Client) audibleURL(region string) (string, string, error) {
	r, err := MarketplaceRegion(region)
	if err != nil {
		return "", "", err
	}
	if c.AudibleBaseURL != "" {
		return strings.TrimRight(c.AudibleBaseURL, "/"), r, nil
	}
	return fmt.Sprintf(defaultAudibleBase, marketplaces[r]), r, nil
}

func (c *Client) audnexusURL() string {
	if c.AudnexusBaseURL != "" {
		return strings.TrimRight(c.AudnexusBaseURL, "/")
	}
	return defaultAudnexusBase
}

// getJSON GETs u and decodes the JSON body into out. 404/429/5xx become *HTTPError.
func (c *Client) getJSON(ctx context.Context, service, u string, out any) error {
	timeout := c.Timeout
	if timeout <= 0 {
		timeout = defaultTimeout
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return err
	}
	ua := c.UserAgent
	if ua == "" {
		ua = defaultUserAgent
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "application/json")

	hc := c.HTTP
	if hc == nil {
		hc = http.DefaultClient
	}
	resp, err := hc.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		he := &HTTPError{Service: service, Status: resp.StatusCode}
		if s := resp.Header.Get("Retry-After"); s != "" {
			if n, err := strconv.Atoi(s); err == nil && n >= 0 {
				he.RetryAfter = time.Duration(n) * time.Second
			}
		}
		return he
	}

	limit := c.MaxBodyBytes
	if limit <= 0 {
		limit = defaultMaxBody
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return err
	}
	if int64(len(body)) > limit {
		return ErrBodyTooLarge
	}
	if err := json.Unmarshal(body, out); err != nil {
		return fmt.Errorf("metadata: decode %s response: %w", service, err)
	}
	return nil
}
