package sources

import (
	"context"
	"crypto/sha1"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func abbHash(slug string) string { return fmt.Sprintf("%x", sha1.Sum([]byte(slug))) }

func abbCardHTML(slug, title string) string {
	return fmt.Sprintf(`<div class="post"><div class="postTitle"><h2><a href="/abss/%s/" rel="bookmark">%s</a></h2></div>`+
		`<div class="postInfo">Language: English<br /></div><p>Posted: 13 Oct 2026<br />Format: <span>M4B</span> / Bitrate: <span>Mixed</span><br />`+
		`File Size: <span>1.48</span> GBs</p></div>`, slug, title)
}

func abbPage(n int) string {
	var b strings.Builder
	b.WriteString("<html><body>")
	for i := 0; i < n; i++ {
		b.WriteString(abbCardHTML(fmt.Sprintf("book-%02d", i), fmt.Sprintf("Book %d", i)))
	}
	b.WriteString("</body></html>")
	return b.String()
}

// abbDetailFor serves the detail fixture with a hash derived from the slug.
func abbDetailFor(t *testing.T, path string) []byte {
	t.Helper()
	slug := strings.Trim(strings.TrimPrefix(path, "/abss/"), "/")
	return []byte(strings.Replace(string(fixture(t, "abb_detail.html")),
		"549152bb35b0eab529d6e6d69fba88e7b373f5f5", abbHash(slug), 1))
}

// abbServer answers searches with search(r) and details from the fixture.
func abbServer(t *testing.T, search func(r *http.Request) string) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/abss/") {
			_, _ = w.Write(abbDetailFor(t, r.URL.Path))
			return
		}
		_, _ = w.Write([]byte(search(r)))
	}))
	t.Cleanup(srv.Close)
	return srv
}

func newABB(urls ...string) *ABB {
	return &ABB{BaseURLs: urls, HTTP: &http.Client{}, Rate: -1}
}

func TestParseABBSearchFixture(t *testing.T) {
	cards := parseABBSearch(fixture(t, "abb_search.html"))
	if len(cards) != 5 {
		t.Fatalf("cards = %d: %+v", len(cards), cards)
	}
	c := cards[0]
	if c.Path != "/abss/el-baile-de-mascaras-del-carnicero-matt-dinniman/" || c.Language != "Spanish" ||
		c.Format != "M4B" || c.Bitrate != "Variable" || c.Size != sz(1.48, 30) ||
		!c.Posted.Equal(time.Date(2026, 10, 13, 0, 0, 0, 0, time.UTC)) ||
		c.Title != "El baile de máscaras del Carnicero, Dungeon Crawler Carl, Vol. 5 - Matt Dinniman" {
		t.Errorf("card 0 = %+v", c)
	}
	if c := cards[1]; c.Size != sz(901.82, 20) || c.Bitrate != "Mixed" || !strings.Contains(c.Title, "[Full Season]") {
		t.Errorf("card 1 = %+v", c)
	}
	c = cards[3]
	if c.Path != "/abss/duncgeon-crawler-carl-book-1-matt-dinniman/" || c.Title != "duncgeon crawler carl – Book 1 & more" ||
		c.Format != "MP3" || c.Bitrate != "64 Kbps" || c.Posted.Day() != 3 {
		t.Errorf("card 3 = %+v", c)
	}
	c = cards[4]
	if c.Title != "Card Without Format" || c.Format != "" || c.Language != "Dutch" || c.Size != 0 || !c.Posted.IsZero() {
		t.Errorf("card 4 = %+v", c)
	}
	if got := parseABBSearch([]byte("<html>nothing</html>")); got == nil || len(got) != 0 {
		t.Errorf("empty page = %#v", got)
	}
	// Non-post hrefs are not cards.
	if got := parseABBSearch([]byte(`<div class="post"><h2><a href="/member/x/">t</a></h2></div>`)); len(got) != 0 {
		t.Errorf("foreign href parsed: %+v", got)
	}
}

