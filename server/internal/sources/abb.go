package sources

import (
	"context"
	"errors"
	"fmt"
	"html"
	"io"
	"math"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/AresiusXP/earmark/server/internal/model"
)

const (
	abbDefaultMaxResults = 10
	abbDefaultTimeout    = 12 * time.Second
	abbDefaultBudget     = 15 * time.Second
	abbDefaultParallel   = 4
	abbDefaultRate       = 2.0 // requests per second, shared by every ABB request
	abbDefaultBurst      = 4
	abbDefaultCacheTTL   = 30 * time.Minute
	abbCacheMax          = 512
	abbMaxBody           = 2 << 20
	abbMinCards          = 3 // fewer search hits than this triggers the title+surname retry
	abbChromeUA          = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
)

// DefaultABBBaseURLs are the AudioBook Bay mirrors tried in order (domains churn).
var DefaultABBBaseURLs = []string{"https://audiobookbay.lu", "https://audiobookbay.is", "https://audiobookbay.fi"}

// ABB scrapes AudioBook Bay. The search page lists post cards (title, format, size...) but the
// info hash only exists on each post's detail page, so a search costs one request for the
// listing plus up to MaxResults detail requests (4 at a time).
//
// ABB shows no seeder counts: Release.Seeders is always 0 (the service ranks cached torrents
// first anyway). Titles on ABB are sometimes deliberately scrambled by a letter, so results are
// never filtered by title here: the matcher decides.
//
// Zero values of the tunables mean "default". They must be set before the first Search.
type ABB struct {
	// BaseURLs is the ordered mirror list (no trailing slash needed). The mirror that last
	// answered is tried first; the next one is used on network errors, 5xx, 403 and Cloudflare
	// challenge pages.
	BaseURLs     []string
	HTTP         *http.Client
	UserAgent    string // empty: a Chrome user agent (ABB serves bots nothing useful)
	MaxBodyBytes int64
	// MaxResults caps the post cards (and so detail fetches) per search; 0 means 10.
	MaxResults int
	// Timeout bounds one HTTP request on top of the caller's context; 0 means 12s. It never
	// exceeds Budget.
	Timeout time.Duration
	// Budget is the hard deadline of one whole Search call (search pages, detail fetches and
	// mirror failover together); 0 means 15s. When it runs out the releases already resolved
	// are returned.
	Budget time.Duration
	// Parallel is the number of concurrent detail fetches; 0 means 4.
	Parallel int
	// Rate and Burst form a global token bucket over all requests (req/s); Rate 0 means 2/s
	// with a burst of 4, Rate < 0 disables limiting.
	Rate  float64
	Burst int
	// CacheTTL keeps search results and detail pages in memory (max 512 each); 0 means 30 min,
	// < 0 disables the cache.
	CacheTTL time.Duration

	once     sync.Once
	good     atomic.Int32
	lim      *rateLimiter
	searches *memo[[]abbCard]
	details  *memo[abbDetail]
	now      func() time.Time
}

// NewABB returns an ABB source over the given mirrors (nil means DefaultABBBaseURLs).
func NewABB(baseURLs []string) *ABB {
	if len(baseURLs) == 0 {
		baseURLs = DefaultABBBaseURLs
	}
	return &ABB{BaseURLs: baseURLs, HTTP: mirrorClient(baseURLs)}
}

// Name implements Source.
func (a *ABB) Name() string { return "abb" }

func (a *ABB) setup() {
	a.once.Do(func() {
		if a.now == nil {
			a.now = time.Now
		}
		rate, burst := a.Rate, a.Burst
		if rate == 0 {
			rate = abbDefaultRate
		}
		if burst <= 0 {
			burst = abbDefaultBurst
		}
		if rate > 0 {
			a.lim = newRateLimiter(rate, burst, a.now)
		}
		ttl := a.CacheTTL
		if ttl == 0 {
			ttl = abbDefaultCacheTTL
		}
		a.searches = newMemo[[]abbCard](ttl, abbCacheMax, a.now)
		a.details = newMemo[abbDetail](ttl, abbCacheMax, a.now)
	})
}

