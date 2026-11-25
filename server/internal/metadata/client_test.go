package metadata

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

// newTestClient returns a client wired to one httptest server standing in for both hosts.
func newTestClient(t *testing.T, h http.HandlerFunc) (*Client, *httptest.Server) {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return &Client{AudibleBaseURL: srv.URL, AudnexusBaseURL: srv.URL, HTTP: srv.Client()}, srv
}

func serveJSON(body []byte) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(body)
	}
}

func TestSearch(t *testing.T) {
	var gotQuery string
	var gotUA string
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		gotQuery = r.URL.RawQuery
		gotUA = r.Header.Get("User-Agent")
		if r.URL.Path != "/1.0/catalog/products" {
			t.Errorf("path = %s", r.URL.Path)
		}
		serveJSON(fixture(t, "audible_search_dune.json"))(w, r)
	})
	books, err := c.Search(context.Background(), "us", "en", "dune")
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"keywords=dune", "num_results=50", "products_sort_by=Relevance", "content_type=Product", "image_sizes=500", "response_groups=contributors"} {
		if !strings.Contains(gotQuery, want) {
			t.Errorf("query %q lacks %q", gotQuery, want)
		}
	}
	if !strings.HasPrefix(gotUA, "stremio-audiobooks/") {
		t.Errorf("UA = %q", gotUA)
	}
	if len(books) != 3 {
		t.Fatalf("got %d books", len(books))
	}
	b := books[0]
	if b.ASIN != "B002V1OF70" || b.Title != "Dune" || b.Subtitle != "Book One in the Dune Chronicles" {
		t.Errorf("book = %+v", b)
	}
	if len(b.Authors) != 1 || b.Authors[0] != "Frank Herbert" || len(b.Narrators) != 12 {
		t.Errorf("people = %v / %v", b.Authors, b.Narrators)
	}
	if b.Series != "The Dune Sequence" || b.SeriesPosition != "12" {
		t.Errorf("series = %q %q", b.Series, b.SeriesPosition)
	}
	if b.Language != "en" || b.RuntimeMin != 1262 || b.ReleaseYear != 2007 || b.Region != "us" {
		t.Errorf("attrs = %+v", b)
	}
	if b.CoverURL != "https://m.media-amazon.com/images/I/41rrXYM-wHL._SL500_.jpg" {
		t.Errorf("cover = %q", b.CoverURL)
	}
	if strings.Contains(b.Description, "<") || !strings.HasPrefix(b.Description, "NOW A MAJOR") {
		t.Errorf("description = %q", b.Description)
	}
}

func TestSearchDutchFiltersDedupesAndDrops(t *testing.T) {
	c, _ := newTestClient(t, serveJSON(fixture(t, "audible_search_dutch.json")))
	books, err := c.Search(context.Background(), "nl", "nl", "nederlands")
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, b := range books {
		if seen[b.ASIN] {
			t.Errorf("duplicate %s", b.ASIN)
		}
		seen[b.ASIN] = true
		if b.Language != "nl" {
			t.Errorf("%s language = %q", b.ASIN, b.Language)
		}
		if b.Region != "us" {
			t.Errorf("nl must resolve to us, got %q", b.Region)
		}
	}
	if seen["B0NOAUTH01"] {
		t.Error("item without authors/title must be dropped")
	}
	if seen["B01I28NFEE"] {
		t.Error("english book must be filtered out for language nl")
	}
	if !seen["B0H5HL6NW3"] || !seen["8726421682"] {
		t.Errorf("missing expected dutch books: %v", seen)
	}

	all, err := c.Search(context.Background(), "nl", "", "nederlands")
	if err != nil {
		t.Fatal(err)
	}
	if len(all) <= len(books) {
		t.Errorf("without language filter expected more books (%d <= %d)", len(all), len(books))
	}
}

func TestSearchEmptyQueryNoRequest(t *testing.T) {
	var calls atomic.Int32
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	books, err := c.Search(context.Background(), "us", "", "  ")
	if err != nil || books == nil || len(books) != 0 || calls.Load() != 0 {
		t.Errorf("books=%v err=%v calls=%d", books, err, calls.Load())
	}
}

func TestSearchUnknownRegion(t *testing.T) {
	c := &Client{}
	if _, err := c.Search(context.Background(), "xx", "", "dune"); !errors.Is(err, ErrUnknownRegion) {
		t.Errorf("err = %v", err)
	}
}