func TestParseABBDetail(t *testing.T) {
	d := parseABBDetail(fixture(t, "abb_detail.html"))
	if d.Hash != "549152bb35b0eab529d6e6d69fba88e7b373f5f5" || d.Size != sz(7.77, 30) {
		t.Errorf("%+v", d)
	}
	if d := parseABBDetail(fixture(t, "abb_detail_nohash.html")); d.Hash != "" || d.Size == 0 {
		t.Errorf("no hash: %+v", d)
	}
	for _, bad := range []string{"zz", "<td>Info Hash:</td><td>nothex" + strings.Repeat("0", 34) + "</td>", "<td>Info Hash:</td><td></td>"} {
		if d := parseABBDetail([]byte(bad)); d.Hash != "" {
			t.Errorf("%q parsed a hash %q", bad, d.Hash)
		}
	}
	up := strings.ToUpper("549152bb35b0eab529d6e6d69fba88e7b373f5f5")
	if d := parseABBDetail([]byte("<tr><td>Info Hash:</td>\n<td> " + up + " </td></tr>")); d.Hash != strings.ToLower(up) {
		t.Errorf("uppercase hash: %q", d.Hash)
	}
}

func TestParseABBSizeAndDate(t *testing.T) {
	for _, tc := range []struct {
		num, unit string
		want      int64
	}{
		{"1.48", "GBs", sz(1.48, 30)},
		{"625", "MBs", 625 << 20},
		{"901.82", "MBs", sz(901.82, 20)},
		{"1,024", "KBs", 1024 * 1024},
		{"2", "TB", 2 << 40},
		{"10", "B", 10},
		{"0", "GBs", 0},
		{"abc", "GBs", 0},
		{"-3", "MBs", 0},
		{"5", "", 0},
		{"5", "XB", 0},
	} {
		if got := parseABBSize(tc.num, tc.unit); got != tc.want {
			t.Errorf("parseABBSize(%q,%q) = %d, want %d", tc.num, tc.unit, got, tc.want)
		}
	}
	for in, ok := range map[string]bool{"13 Oct 2026": true, "3 Jan 2026": true, "03  Jan 2026": true} {
		c, found := parseABBCard(`<h2><a href="/abss/x/">T</a></h2>Posted: ` + in)
		if !found || c.Posted.IsZero() != !ok {
			t.Errorf("date %q: %+v", in, c)
		}
	}
}

func TestABBSearchReleases(t *testing.T) {
	var queries []string
	var ua string
	srv := abbServer(t, func(r *http.Request) string {
		queries = append(queries, r.URL.Query().Get("s"))
		ua = r.Header.Get("User-Agent")
		return string(fixture(t, "abb_search.html"))
	})
	a := newABB(srv.URL)
	rels, err := a.Search(context.Background(), Query{Title: "Dungeon Crawler Carl!", Author: "Matt Dinniman"})
	if err != nil {
		t.Fatal(err)
	}
	if len(queries) != 1 || queries[0] != "dungeon crawler carl" {
		t.Errorf("queries = %q (5 posts: no surname retry)", queries)
	}
	if !strings.Contains(ua, "Chrome/") || !strings.HasPrefix(ua, "Mozilla/5.0") {
		t.Errorf("User-Agent = %q", ua)
	}
	if len(rels) != 5 {
		t.Fatalf("releases = %d", len(rels))
	}
	r := rels[0]
	wantTitle := "El baile de máscaras del Carnicero, Dungeon Crawler Carl, Vol. 5 - Matt Dinniman [M4B] [Spanish]"
	if r.Title != wantTitle || r.Source != "abb" || r.Seeders != 0 || r.Size != sz(1.48, 30) ||
		r.InfoHash != abbHash("el-baile-de-mascaras-del-carnicero-matt-dinniman") || r.PublishedAt.Year() != 2026 {
		t.Errorf("release 0 = %+v", r)
	}
	if !strings.HasPrefix(r.Magnet, "magnet:?xt=urn:btih:"+r.InfoHash+"&dn=El+baile") ||
		HashFromMagnet(r.Magnet) != r.InfoHash {
		t.Errorf("magnet = %q", r.Magnet)
	}
	if strings.Contains(r.Magnet, "stealth") && !strings.Contains(r.Magnet, "open.stealth.si%3A80") {
		t.Errorf("unexpected tracker in %q", r.Magnet)
	}
	if strings.Contains(r.Magnet, "tiny-vps") || strings.Contains(r.Magnet, "arenabg") {
		t.Errorf("page trackers leaked into %q", r.Magnet)
	}
	if strings.Contains(r.Magnet, "%5BM4B") {
		t.Errorf("dn must be the plain card title: %q", r.Magnet)
	}
	if got := rels[3].Title; got != "duncgeon crawler carl – Book 1 & more [MP3 64 Kbps] [English]" {
		t.Errorf("scrambled title kept with mp3 tag: %q", got)
	}
	if got := rels[4].Title; got != "Card Without Format [Dutch]" {
		t.Errorf("title without format: %q", got)
	}
	if rels[4].Size != sz(7.77, 30) {
		t.Errorf("size falls back to the detail's combined size: %d", rels[4].Size)
	}
}