// abbCard is one post of a search page.
type abbCard struct {
	Path     string // "/abss/<slug>/"
	Title    string
	Language string
	Format   string
	Bitrate  string
	Size     int64
	Posted   time.Time
}

// abbDetail is what a detail page contributes.
type abbDetail struct {
	Hash string // lowercase 40-hex, "" when the page has none
	Size int64
}

// Search implements Source. The site query is the title words; when that finds fewer than 3
// posts it is repeated with the author surname added and the lists merged. If the overall
// budget (Budget, or ctx) runs out while details are loading, the releases already resolved
// are returned.
func (a *ABB) Search(ctx context.Context, q Query) ([]model.Release, error) {
	a.setup()
	title := abbWords(q.Title)
	if title == "" {
		return []model.Release{}, nil
	}
	ctx, cancel := context.WithTimeout(ctx, a.budget())
	defer cancel()
	cards, err := a.searchCards(ctx, title)
	if err != nil {
		return nil, err
	}
	if surname := surnameOf(abbStripQuotes(q.Author)); len(cards) < abbMinCards && surname != "" &&
		!containsWord(title, surname) {
		more, err := a.searchCards(ctx, title+" "+surname)
		switch {
		case err == nil:
			cards = mergeCards(cards, more)
		case len(cards) == 0:
			return nil, err
		}
	}
	max := a.MaxResults
	if max <= 0 {
		max = abbDefaultMaxResults
	}
	if len(cards) > max {
		cards = cards[:max]
	}
	return a.resolve(ctx, cards)
}

// abbStripQuotes removes apostrophes and quotes (so "Carl's" becomes "Carls", not "Carl s").
func abbStripQuotes(s string) string {
	return strings.Map(func(r rune) rune {
		switch r {
		case '\'', '’', '‘', '`', '"', '“', '”':
			return -1
		}
		return r
	}, s)
}

// abbWords is the site query text: lowercase letters, digits and single spaces only. ABB's
// search stalls or redirects on apostrophes and other punctuation.
func abbWords(s string) string {
	return strings.ToLower(words(abbStripQuotes(s)))
}

func (a *ABB) budget() time.Duration {
	if a.Budget > 0 {
		return a.Budget
	}
	return abbDefaultBudget
}

// requestTimeout is the per-request bound, never longer than the budget.
func (a *ABB) requestTimeout() time.Duration {
	t := a.Timeout
	if t <= 0 {
		t = abbDefaultTimeout
	}
	return min(t, a.budget())
}

func containsWord(s, w string) bool {
	for _, f := range strings.Fields(strings.ToLower(s)) {
		if f == strings.ToLower(w) {
			return true
		}
	}
	return false
}

func mergeCards(a, b []abbCard) []abbCard {
	seen := make(map[string]bool, len(a)+len(b))
	out := make([]abbCard, 0, len(a)+len(b))
	for _, c := range append(append([]abbCard{}, a...), b...) {
		if !seen[c.Path] {
			seen[c.Path] = true
			out = append(out, c)
		}
	}
	return out
}

// searchCards runs one site search. The query is lowercased: ABB answers a query with capital
// letters by redirecting to the front page (live, 2026-11).
func (a *ABB) searchCards(ctx context.Context, query string) ([]abbCard, error) {
	query = strings.ToLower(query)
	return a.searches.get(ctx, query, func(ctx context.Context) ([]abbCard, error) {
		body, err := a.fetch(ctx, "/?s="+url.QueryEscape(query))
		if err != nil {
			return nil, err
		}
		return parseABBSearch(body), nil
	})
}

func (a *ABB) detail(ctx context.Context, path string) (abbDetail, error) {
	return a.details.get(ctx, path, func(ctx context.Context) (abbDetail, error) {
		body, err := a.fetch(ctx, path)
		if err != nil {
			return abbDetail{}, err
		}
		return parseABBDetail(body), nil
	})
}