func TestDiscover(t *testing.T) {
	var q string
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		q = r.URL.RawQuery
		serveJSON(fixture(t, "audible_discover.json"))(w, r)
	})
	books, err := c.Discover(context.Background(), "us", "en", 2)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"products_sort_by=BestSellers", "page=2"} {
		if !strings.Contains(q, want) {
			t.Errorf("query %q lacks %q", q, want)
		}
	}
	if strings.Contains(q, "keywords") {
		t.Errorf("english discover must not seed keywords: %q", q)
	}
	if len(books) == 0 {
		t.Fatal("no books")
	}
	for _, b := range books {
		if b.ASIN == "" || b.Title == "" || len(b.Authors) == 0 || b.Language != "en" {
			t.Errorf("bad book %+v", b)
		}
	}

	if _, err := c.DiscoverCategory(context.Background(), "nl", "nl", "18574426011", -3); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{"keywords=verhaal", "category_id=18574426011", "page=0"} {
		if !strings.Contains(q, want) {
			t.Errorf("dutch query %q lacks %q", q, want)
		}
	}
}

func TestBookAudnexus(t *testing.T) {
	var audibleCalls atomic.Int32
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/books/B002V1OF70":
			if r.URL.Query().Get("region") != "us" {
				t.Errorf("region = %q", r.URL.Query().Get("region"))
			}
			serveJSON(fixture(t, "audnexus_book_dune.json"))(w, r)
		default:
			audibleCalls.Add(1)
			http.NotFound(w, r)
		}
	})
	b, err := c.Book(context.Background(), "us", "B002V1OF70")
	if err != nil {
		t.Fatal(err)
	}
	if b.Title != "Dune" || b.Subtitle != "Book One in the Dune Chronicles" || b.Authors[0] != "Frank Herbert" {
		t.Errorf("book = %+v", b)
	}
	if b.Series != "Dune" || b.SeriesPosition != "1" || b.Language != "en" || b.RuntimeMin != 1262 || b.ReleaseYear != 2007 {
		t.Errorf("attrs = %+v", b)
	}
	if b.CoverURL != "https://m.media-amazon.com/images/I/71-1WBgjGoL.jpg" {
		t.Errorf("cover = %q", b.CoverURL)
	}
	if len(b.Genres) != 2 || b.Genres[0] != "Literature & Fiction" {
		t.Errorf("genres = %v", b.Genres)
	}
	if audibleCalls.Load() != 0 {
		t.Error("audible must not be called when audnexus answers")
	}
}

func TestBookFallsBackToAudibleOn404(t *testing.T) {
	var paths []string
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		paths = append(paths, r.URL.Path)
		if strings.HasPrefix(r.URL.Path, "/books/") {
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write(fixture(t, "audnexus_404.json"))
			return
		}
		serveJSON(fixture(t, "audible_product_dutch.json"))(w, r)
	})
	b, err := c.Book(context.Background(), "nl", "B0H5HL6NW3")
	if err != nil {
		t.Fatal(err)
	}
	if b.Title != "Vrouwenleed" || b.Language != "nl" || b.Region != "us" || b.RuntimeMin != 529 || b.ReleaseYear != 2026 {
		t.Errorf("book = %+v", b)
	}
	if len(paths) != 2 || paths[1] != "/1.0/catalog/products/B0H5HL6NW3" {
		t.Errorf("paths = %v", paths)
	}
}

func TestBookNotFoundEverywhere(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) { http.NotFound(w, r) })
	_, err := c.Book(context.Background(), "us", "B000000000")
	if !errors.Is(err, ErrNotFound) {
		t.Errorf("err = %v", err)
	}
}

func TestBookDutchAudnexus(t *testing.T) {
	c, _ := newTestClient(t, serveJSON(fixture(t, "audnexus_book_dutch.json")))
	b, err := c.Book(context.Background(), "us", "B0H5HL6NW3")
	if err != nil {
		t.Fatal(err)
	}
	if b.Language != "nl" || b.Title != "Vrouwenleed" {
		t.Errorf("book = %+v", b)
	}
}

func TestBookRateLimitedDoesNotFallBack(t *testing.T) {
	var calls atomic.Int32
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.Header().Set("Retry-After", "7")
		w.WriteHeader(http.StatusTooManyRequests)
		_, _ = w.Write(fixture(t, "rate_limited.json"))
	})
	_, err := c.Book(context.Background(), "us", "B002V1OF70")
	if !errors.Is(err, ErrRateLimited) {
		t.Fatalf("err = %v", err)
	}
	var he *HTTPError
	if !errors.As(err, &he) || he.RetryAfter != 7*time.Second || he.Service != "audnexus" {
		t.Errorf("he = %+v", he)
	}
	if calls.Load() != 1 {
		t.Errorf("calls = %d", calls.Load())
	}
}