func TestABBSurnameRetry(t *testing.T) {
	var mu sync.Mutex
	var queries []string
	srv := abbServer(t, func(r *http.Request) string {
		q := r.URL.Query().Get("s")
		mu.Lock()
		queries = append(queries, q)
		mu.Unlock()
		if q == "dungeon crawler carl" {
			return abbPage(2) // < 3 posts
		}
		return abbPage(4)[:0] + abbCardHTML("book-01", "dup") + abbCardHTML("extra", "Extra")
	})
	a := newABB(srv.URL)
	rels, err := a.Search(context.Background(), Query{Title: "Dungeon Crawler Carl", Author: "Matt Dinniman"})
	if err != nil {
		t.Fatal(err)
	}
	if len(queries) != 2 || queries[1] != "dungeon crawler carl dinniman" {
		t.Errorf("queries = %q", queries)
	}
	if len(rels) != 3 { // book-00, book-01, extra (book-01 deduped)
		t.Errorf("releases = %d: %+v", len(rels), rels)
	}

	// No author, or surname already in the title: no second query.
	queries = nil
	for _, q := range []Query{{Title: "Dungeon Crawler Carl"}, {Title: "Dinniman Stories", Author: "Matt Dinniman"}} {
		a := newABB(srv.URL)
		if _, err := a.Search(context.Background(), q); err != nil {
			t.Fatal(err)
		}
	}
	if len(queries) != 2 {
		t.Errorf("queries = %q", queries)
	}
	if rels, err := newABB(srv.URL).Search(context.Background(), Query{Title: " !! "}); err != nil || len(rels) != 0 || rels == nil {
		t.Errorf("empty title: %v %v", rels, err)
	}
}

func TestABBSurnameRetryFailureKeepsFirstResults(t *testing.T) {
	srv := abbServer(t, func(r *http.Request) string { return abbPage(1) })
	bad := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Query().Get("s"), "dinniman") {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		srv.Config.Handler.ServeHTTP(w, r)
	}))
	defer bad.Close()
	rels, err := newABB(bad.URL).Search(context.Background(), Query{Title: "Book", Author: "Dinniman"})
	if err != nil || len(rels) != 1 {
		t.Fatalf("%v %v", rels, err)
	}
}

func TestABBMaxResults(t *testing.T) {
	var details atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/abss/") {
			details.Add(1)
			_, _ = w.Write(abbDetailFor(t, r.URL.Path))
			return
		}
		_, _ = w.Write([]byte(abbPage(15)))
	}))
	defer srv.Close()
	rels, err := newABB(srv.URL).Search(context.Background(), Query{Title: "Book"})
	if err != nil || len(rels) != 10 || details.Load() != 10 {
		t.Fatalf("default cap: %d %d %v", len(rels), details.Load(), err)
	}
	a := newABB(srv.URL)
	a.MaxResults = 3
	if rels, _ := a.Search(context.Background(), Query{Title: "Book"}); len(rels) != 3 {
		t.Errorf("MaxResults=3 gave %d", len(rels))
	}
}