func (a *ABB) resolve(ctx context.Context, cards []abbCard) ([]model.Release, error) {
	if len(cards) == 0 {
		return []model.Release{}, nil
	}
	par := a.Parallel
	if par <= 0 {
		par = abbDefaultParallel
	}
	type slot struct {
		d   abbDetail
		err error
	}
	slots := make([]slot, len(cards))
	sem := make(chan struct{}, par)
	var wg sync.WaitGroup
	for i, c := range cards {
		select {
		case sem <- struct{}{}:
		case <-ctx.Done():
			slots[i].err = ctx.Err()
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			slots[i].d, slots[i].err = a.detail(ctx, c.Path)
		}()
	}
	wg.Wait()

	out := make([]model.Release, 0, len(cards))
	seen := make(map[string]bool, len(cards))
	var firstErr error
	for i, c := range cards {
		s := slots[i]
		if s.err != nil {
			if firstErr == nil {
				firstErr = s.err
			}
			continue
		}
		if s.d.Hash == "" || seen[s.d.Hash] {
			continue
		}
		seen[s.d.Hash] = true
		out = append(out, abbRelease(c, s.d))
	}
	if len(out) == 0 && firstErr != nil {
		return nil, firstErr
	}
	return out, nil
}

func abbRelease(c abbCard, d abbDetail) model.Release {
	title := c.Title
	if tag := c.formatTag(); tag != "" {
		title += " [" + tag + "]"
	}
	if c.Language != "" {
		title += " [" + c.Language + "]"
	}
	size := c.Size
	if size == 0 {
		size = d.Size
	}
	return model.Release{
		InfoHash:    d.Hash,
		Title:       title,
		Source:      "abb",
		Magnet:      BuildMagnet(d.Hash, c.Title),
		Size:        size,
		PublishedAt: c.Posted,
	}
}

var abbBitrateRe = regexp.MustCompile(`(?i)^(\d+)\s*kbps$`)

// formatTag is "M4B" or "MP3 64 Kbps"; ABB's "Variable"/"Mixed" bitrates carry no number and
// are left out. Empty when the card shows no format.
func (c abbCard) formatTag() string {
	if c.Format == "" {
		return ""
	}
	tag := strings.ToUpper(c.Format)
	if m := abbBitrateRe.FindStringSubmatch(c.Bitrate); m != nil {
		tag += " " + m[1] + " Kbps"
	}
	return tag
}

// ---- HTTP ----

var errABBChallenge = fmt.Errorf("%w: cloudflare challenge", ErrUpstream)

// fetch GETs a path (with query) from the mirrors, last good one first, and returns the body.
// The whole failover chain shares one budget: trying more mirrors never extends it (the
// memoised loads run detached from the Search deadline, so it is enforced here too).
func (a *ABB) fetch(ctx context.Context, pathQuery string) ([]byte, error) {
	n := len(a.BaseURLs)
	if n == 0 {
		return nil, errors.New("sources: abb has no base URLs")
	}
	ctx, cancel := context.WithTimeout(ctx, a.budget())
	defer cancel()
	first := int(a.good.Load())
	if first < 0 || first >= n {
		first = 0
	}
	var errs []error
	for k := 0; k < n; k++ {
		idx := (first + k) % n
		if k > 0 {
			// Keep the configured order after the remembered mirror.
			idx = k - 1
			if idx >= first {
				idx++
			}
		}
		base := strings.TrimRight(a.BaseURLs[idx], "/")
		body, err := a.fetchOne(ctx, base+pathQuery)
		if err == nil {
			a.good.Store(int32(idx))
			return body, nil
		}
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		errs = append(errs, fmt.Errorf("%s: %w", base, err))
		if !abbFailover(err) {
			break
		}
	}
	return nil, errors.Join(errs...)
}