func TestChapters(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/books/B002V1OF70/chapters" {
			t.Errorf("path = %s", r.URL.Path)
		}
		serveJSON(fixture(t, "audnexus_chapters_dune.json"))(w, r)
	})
	chs, err := c.Chapters(context.Background(), "us", "B002V1OF70")
	if err != nil {
		t.Fatal(err)
	}
	if len(chs) < 3 {
		t.Fatalf("chapters = %d", len(chs))
	}
	if chs[0] != (Chapter{Title: "Opening Credits", StartMs: 0, LengthMs: 60970}) {
		t.Errorf("first = %+v", chs[0])
	}
	if chs[1].StartMs != 60970 || chs[1].Title != "Book One: Dune" {
		t.Errorf("second = %+v", chs[1])
	}
}

func TestChapterListBranding(t *testing.T) {
	cases := []struct {
		name, body        string
		runtime, in, out_ int64
	}{
		{"fixture", string(fixture(t, "audnexus_chapters_dune.json")), 75735564, 3970, 4945},
		{"absent", `{"runtimeLengthMs":1000,"chapters":[]}`, 1000, 0, 0},
		{"negative", `{"runtimeLengthMs":1000,"brandIntroDurationMs":-1,"brandOutroDurationMs":-9,"chapters":[]}`, 1000, 0, 0},
		{"longer than the book", `{"runtimeLengthMs":1000,"brandIntroDurationMs":1001,"brandOutroDurationMs":1000,"chapters":[]}`, 1000, 0, 1000},
		{"runtime unknown", `{"brandIntroDurationMs":5,"brandOutroDurationMs":7,"chapters":[]}`, 0, 5, 7},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			c, _ := newTestClient(t, serveJSON([]byte(tc.body)))
			cl, err := c.ChapterList(context.Background(), "us", "B002V1OF70")
			if err != nil {
				t.Fatal(err)
			}
			if cl.RuntimeMs != tc.runtime || cl.IntroMs != tc.in || cl.OutroMs != tc.out_ {
				t.Fatalf("runtime=%d intro=%d outro=%d, want %d %d %d", cl.RuntimeMs, cl.IntroMs, cl.OutroMs, tc.runtime, tc.in, tc.out_)
			}
			again, err := c.ChapterList(context.Background(), "us", "B002V1OF70") // served from the cache, branding included
			if err != nil || again.IntroMs != cl.IntroMs || again.OutroMs != cl.OutroMs {
				t.Fatalf("cached: %+v %v", again, err)
			}
		})
	}
}

func TestChapters404(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusNotFound)
		_, _ = w.Write(fixture(t, "audnexus_404.json"))
	})
	if _, err := c.Chapters(context.Background(), "us", "B000000000"); !errors.Is(err, ErrNotFound) {
		t.Errorf("err = %v", err)
	}
}

func TestStatusErrors(t *testing.T) {
	tests := []struct {
		status int
		want   error
	}{
		{http.StatusTooManyRequests, ErrRateLimited},
		{http.StatusInternalServerError, ErrUpstream},
		{http.StatusBadGateway, ErrUpstream},
		{http.StatusNotFound, ErrNotFound},
	}
	for _, tc := range tests {
		c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(tc.status) })
		_, err := c.Search(context.Background(), "us", "", "dune")
		if !errors.Is(err, tc.want) {
			t.Errorf("status %d: err = %v, want %v", tc.status, err, tc.want)
		}
	}
}

func TestInvalidASINNeverReachesNetwork(t *testing.T) {
	var calls atomic.Int32
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	for _, asin := range []string{"", "b002v1of70", "B002V1OF7", "B002V1OF700", "../etc/pass", "B002V1OF7?"} {
		if _, err := c.Book(context.Background(), "us", asin); !errors.Is(err, ErrInvalidASIN) {
			t.Errorf("Book(%q) err = %v", asin, err)
		}
		if _, err := c.Chapters(context.Background(), "us", asin); !errors.Is(err, ErrInvalidASIN) {
			t.Errorf("Chapters(%q) err = %v", asin, err)
		}
	}
	if calls.Load() != 0 {
		t.Errorf("calls = %d", calls.Load())
	}
}

func TestBodySizeLimit(t *testing.T) {
	c, _ := newTestClient(t, serveJSON(fixture(t, "audible_search_dune.json")))
	c.MaxBodyBytes = 1024
	if _, err := c.Search(context.Background(), "us", "", "dune"); !errors.Is(err, ErrBodyTooLarge) {
		t.Errorf("err = %v", err)
	}
}