func TestABBMirrorFailover(t *testing.T) {
	cloudflare := func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Server", "cloudflare")
		w.WriteHeader(http.StatusForbidden)
	}
	challenge200 := func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(fixture(t, "abb_challenge.html"))
	}
	for name, bad := range map[string]http.HandlerFunc{
		"500": func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(500) },
		"503 cloudflare": func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Server", "cloudflare")
			w.WriteHeader(503)
		},
		"403 cloudflare": cloudflare,
		"403 plain":      func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(403) },
		"challenge 200":  challenge200,
		"oversized body": func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte(strings.Repeat("x", 3<<20))) },
		"hijacked":       func(w http.ResponseWriter, r *http.Request) { panic(http.ErrAbortHandler) },
	} {
		t.Run(name, func(t *testing.T) {
			var badHits, goodHits atomic.Int32
			b := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { badHits.Add(1); bad(w, r) }))
			defer b.Close()
			g := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				goodHits.Add(1)
				if strings.HasPrefix(r.URL.Path, "/abss/") {
					_, _ = w.Write(abbDetailFor(t, r.URL.Path))
					return
				}
				_, _ = w.Write([]byte(abbPage(2)))
			}))
			defer g.Close()
			a := newABB(b.URL, g.URL)
			a.CacheTTL = -1
			rels, err := a.Search(context.Background(), Query{Title: "Book"})
			if err != nil || len(rels) != 2 {
				t.Fatalf("%v %v", rels, err)
			}
			if badHits.Load() != 1 {
				t.Errorf("bad mirror hit %d times in the first search", badHits.Load())
			}
			// The good mirror is remembered: the bad one is not tried again.
			if _, err := a.Search(context.Background(), Query{Title: "Book"}); err != nil {
				t.Fatal(err)
			}
			if badHits.Load() != 1 {
				t.Errorf("bad mirror retried after a good one answered: %d", badHits.Load())
			}
		})
	}
}

func TestABBMirrorNetworkErrorAndOrder(t *testing.T) {
	dead := httptest.NewServer(http.NotFoundHandler())
	dead.Close()
	g1 := abbServer(t, func(r *http.Request) string { return abbPage(1) })
	g2 := abbServer(t, func(r *http.Request) string { return abbPage(1) })
	a := newABB(dead.URL, g1.URL, g2.URL)
	if _, err := a.Search(context.Background(), Query{Title: "Book"}); err != nil {
		t.Fatal(err)
	}
	if a.good.Load() != 1 {
		t.Errorf("good = %d, want 1 (first live mirror)", a.good.Load())
	}
	// Remembered mirror dies: order falls back to the configured order, skipping nothing.
	g1.Close()
	a.CacheTTL = 0
	a2 := newABB(dead.URL, g1.URL, g2.URL)
	a2.good.Store(1)
	if _, err := a2.Search(context.Background(), Query{Title: "Book"}); err != nil {
		t.Fatal(err)
	}
	if a2.good.Load() != 2 {
		t.Errorf("good = %d, want 2", a2.good.Load())
	}
}

func TestABBAllMirrorsFailTypedErrors(t *testing.T) {
	cf := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write(fixture(t, "abb_challenge.html"))
	}))
	defer cf.Close()
	e500 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(502) }))
	defer e500.Close()
	_, err := newABB(cf.URL, e500.URL).Search(context.Background(), Query{Title: "Book"})
	if !errors.Is(err, ErrUpstream) {
		t.Fatalf("err = %v", err)
	}
	var he *HTTPError
	if !errors.As(err, &he) || he.Status != 502 {
		t.Errorf("HTTPError not reachable: %v", err)
	}
	if _, err := newABB().Search(context.Background(), Query{Title: "Book"}); err == nil {
		t.Error("no base URLs: expected error")
	}
}

func TestABBChallengeDetection(t *testing.T) {
	for name, tc := range map[string]struct {
		body string
		want bool
	}{
		"interstitial":        {string(fixtureNoT("abb_challenge.html")), true},
		"cf-chl only":         {`<form id="cf-chl-form">`, true},
		"moment, real page":   {`<div class="postTitle"><h2>Just a moment of silence</h2>`, false},
		"moment, detail page": {`Just a moment <td>Info Hash:</td>`, false},
		"normal":              {abbPage(1), false},
		"moment alone":        {`<title>Just a moment...</title>`, true},
	} {
		if got := isABBChallenge([]byte(tc.body)); got != tc.want {
			t.Errorf("%s: %v", name, got)
		}
	}
}