// abbFailover reports whether another mirror is worth trying.
func abbFailover(err error) bool {
	var he *HTTPError
	if errors.As(err, &he) {
		return he.Status == http.StatusForbidden || he.Status >= 500
	}
	// 429 and 404 are the site's answer, not a mirror problem.
	return true
}

func (a *ABB) fetchOne(ctx context.Context, u string) ([]byte, error) {
	if a.lim != nil {
		if err := a.lim.wait(ctx); err != nil {
			return nil, err
		}
	}
	ctx, cancel := context.WithTimeout(ctx, a.requestTimeout())
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return nil, err
	}
	ua := a.UserAgent
	if ua == "" {
		ua = abbChromeUA
	}
	req.Header.Set("User-Agent", ua)
	req.Header.Set("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	hc := a.HTTP
	if hc == nil {
		hc = mirrorClient(a.BaseURLs)
	}
	resp, err := hc.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 64<<10))
		if (resp.StatusCode == http.StatusForbidden || resp.StatusCode == http.StatusServiceUnavailable) &&
			strings.EqualFold(resp.Header.Get("Server"), "cloudflare") {
			return nil, errABBChallenge
		}
		he := &HTTPError{Source: "abb", Status: resp.StatusCode}
		if n, err := strconv.Atoi(resp.Header.Get("Retry-After")); err == nil && n >= 0 {
			he.RetryAfter = time.Duration(n) * time.Second
		}
		return nil, he
	}
	limit := a.MaxBodyBytes
	if limit <= 0 {
		limit = abbMaxBody
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(raw)) > limit {
		return nil, ErrBodyTooLarge
	}
	if isABBChallenge(raw) {
		return nil, errABBChallenge
	}
	return raw, nil
}

// isABBChallenge detects a Cloudflare interstitial served with 200.
func isABBChallenge(body []byte) bool {
	s := string(body)
	if strings.Contains(s, "cf-chl") {
		return true
	}
	return strings.Contains(s, "Just a moment") &&
		!strings.Contains(s, `class="postTitle"`) && !strings.Contains(s, "Info Hash:")
}

// ---- parsing ----

var (
	abbTitleRe    = regexp.MustCompile(`(?is)<h2>\s*<a\s+href=["']([^"']+)["'][^>]*>(.*?)</a>`)
	abbLangRe     = regexp.MustCompile(`Language:\s*([^<]+)`)
	abbPostedRe   = regexp.MustCompile(`Posted:\s*(\d{1,2}\s+[A-Za-z]{3}\s+\d{4})`)
	abbFormatRe   = regexp.MustCompile(`(?s)Format:\s*<span[^>]*>(.*?)</span>`)
	abbBitrateTag = regexp.MustCompile(`(?s)Bitrate:\s*<span[^>]*>(.*?)</span>`)
	abbSizeRe     = regexp.MustCompile(`(?s)File Size:\s*<span[^>]*>\s*([\d.,]+)\s*</span>\s*([KMGT]?B)`)
	abbHashRe     = regexp.MustCompile(`(?is)<td[^>]*>\s*Info Hash:?\s*</td>\s*<td[^>]*>\s*([^<]*?)\s*</td>`)
	abbCombinedRe = regexp.MustCompile(`(?is)<td[^>]*>\s*Combined File Size:?\s*</td>\s*<td[^>]*>\s*<span[^>]*>\s*([\d.,]+)\s*</span>\s*([KMGT]?B)`)
	abbTagRe      = regexp.MustCompile(`<[^>]*>`)
	abbSpaceRe    = regexp.MustCompile(`\s+`)
)

const abbPostMarker = `<div class="post">`

// parseABBSearch extracts the post cards of a search page, in page order.
func parseABBSearch(body []byte) []abbCard {
	s := string(body)
	cards := []abbCard{}
	for {
		i := strings.Index(s, abbPostMarker)
		if i < 0 {
			break
		}
		s = s[i+len(abbPostMarker):]
		block := s
		if j := strings.Index(s, abbPostMarker); j >= 0 {
			block = s[:j]
		}
		if c, ok := parseABBCard(block); ok {
			cards = append(cards, c)
		}
	}
	return cards
}