func TestContextCancelAndTimeout(t *testing.T) {
	block := make(chan struct{})
	c, srv := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-block:
		case <-r.Context().Done():
		}
	})
	defer close(block)
	_ = srv

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := c.Search(ctx, "us", "", "dune"); !errors.Is(err, context.Canceled) {
		t.Errorf("cancel: err = %v", err)
	}

	c.Timeout = 50 * time.Millisecond
	if _, err := c.Search(context.Background(), "us", "", "dune"); !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("timeout: err = %v", err)
	}
}

func TestMarketplaceRegionAndHelpers(t *testing.T) {
	for in, want := range map[string]string{"us": "us", "NL": "us", " uk ": "uk", "de": "de"} {
		if got, err := MarketplaceRegion(in); err != nil || got != want {
			t.Errorf("MarketplaceRegion(%q) = %q, %v", in, got, err)
		}
	}
	for in, want := range map[string]string{
		"english": "en", "Dutch": "nl", "nl": "nl", "klingon": "", "": "",
	} {
		if got := NormalizeLanguage(in); got != want {
			t.Errorf("NormalizeLanguage(%q) = %q", in, got)
		}
	}
	for in, want := range map[string]string{
		"http://x/a.jpg": "https://x/a.jpg", "//x/a.jpg": "https://x/a.jpg",
		"https://x/a.jpg": "https://x/a.jpg", "javascript:alert(1)": "", "": "",
	} {
		if got := HTTPSURL(in); got != want {
			t.Errorf("HTTPSURL(%q) = %q", in, got)
		}
	}
	if got := bestImage(map[string]string{"250": "http://x/s.jpg", "500": "http://x/l.jpg"}); got != "https://x/l.jpg" {
		t.Errorf("bestImage = %q", got)
	}
}

func TestChapterListCaching(t *testing.T) {
	var calls atomic.Int32
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	status := http.StatusOK
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if status != http.StatusOK {
			w.WriteHeader(status)
			return
		}
		serveJSON([]byte(`{"asin":"B002V1OF70","runtimeLengthMs":3600000,"chapters":[
			{"title":" One ","startOffsetMs":0,"lengthMs":1000},
			{"title":"bad","startOffsetMs":-5,"lengthMs":1},
			{"title":"Two","startOffsetMs":1000,"lengthMs":2000}]}`))(w, r)
	})
	c.Now = func() time.Time { return now }
	ctx := context.Background()

	for i := 0; i < 3; i++ {
		cl, err := c.ChapterList(ctx, "us", "B002V1OF70")
		if err != nil {
			t.Fatal(err)
		}
		if cl.RuntimeMs != 3600000 || len(cl.Chapters) != 2 || cl.Chapters[0].Title != "One" || cl.Chapters[1].StartMs != 1000 {
			t.Fatalf("list = %+v", cl)
		}
	}
	if calls.Load() != 1 {
		t.Fatalf("calls = %d, want 1 (cached)", calls.Load())
	}
	// "nl" shares the marketplace of "us"; another region is another cache entry.
	if _, err := c.ChapterList(ctx, "nl", "B002V1OF70"); err != nil || calls.Load() != 1 {
		t.Fatalf("nl: err=%v calls=%d", err, calls.Load())
	}
	if _, err := c.ChapterList(ctx, "uk", "B002V1OF70"); err != nil || calls.Load() != 2 {
		t.Fatalf("uk: err=%v calls=%d", err, calls.Load())
	}
	now = now.Add(chapterTTL + time.Second)
	if _, err := c.ChapterList(ctx, "us", "B002V1OF70"); err != nil || calls.Load() != 3 {
		t.Fatalf("after ttl: err=%v calls=%d", err, calls.Load())
	}

	// A miss is cached for chapterMissTTL; other failures are not cached.
	status = http.StatusNotFound
	for i := 0; i < 2; i++ {
		if _, err := c.ChapterList(ctx, "us", "B000000001"); !errors.Is(err, ErrNotFound) {
			t.Fatalf("miss err = %v", err)
		}
	}
	if calls.Load() != 4 {
		t.Fatalf("calls = %d after cached miss", calls.Load())
	}
	now = now.Add(chapterMissTTL + time.Second)
	_, _ = c.ChapterList(ctx, "us", "B000000001")
	if calls.Load() != 5 {
		t.Fatalf("miss should expire, calls = %d", calls.Load())
	}
	status = http.StatusBadGateway
	for i := 0; i < 2; i++ {
		if _, err := c.ChapterList(ctx, "us", "B000000002"); !errors.Is(err, ErrUpstream) {
			t.Fatalf("5xx err = %v", err)
		}
	}
	if calls.Load() != 7 {
		t.Fatalf("5xx must not be cached, calls = %d", calls.Load())
	}
}