func fixtureNoT(name string) []byte {
	b, err := os.ReadFile("testdata/" + name)
	if err != nil {
		panic(err)
	}
	return b
}

func TestABBRateLimitedDoesNotFailOver(t *testing.T) {
	var second atomic.Int32
	r429 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Retry-After", "7")
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer r429.Close()
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { second.Add(1) }))
	defer other.Close()
	_, err := newABB(r429.URL, other.URL).Search(context.Background(), Query{Title: "Book"})
	var he *HTTPError
	if !errors.Is(err, ErrRateLimited) || !errors.As(err, &he) || he.RetryAfter != 7*time.Second {
		t.Fatalf("err = %v", err)
	}
	if second.Load() != 0 {
		t.Error("429 must not hit the next mirror")
	}
}

func TestABBDetailWithoutHashIsSkipped(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.URL.Path == "/abss/book-01/":
			_, _ = w.Write(fixture(t, "abb_detail_nohash.html"))
		case r.URL.Path == "/abss/book-02/":
			http.NotFound(w, r)
		case strings.HasPrefix(r.URL.Path, "/abss/"):
			_, _ = w.Write(abbDetailFor(t, r.URL.Path))
		default:
			_, _ = w.Write([]byte(abbPage(4)))
		}
	}))
	defer srv.Close()
	rels, err := newABB(srv.URL).Search(context.Background(), Query{Title: "Book"})
	if err != nil || len(rels) != 2 {
		t.Fatalf("%d %v", len(rels), err)
	}
	if rels[0].Title != "Book 0 [M4B] [English]" || rels[1].Title != "Book 3 [M4B] [English]" {
		t.Errorf("order/titles: %q, %q", rels[0].Title, rels[1].Title)
	}
}

func TestABBAllDetailsFailReturnsError(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/abss/") {
			w.WriteHeader(http.StatusTooManyRequests)
			return
		}
		_, _ = w.Write([]byte(abbPage(3)))
	}))
	defer srv.Close()
	if _, err := newABB(srv.URL).Search(context.Background(), Query{Title: "Book"}); !errors.Is(err, ErrRateLimited) {
		t.Fatalf("err = %v", err)
	}
	// No hash anywhere is not an error, just no releases.
	srv2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/abss/") {
			_, _ = w.Write(fixture(t, "abb_detail_nohash.html"))
			return
		}
		_, _ = w.Write([]byte(abbPage(3)))
	}))
	defer srv2.Close()
	if rels, err := newABB(srv2.URL).Search(context.Background(), Query{Title: "Book"}); err != nil || len(rels) != 0 {
		t.Fatalf("%v %v", rels, err)
	}
}

func TestABBDetailConcurrencyLimit(t *testing.T) {
	var cur, peak atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !strings.HasPrefix(r.URL.Path, "/abss/") {
			_, _ = w.Write([]byte(abbPage(10)))
			return
		}
		n := cur.Add(1)
		defer cur.Add(-1)
		for {
			p := peak.Load()
			if n <= p || peak.CompareAndSwap(p, n) {
				break
			}
		}
		time.Sleep(30 * time.Millisecond)
		_, _ = w.Write(abbDetailFor(t, r.URL.Path))
	}))
	defer srv.Close()
	a := newABB(srv.URL)
	rels, err := a.Search(context.Background(), Query{Title: "Book"})
	if err != nil || len(rels) != 10 {
		t.Fatalf("%d %v", len(rels), err)
	}
	if peak.Load() != 4 {
		t.Errorf("peak concurrent details = %d, want 4", peak.Load())
	}
	peak.Store(0)
	a = newABB(srv.URL)
	a.Parallel = 2
	if _, err := a.Search(context.Background(), Query{Title: "Book"}); err != nil {
		t.Fatal(err)
	}
	if peak.Load() != 2 {
		t.Errorf("peak with Parallel=2 = %d", peak.Load())
	}
}

