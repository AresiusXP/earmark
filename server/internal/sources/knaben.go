package sources

import (
	"bytes"
	"context"
	"encoding/base32"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

const (
	knabenDefaultURL  = "https://api.knaben.org/v1"
	knabenCategory    = 1003000 // Audio / Audiobook
	knabenPageSize    = 50
	defaultMaxBody    = 8 << 20
	defaultReqTimeout = 20 * time.Second
	defaultUserAgent  = "stremio-audiobooks/0.1 (+https://github.com/AresiusXP/stremio-audiobooks)"
)

var (
	// ErrRateLimited: the index answered 429.
	ErrRateLimited = errors.New("sources: rate limited")
	// ErrUpstream: the index answered 5xx.
	ErrUpstream = errors.New("sources: upstream failure")
	// ErrBodyTooLarge: the response exceeded the size limit.
	ErrBodyTooLarge = errors.New("sources: response too large")
)

// HTTPError is a non-2xx answer from an index; errors.Is maps it onto the sentinels above.
type HTTPError struct {
	Source     string
	Status     int
	RetryAfter time.Duration
}

func (e *HTTPError) Error() string {
	return fmt.Sprintf("sources: %s answered HTTP %d", e.Source, e.Status)
}

// Is implements errors.Is for the typed sentinels.
func (e *HTTPError) Is(target error) bool {
	switch target {
	case ErrRateLimited:
		return e.Status == http.StatusTooManyRequests
	case ErrUpstream:
		return e.Status >= 500
	}
	return false
}

// defaultTrackers are public trackers appended to magnets built from a bare hash.
var defaultTrackers = []string{
	"udp://tracker.opentrackr.org:1337/announce",
	"udp://open.stealth.si:80/announce",
	"udp://tracker.torrent.eu.org:451/announce",
	"udp://open.demonii.com:1337/announce",
	"udp://exodus.desync.com:6969/announce",
}

// Knaben searches the Knaben meta-index (audiobook category).
type Knaben struct {
	// BaseURL overrides the API endpoint (tests); empty means https://api.knaben.org/v1.
	BaseURL      string
	HTTP         *http.Client
	UserAgent    string
	MaxBodyBytes int64
	// Timeout bounds one HTTP request on top of the caller's context; 0 means 20s.
	Timeout time.Duration
}

// NewKnaben returns a Knaben source with defaults.
func NewKnaben() *Knaben { return &Knaben{HTTP: &http.Client{Timeout: 30 * time.Second}} }

// Name implements Source.
func (k *Knaben) Name() string { return "knaben" }

type knabenRequest struct {
	SearchType     string `json:"search_type"`
	SearchField    string `json:"search_field"`
	Query          string `json:"query"`
	Categories     []int  `json:"categories"`
	OrderBy        string `json:"order_by"`
	OrderDirection string `json:"order_direction"`
	Size           int    `json:"size"`
	HideUnsafe     bool   `json:"hide_unsafe"`
}

type knabenHit struct {
	Title        string  `json:"title"`
	Hash         *string `json:"hash"`
	MagnetURL    *string `json:"magnetUrl"`
	Bytes        *int64  `json:"bytes"`
	Seeders      *int    `json:"seeders"`
	Date         string  `json:"date"`
	CachedOrigin string  `json:"cachedOrigin"`
}

type knabenResponse struct {
	Hits []knabenHit `json:"hits"`
}

// Search implements Source. The query is title words plus the author surname; when that
// finds nothing the search is retried once with the title alone.
func (k *Knaben) Search(ctx context.Context, q Query) ([]model.Release, error) {
	title := words(q.Title)
	if title == "" {
		return []model.Release{}, nil
	}
	query := title
	surname := surnameOf(q.Author)
	if surname != "" {
		query = title + " " + surname
	}
	rels, err := k.query(ctx, query)
	if err != nil {
		return nil, err
	}
	if len(rels) == 0 && surname != "" {
		return k.query(ctx, title)
	}
	return rels, nil
}

func (k *Knaben) query(ctx context.Context, query string) ([]model.Release, error) {
	body, err := json.Marshal(knabenRequest{
		SearchType: "100%", SearchField: "title", Query: query,
		Categories: []int{knabenCategory}, OrderBy: "seeders", OrderDirection: "desc",
		Size: knabenPageSize, HideUnsafe: true,
	})
	if err != nil {
		return nil, err
	}
	timeout := k.Timeout
	if timeout <= 0 {
		timeout = defaultReqTimeout
	}
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	u := k.BaseURL
	if u == "" {
		u = knabenDefaultURL
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, u, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")
	ua := k.UserAgent
	if ua == "" {
		ua = defaultUserAgent
	}
	req.Header.Set("User-Agent", ua)

	hc := k.HTTP
	if hc == nil {
		hc = http.DefaultClient
	}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		he := &HTTPError{Source: "knaben", Status: resp.StatusCode}
		if n, err := strconv.Atoi(resp.Header.Get("Retry-After")); err == nil && n >= 0 {
			he.RetryAfter = time.Duration(n) * time.Second
		}
		return nil, he
	}
	limit := k.MaxBodyBytes
	if limit <= 0 {
		limit = defaultMaxBody
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(raw)) > limit {
		return nil, ErrBodyTooLarge
	}
	var kr knabenResponse
	if err := json.Unmarshal(raw, &kr); err != nil {
		return nil, fmt.Errorf("sources: decode knaben response: %w", err)
	}
	return mapKnabenHits(kr.Hits), nil
}

func mapKnabenHits(hits []knabenHit) []model.Release {
	out := make([]model.Release, 0, len(hits))
	for _, h := range hits {
		title := strings.TrimSpace(h.Title)
		if title == "" {
			continue
		}
		var hash, magnet string
		if h.MagnetURL != nil {
			magnet = strings.TrimSpace(*h.MagnetURL)
		}
		if h.Hash != nil {
			hash = normalizeHash(*h.Hash)
		}
		if hash == "" && magnet != "" {
			hash = HashFromMagnet(magnet)
		}
		if hash == "" {
			continue
		}
		// Always rebuild: never forward tracker URLs supplied by the index to the debrid service.
		magnet = BuildMagnet(hash, title)
		r := model.Release{InfoHash: hash, Title: title, Source: "knaben", Magnet: magnet}
		if h.Bytes != nil && *h.Bytes > 0 {
			r.Size = *h.Bytes
		}
		if h.Seeders != nil && *h.Seeders > 0 {
			r.Seeders = *h.Seeders
		}
		if t, err := time.Parse(time.RFC3339, h.Date); err == nil {
			r.PublishedAt = t
		}
		out = append(out, r)
	}
	return DedupeByHash(out)
}

// DedupeByHash keeps one release per info hash (the one with the most seeders; the first
// seen on a tie) and orders the result by seeders descending, then hash.
func DedupeByHash(in []model.Release) []model.Release {
	best := make(map[string]int, len(in))
	out := make([]model.Release, 0, len(in))
	for _, r := range in {
		if i, ok := best[r.InfoHash]; ok {
			if r.Seeders > out[i].Seeders {
				out[i] = r
			}
			continue
		}
		best[r.InfoHash] = len(out)
		out = append(out, r)
	}
	sort.SliceStable(out, func(i, j int) bool {
		if out[i].Seeders != out[j].Seeders {
			return out[i].Seeders > out[j].Seeders
		}
		return out[i].InfoHash < out[j].InfoHash
	})
	return out
}

// normalizeHash returns a lowercase 40-hex hash from a 40-hex or 32-char base32 string, or "".
func normalizeHash(s string) string {
	s = strings.TrimSpace(s)
	switch len(s) {
	case 40:
		if _, err := hex.DecodeString(s); err != nil {
			return ""
		}
		return strings.ToLower(s)
	case 32:
		raw, err := base32.StdEncoding.DecodeString(strings.ToUpper(s))
		if err != nil || len(raw) != 20 {
			return ""
		}
		return hex.EncodeToString(raw)
	}
	return ""
}

// HashFromMagnet extracts the lowercase hex btih from a magnet URI, or "".
func HashFromMagnet(magnet string) string {
	rest, ok := strings.CutPrefix(magnet, "magnet:?")
	if !ok {
		return ""
	}
	vals, err := url.ParseQuery(rest)
	if err != nil {
		// ParseQuery still returns the pairs it could parse.
		if vals == nil {
			return ""
		}
	}
	for _, xt := range vals["xt"] {
		if h, ok := strings.CutPrefix(strings.ToLower(xt), "urn:btih:"); ok {
			return normalizeHash(h)
		}
	}
	return ""
}

// BuildMagnet builds a magnet URI with the display name and the default public trackers.
func BuildMagnet(hash, name string) string {
	var b strings.Builder
	b.WriteString("magnet:?xt=urn:btih:")
	b.WriteString(hash)
	if name != "" {
		b.WriteString("&dn=")
		b.WriteString(url.QueryEscape(name))
	}
	for _, t := range defaultTrackers {
		b.WriteString("&tr=")
		b.WriteString(url.QueryEscape(t))
	}
	return b.String()
}

// words replaces everything but letters and digits with single spaces.
func words(s string) string {
	return strings.Join(strings.FieldsFunc(s, func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r)
	}), " ")
}

// surnameOf returns the last word of an author name ("Frank Herbert" -> "Herbert").
func surnameOf(author string) string {
	f := strings.Fields(words(author))
	if len(f) == 0 {
		return ""
	}
	return f[len(f)-1]
}