func parseABBCard(block string) (abbCard, bool) {
	m := abbTitleRe.FindStringSubmatch(block)
	if m == nil {
		return abbCard{}, false
	}
	path := abbPostPath(html.UnescapeString(m[1]))
	title := cleanText(m[2])
	if path == "" || title == "" {
		return abbCard{}, false
	}
	c := abbCard{Path: path, Title: title}
	if m := abbLangRe.FindStringSubmatch(block); m != nil {
		c.Language = cleanText(m[1])
	}
	if m := abbFormatRe.FindStringSubmatch(block); m != nil {
		c.Format = cleanText(m[1])
	}
	if m := abbBitrateTag.FindStringSubmatch(block); m != nil {
		c.Bitrate = cleanText(m[1])
	}
	if m := abbPostedRe.FindStringSubmatch(block); m != nil {
		if t, err := time.Parse("2 Jan 2006", abbSpaceRe.ReplaceAllString(m[1], " ")); err == nil {
			c.Posted = t
		}
	}
	if m := abbSizeRe.FindStringSubmatch(block); m != nil {
		c.Size = parseABBSize(m[1], m[2])
	}
	return c, true
}

// abbPostPath turns a card href (relative or absolute) into "/abss/<slug>/", or "".
func abbPostPath(href string) string {
	u, err := url.Parse(strings.TrimSpace(href))
	if err != nil || !strings.HasPrefix(u.Path, "/abss/") {
		return ""
	}
	return u.EscapedPath()
}

func cleanText(s string) string {
	s = abbTagRe.ReplaceAllString(s, " ")
	s = strings.ReplaceAll(html.UnescapeString(s), "\u00a0", " ")
	return strings.TrimSpace(abbSpaceRe.ReplaceAllString(s, " "))
}

// parseABBSize converts ABB's "1.48" + "GBs"/"MBs" (binary units) to bytes; 0 when unparsable.
func parseABBSize(num, unit string) int64 {
	f, err := strconv.ParseFloat(strings.ReplaceAll(strings.TrimSpace(num), ",", ""), 64)
	if err != nil || f <= 0 || math.IsInf(f, 0) {
		return 0
	}
	unit = strings.ToUpper(strings.TrimSpace(unit))
	if unit == "" {
		return 0
	}
	exp := strings.Index("BKMGT", unit[:1])
	if exp < 0 {
		return 0
	}
	return int64(f * math.Pow(1024, float64(exp)))
}

// parseABBDetail reads the info hash (and combined size) of a detail page.
func parseABBDetail(body []byte) abbDetail {
	var d abbDetail
	if m := abbHashRe.FindSubmatch(body); m != nil {
		d.Hash = normalizeHash(string(m[1]))
	}
	if m := abbCombinedRe.FindSubmatch(body); m != nil {
		d.Size = parseABBSize(string(m[1]), string(m[2]))
	}
	return d
}

// ---- politeness: token bucket, TTL cache, in-flight coalescing ----

type rateLimiter struct {
	mu     sync.Mutex
	rate   float64
	burst  float64
	tokens float64
	last   time.Time
	now    func() time.Time
}

func newRateLimiter(rate float64, burst int, now func() time.Time) *rateLimiter {
	return &rateLimiter{rate: rate, burst: float64(burst), tokens: float64(burst), last: now(), now: now}
}

func (l *rateLimiter) wait(ctx context.Context) error {
	for {
		l.mu.Lock()
		t := l.now()
		l.tokens = math.Min(l.burst, l.tokens+t.Sub(l.last).Seconds()*l.rate)
		l.last = t
		if l.tokens >= 1 {
			l.tokens--
			l.mu.Unlock()
			return nil
		}
		d := time.Duration((1 - l.tokens) / l.rate * float64(time.Second))
		l.mu.Unlock()
		timer := time.NewTimer(d)
		select {
		case <-timer.C:
		case <-ctx.Done():
			timer.Stop()
			return ctx.Err()
		}
	}
}