func TestABBCacheAvoidsSecondRequest(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		if strings.HasPrefix(r.URL.Path, "/abss/") {
			_, _ = w.Write(abbDetailFor(t, r.URL.Path))
			return
		}
		_, _ = w.Write([]byte(abbPage(3)))
	}))
	defer srv.Close()
	a := newABB(srv.URL)
	clock := time.Now()
	var cmu sync.Mutex
	a.now = func() time.Time { cmu.Lock(); defer cmu.Unlock(); return clock }
	q := Query{Title: "Book"}
	if _, err := a.Search(context.Background(), q); err != nil {
		t.Fatal(err)
	}
	if hits.Load() != 4 {
		t.Fatalf("first search hits = %d", hits.Load())
	}
	// Case-insensitive on the query, served entirely from memory.
	if rels, err := a.Search(context.Background(), Query{Title: "book"}); err != nil || len(rels) != 3 || hits.Load() != 4 {
		t.Fatalf("second search: %d releases, %d hits, %v", len(rels), hits.Load(), err)
	}
	cmu.Lock()
	clock = clock.Add(31 * time.Minute)
	cmu.Unlock()
	if _, err := a.Search(context.Background(), q); err != nil || hits.Load() != 8 {
		t.Fatalf("after TTL: %d hits, %v", hits.Load(), err)
	}
	// Disabled cache.
	hits.Store(0)
	off := newABB(srv.URL)
	off.CacheTTL = -1
	_, _ = off.Search(context.Background(), q)
	_, _ = off.Search(context.Background(), q)
	if hits.Load() != 8 {
		t.Errorf("no-cache hits = %d, want 8", hits.Load())
	}
}

func TestABBErrorsAreNotCached(t *testing.T) {
	var hits atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if hits.Add(1) == 1 {
			w.WriteHeader(500)
			return
		}
		_, _ = w.Write([]byte(abbPage(1)))
	}))
	defer srv.Close()
	a := newABB(srv.URL)
	if _, err := a.Search(context.Background(), Query{Title: "Book"}); err == nil {
		t.Fatal("expected error")
	}
	a.good.Store(0)
	if _, err := a.Search(context.Background(), Query{Title: "Book"}); err != nil {
		t.Fatal(err)
	}
}

func TestABBCoalescesIdenticalSearches(t *testing.T) {
	var searches atomic.Int32
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/abss/") {
			_, _ = w.Write(abbDetailFor(t, r.URL.Path))
			return
		}
		searches.Add(1)
		<-release
		_, _ = w.Write([]byte(abbPage(2)))
	}))
	defer srv.Close()
	a := newABB(srv.URL)
	var wg sync.WaitGroup
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if rels, err := a.Search(context.Background(), Query{Title: "Book"}); err != nil || len(rels) != 2 {
				t.Errorf("%d %v", len(rels), err)
			}
		}()
	}
	deadline := time.Now().Add(2 * time.Second)
	for searches.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	time.Sleep(50 * time.Millisecond) // let the other callers join the in-flight request
	close(release)
	wg.Wait()
	if searches.Load() != 1 {
		t.Errorf("search requests = %d, want 1", searches.Load())
	}
}

func TestABBContextCancellation(t *testing.T) {
	started := make(chan struct{}, 1)
	gone := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		started <- struct{}{}
		<-r.Context().Done()
		close(gone)
	}))
	defer srv.Close()
	a := newABB(srv.URL)
	ctx, cancel := context.WithCancel(context.Background())
	errc := make(chan error, 1)
	go func() { _, err := a.Search(ctx, Query{Title: "Book"}); errc <- err }()
	<-started
	cancel()
	select {
	case err := <-errc:
		if !errors.Is(err, context.Canceled) {
			t.Errorf("err = %v", err)
		}
	case <-time.After(3 * time.Second):
		t.Fatal("Search did not return after cancel")
	}
	select {
	case <-gone:
	case <-time.After(3 * time.Second):
		t.Fatal("the in-flight request outlived its only caller")
	}

	// Already-cancelled context.
	if _, err := newABB(srv.URL).Search(ctx, Query{Title: "Other"}); err == nil {
		t.Error("expected error on cancelled ctx")
	}
}

