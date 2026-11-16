package sources

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

const (
	torrentsCSVDefaultURL = "https://torrents-csv.com"
	torrentsCSVPageSize   = 50
	torrentsCSVRate       = 1.0 // requests per second
	torrentsCSVBurst      = 2
	torrentsCSVCacheTTL   = 30 * time.Minute
	torrentsCSVCacheMax   = 256
)

// TorrentsCSV searches torrents-csv.com (JSON API, info hashes included). The query is the
// title only: the index is a full-text AND search on the torrent name and many audiobook names
// lack the author. Ebook/video results and anything under 20 MB are dropped here.
type TorrentsCSV struct {
	// BaseURL overrides the site (tests); empty means https://torrents-csv.com.
	BaseURL      string
	HTTP         *http.Client // nil: a client that only follows redirects within BaseURL's host
	UserAgent    string
	MaxBodyBytes int64
	Timeout      time.Duration // per request, capped at 15s

	once     sync.Once
	now      func() time.Time
	lim      *rateLimiter
	searches *memo[[]model.Release]
	queue    queueBound
}

// NewTorrentsCSV returns a TorrentsCSV source with defaults.
func NewTorrentsCSV() *TorrentsCSV { return &TorrentsCSV{} }

// Name implements Source.
func (t *TorrentsCSV) Name() string { return "torrents-csv" }

func (t *TorrentsCSV) setup() {
	t.once.Do(func() {
		if t.now == nil {
			t.now = time.Now
		}
		t.lim = newRateLimiter(torrentsCSVRate, torrentsCSVBurst, t.now)
		t.searches = newMemo[[]model.Release](torrentsCSVCacheTTL, torrentsCSVCacheMax, t.now)
	})
}

type torrentsCSVResponse struct {
	Torrents *[]torrentsCSVHit `json:"torrents"` // nil: key missing or null
}

type torrentsCSVHit struct {
	InfoHash    string `json:"infohash"`
	Name        string `json:"name"`
	SizeBytes   int64  `json:"size_bytes"`
	CreatedUnix int64  `json:"created_unix"`
	Seeders     int    `json:"seeders"`
}

// Search implements Source.
func (t *TorrentsCSV) Search(ctx context.Context, q Query) ([]model.Release, error) {
	query, ok := jsonIndexQuery(q.Title)
	if !ok {
		return []model.Release{}, nil
	}
	t.setup()
	rels, err := t.searches.get(ctx, query, func(ctx context.Context) ([]model.Release, error) {
		return t.fetch(ctx, query)
	})
	if err != nil {
		return nil, err
	}
	return slices.Clone(rels), nil
}

func (t *TorrentsCSV) fetch(ctx context.Context, query string) ([]model.Release, error) {
	if !t.queue.enter() {
		return nil, errQueueFull("torrents-csv")
	}
	wctx, cancel := context.WithTimeout(ctx, jsonIndexTimeout)
	err := t.lim.wait(wctx)
	cancel()
	t.queue.leave()
	if err != nil {
		return nil, err
	}
	base := strings.TrimRight(t.BaseURL, "/")
	if base == "" {
		base = torrentsCSVDefaultURL
	}
	hc := t.HTTP
	if hc == nil {
		hc = hostClient("torrents-csv", base)
	}
	u := fmt.Sprintf("%s/service/search?q=%s&size=%d", base, url.QueryEscape(query), torrentsCSVPageSize)
	raw, _, err := jsonIndexGet(ctx, hc, "torrents-csv", t.UserAgent, u, t.Timeout, t.MaxBodyBytes)
	if err != nil {
		return nil, err
	}
	var resp torrentsCSVResponse
	if err := json.Unmarshal(raw, &resp); err != nil {
		return nil, fmt.Errorf("%w: torrents-csv: %v", ErrInvalidResponse, err)
	}
	if resp.Torrents == nil {
		return nil, fmt.Errorf("%w: torrents-csv: no torrents array", ErrInvalidResponse)
	}
	out := make([]model.Release, 0, len(*resp.Torrents))
	for _, h := range *resp.Torrents {
		var published time.Time
		if h.CreatedUnix > 0 {
			published = time.Unix(h.CreatedUnix, 0).UTC()
		}
		if r, ok := jsonIndexRelease("torrents-csv", h.InfoHash, h.Name, h.SizeBytes, h.Seeders, published); ok {
			out = append(out, r)
		}
	}
	return capReleases(DedupeByHash(out)), nil
}