// memo caches successful results by key for a TTL (bounded) and coalesces identical
// in-flight loads. The load runs detached from any single caller: it is cancelled only when
// every waiting caller has given up.
type memo[V any] struct {
	ttl time.Duration
	max int
	now func() time.Time

	mu      sync.Mutex
	entries map[string]memoEntry[V]
	calls   map[string]*memoCall[V]
}

type memoEntry[V any] struct {
	val V
	exp time.Time
}

type memoCall[V any] struct {
	done    chan struct{}
	val     V
	err     error
	waiters int
	cancel  context.CancelFunc
}

func newMemo[V any](ttl time.Duration, max int, now func() time.Time) *memo[V] {
	return &memo[V]{ttl: ttl, max: max, now: now,
		entries: map[string]memoEntry[V]{}, calls: map[string]*memoCall[V]{}}
}

func (m *memo[V]) get(ctx context.Context, key string, load func(context.Context) (V, error)) (V, error) {
	var zero V
	m.mu.Lock()
	if m.ttl > 0 {
		if e, ok := m.entries[key]; ok {
			if m.now().Before(e.exp) {
				m.mu.Unlock()
				return e.val, nil
			}
			delete(m.entries, key)
		}
	}
	c := m.calls[key]
	if c == nil {
		runCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
		c = &memoCall[V]{done: make(chan struct{}), cancel: cancel}
		m.calls[key] = c
		go func() {
			defer cancel()
			c.val, c.err = load(runCtx)
			m.mu.Lock()
			if m.calls[key] == c {
				delete(m.calls, key)
			}
			if c.err == nil && m.ttl > 0 {
				m.store(key, c.val)
			}
			m.mu.Unlock()
			close(c.done)
		}()
	}
	c.waiters++
	m.mu.Unlock()

	select {
	case <-c.done:
		return c.val, c.err
	case <-ctx.Done():
		m.mu.Lock()
		c.waiters--
		if c.waiters == 0 {
			if m.calls[key] == c {
				delete(m.calls, key)
			}
			c.cancel()
		}
		m.mu.Unlock()
		return zero, ctx.Err()
	}
}

// store adds an entry, evicting expired ones and then the soonest-to-expire when full.
func (m *memo[V]) store(key string, v V) {
	now := m.now()
	if _, ok := m.entries[key]; !ok && len(m.entries) >= m.max {
		for k, e := range m.entries {
			if !now.Before(e.exp) {
				delete(m.entries, k)
			}
		}
		if len(m.entries) >= m.max {
			var oldest string
			var oexp time.Time
			for k, e := range m.entries {
				if oldest == "" || e.exp.Before(oexp) {
					oldest, oexp = k, e.exp
				}
			}
			delete(m.entries, oldest)
		}
	}
	m.entries[key] = memoEntry[V]{val: v, exp: now.Add(m.ttl)}
}

// mirrorClient is an HTTP client that only follows redirects to the configured mirrors (same
// scheme family, at most 5 hops). A mirror, or whoever controls its DNS, therefore cannot bounce
// the server's request to loopback, link-local or other internal addresses.
func mirrorClient(baseURLs []string) *http.Client {
	hosts := make(map[string]bool, len(baseURLs))
	for _, b := range baseURLs {
		if u, err := url.Parse(strings.TrimSpace(b)); err == nil && u.Host != "" {
			hosts[strings.ToLower(u.Host)] = true
		}
	}
	return &http.Client{CheckRedirect: func(req *http.Request, via []*http.Request) error {
		if len(via) >= 5 {
			return errors.New("abb: too many redirects")
		}
		if (req.URL.Scheme != "http" && req.URL.Scheme != "https") || !hosts[strings.ToLower(req.URL.Host)] {
			return errors.New("abb: redirect to a host that is not a configured mirror")
		}
		return nil
	}}
}