func TestABBPartialResultsWhenBudgetRunsOut(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/":
			_, _ = w.Write([]byte(abbPage(3)))
		case "/abss/book-01/":
			<-r.Context().Done()
		default:
			_, _ = w.Write(abbDetailFor(t, r.URL.Path))
		}
	}))
	defer srv.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 300*time.Millisecond)
	defer cancel()
	rels, err := newABB(srv.URL).Search(ctx, Query{Title: "Book"})
	if err != nil || len(rels) != 2 {
		t.Fatalf("%d %v", len(rels), err)
	}
}

func TestABBRequestTimeout(t *testing.T) {
	var hits atomic.Int32
	slow := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		<-r.Context().Done()
	}))
	defer slow.Close()
	good := abbServer(t, func(r *http.Request) string { return abbPage(1) })
	a := newABB(slow.URL, good.URL)
	a.Timeout = 100 * time.Millisecond
	start := time.Now()
	rels, err := a.Search(context.Background(), Query{Title: "Book"})
	if err != nil || len(rels) != 1 || time.Since(start) > 3*time.Second {
		t.Fatalf("%d %v after %v", len(rels), err, time.Since(start))
	}
}

func TestABBQueryNormalisation(t *testing.T) {
	for _, tc := range []struct{ title, want string }{
		{"Carl's Doomsday Scenario", "carls doomsday scenario"},
		{"Carl’s Doomsday “Scenario”", "carls doomsday scenario"},
		{`It's "Fine": Here — (Really)!`, "its fine here really"},
		{"  Dungeon   Crawler\tCarl ", "dungeon crawler carl"},
		{"Ça va? Été 2", "ça va été 2"},
	} {
		var got atomic.Value
		srv := abbServer(t, func(r *http.Request) string {
			got.Store(r.URL.Query().Get("s"))
			return abbPage(5)
		})
		if _, err := newABB(srv.URL).Search(context.Background(), Query{Title: tc.title}); err != nil {
			t.Fatal(err)
		}
		if got.Load() != tc.want {
			t.Errorf("%q: site query %q, want %q", tc.title, got.Load(), tc.want)
		}
	}
}

func TestABBSurnameRetryStripsQuotes(t *testing.T) {
	var mu sync.Mutex
	var queries []string
	srv := abbServer(t, func(r *http.Request) string {
		mu.Lock()
		queries = append(queries, r.URL.Query().Get("s"))
		mu.Unlock()
		return abbPage(1)
	})
	if _, err := newABB(srv.URL).Search(context.Background(), Query{Title: "Dark", Author: "Pat O'Brien"}); err != nil {
		t.Fatal(err)
	}
	if len(queries) != 2 || queries[1] != "dark obrien" {
		t.Errorf("queries = %q", queries)
	}
}

func hangingServer(t *testing.T, hits *atomic.Int32) *httptest.Server {
	t.Helper()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		<-r.Context().Done()
	}))
	t.Cleanup(srv.Close)
	return srv
}

func TestABBBudgetCoversMirrorFailover(t *testing.T) {
	var hits atomic.Int32
	var urls []string
	for i := 0; i < 3; i++ {
		urls = append(urls, hangingServer(t, &hits).URL)
	}
	a := newABB(urls...)
	a.Timeout = 200 * time.Millisecond
	a.Budget = 300 * time.Millisecond
	start := time.Now()
	_, err := a.Search(context.Background(), Query{Title: "Book"})
	took := time.Since(start)
	if err == nil {
		t.Fatal("expected an error")
	}
	if took > 900*time.Millisecond || took < 250*time.Millisecond {
		t.Errorf("took %v: 3 mirrors x 200ms must be capped by the 300ms budget", took)
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("err = %v, want a deadline error", err)
	}
}

func TestABBRequestTimeoutClampedToBudget(t *testing.T) {
	a := &ABB{Timeout: time.Minute, Budget: 2 * time.Second}
	if got := a.requestTimeout(); got != 2*time.Second {
		t.Errorf("requestTimeout = %v", got)
	}
	a = &ABB{}
	if a.budget() != 15*time.Second || a.requestTimeout() != 12*time.Second {
		t.Errorf("defaults: budget %v request %v", a.budget(), a.requestTimeout())
	}
}

func TestABBBudgetReturnsPartialDetails(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/":
			_, _ = w.Write([]byte(abbPage(3)))
		case "/abss/book-01/":
			<-r.Context().Done()
		default:
			_, _ = w.Write(abbDetailFor(t, r.URL.Path))
		}
	}))
	defer srv.Close()
	a := newABB(srv.URL)
	a.Budget = 300 * time.Millisecond
	start := time.Now()
	rels, err := a.Search(context.Background(), Query{Title: "Book"})
	if err != nil || len(rels) != 2 {
		t.Fatalf("%d %v", len(rels), err)
	}
	if time.Since(start) > 2*time.Second {
		t.Errorf("took %v", time.Since(start))
	}
}

func TestABBRateLimiter(t *testing.T) {
	l := newRateLimiter(20, 2, time.Now)
	start := time.Now()
	for i := 0; i < 6; i++ {
		if err := l.wait(context.Background()); err != nil {
			t.Fatal(err)
		}
	}
	// 2 immediate tokens, then 4 more at 20/s = ~200ms.
	if d := time.Since(start); d < 150*time.Millisecond || d > 2*time.Second {
		t.Errorf("6 waits took %v", d)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	l = newRateLimiter(0.001, 1, time.Now)
	_ = l.wait(context.Background())
	if err := l.wait(ctx); !errors.Is(err, context.Canceled) {
		t.Errorf("cancelled wait: %v", err)
	}
	// Default config enables limiting; Rate < 0 disables it.
	d := &ABB{}
	d.setup()
	if d.lim == nil || d.lim.rate != 2 || d.lim.burst != 4 {
		t.Errorf("defaults: %+v", d.lim)
	}
	off := &ABB{Rate: -1}
	off.setup()
	if off.lim != nil {
		t.Error("Rate<0 must disable the limiter")
	}
}

func TestMemoBoundedAndExpiring(t *testing.T) {
	now := time.Unix(1000, 0)
	m := newMemo[int](time.Minute, 3, func() time.Time { return now })
	load := func(v int) func(context.Context) (int, error) {
		return func(context.Context) (int, error) { return v, nil }
	}
	for i := 0; i < 5; i++ {
		now = now.Add(time.Second)
		if _, err := m.get(context.Background(), fmt.Sprint("k", i), load(i)); err != nil {
			t.Fatal(err)
		}
	}
	if len(m.entries) != 3 {
		t.Fatalf("entries = %d, want bound 3", len(m.entries))
	}
	if _, ok := m.entries["k0"]; ok {
		t.Error("oldest entry was not evicted")
	}
	now = now.Add(10 * time.Minute)
	if _, err := m.get(context.Background(), "new", load(9)); err != nil {
		t.Fatal(err)
	}
	if len(m.entries) != 1 {
		t.Errorf("expired entries survived: %d", len(m.entries))
	}
	if _, err := m.get(context.Background(), "bad", func(context.Context) (int, error) { return 0, ErrUpstream }); !errors.Is(err, ErrUpstream) {
		t.Fatal(err)
	}
	if _, ok := m.entries["bad"]; ok {
		t.Error("error was cached")
	}
}

func TestABBImplementsSource(t *testing.T) {
	var s Source = NewABB(nil)
	if s.Name() != "abb" {
		t.Error(s.Name())
	}
	if a := NewABB(nil); len(a.BaseURLs) != 3 || a.BaseURLs[0] != "https://audiobookbay.lu" {
		t.Errorf("%v", a.BaseURLs)
	}
}

// sz is f units of 2^shift bytes (a variable conversion: constant floats do not fit int64).
func sz(f float64, shift uint) int64 { return int64(f * float64(int64(1)<<shift)) }

func TestMirrorClientRefusesForeignRedirects(t *testing.T) {
	var hit bool
	internal := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { hit = true }))
	defer internal.Close()
	mirror := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, internal.URL+"/secret", http.StatusFound)
	}))
	defer mirror.Close()

	c := mirrorClient([]string{mirror.URL})
	resp, err := c.Get(mirror.URL)
	if err == nil {
		resp.Body.Close()
		t.Fatal("a redirect to a host that is not a configured mirror must fail")
	}
	if hit {
		t.Fatal("the foreign host was contacted")
	}
}
