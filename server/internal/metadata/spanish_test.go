package metadata

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// stand records the queries of the per-region Audible stand-ins made by newSpanishClient.
type stand struct {
	mu    sync.Mutex
	hits  map[string][]string // region -> raw queries
	files map[string]string   // region -> fixture ("" = HTTP 503)
}

// newSpanishClient starts one Audible stand-in per region; each answers with its fixture ("" =
// HTTP 503) and records the query strings it saw.
func newSpanishClient(t *testing.T, files map[string]string) (*Client, *stand) {
	t.Helper()
	st := &stand{hits: map[string][]string{}, files: files}
	urls := map[string]string{}
	for region := range files {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			st.mu.Lock()
			st.hits[region] = append(st.hits[region], r.URL.RawQuery)
			st.mu.Unlock()
			if st.files[region] == "" {
				w.WriteHeader(http.StatusServiceUnavailable)
				return
			}
			serveJSON(fixture(t, st.files[region]))(w, r)
		}))
		t.Cleanup(srv.Close)
		urls[region] = srv.URL
	}
	return &Client{AudibleRegionURLs: urls, HTTP: http.DefaultClient}, st
}

func TestSearchSpanishAsksBothMarketplaces(t *testing.T) {
	tests := []struct {
		name     string
		region   string
		language string
		files    map[string]string
		want     []string // "asin@region"
		asked    []string // regions that must have been asked
	}{
		{"us user, spanish: us + es interleaved, english dropped", "us", "es",
			map[string]string{"us": "audible_search_spanish_com.json", "es": "audible_search_spanish_es.json"},
			[]string{"B00RDN6DO4@us", "1481518429@es", "8439735995@us", "B0G52QVYRY@es", "B00SX7KDRC@us", "B08KFM36QZ@es", "B0DJWRQ55C@es"},
			[]string{"us", "es"}},
		{"nl user maps to us, spanish still adds es", "nl", "es",
			map[string]string{"us": "audible_search_spanish_com.json", "es": "audible_search_spanish_es.json"},
			[]string{"B00RDN6DO4@us", "1481518429@es", "8439735995@us", "B0G52QVYRY@es", "B00SX7KDRC@us", "B08KFM36QZ@es", "B0DJWRQ55C@es"},
			[]string{"us", "es"}},
		{"es user, spanish: one marketplace", "es", "es",
			map[string]string{"es": "audible_search_spanish_es.json"},
			[]string{"1481518429@es", "B0G52QVYRY@es", "B08KFM36QZ@es", "B0DJWRQ55C@es"},
			[]string{"es"}},
		{"us user, english: es is not asked", "us", "en",
			map[string]string{"us": "audible_search_spanish_com.json"},
			[]string{"B002V5IRZC@us", "B00EF8DG68@us"},
			[]string{"us"}},
		{"duplicate asin across marketplaces appears once", "us", "es",
			map[string]string{"us": "audible_search_spanish_es.json", "es": "audible_search_spanish_es.json"},
			[]string{"1481518429@us", "B0G52QVYRY@us", "B08KFM36QZ@us", "B0DJWRQ55C@us"},
			[]string{"us", "es"}},
		{"es marketplace down: us results only", "us", "es",
			map[string]string{"us": "audible_search_spanish_com.json", "es": ""},
			[]string{"B00RDN6DO4@us", "8439735995@us", "B00SX7KDRC@us"},
			[]string{"us", "es"}},
		{"us marketplace down: es results only", "us", "es",
			map[string]string{"us": "", "es": "audible_search_spanish_es.json"},
			[]string{"1481518429@es", "B0G52QVYRY@es", "B08KFM36QZ@es", "B0DJWRQ55C@es"},
			[]string{"us", "es"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			c, st := newSpanishClient(t, tt.files)
			books, err := c.Search(context.Background(), tt.region, tt.language, "cien años de soledad")
			if err != nil {
				t.Fatal(err)
			}
			var got []string
			for _, b := range books {
				got = append(got, b.ASIN+"@"+b.Region)
				if b.Language != tt.language {
					t.Errorf("%s has language %q", b.ASIN, b.Language)
				}
			}
			if strings.Join(got, " ") != strings.Join(tt.want, " ") {
				t.Errorf("books = %v\nwant    %v", got, tt.want)
			}
			for _, r := range tt.asked {
				if len(st.hits[r]) != 1 || !strings.Contains(st.hits[r][0], "keywords=cien+a%C3%B1os+de+soledad") {
					t.Errorf("region %s queries = %v", r, st.hits[r])
				}
			}
			if len(st.hits) != len(tt.asked) {
				t.Errorf("asked %v, want only %v", st.hits, tt.asked)
			}
		})
	}
}

func TestSearchSpanishBothMarketplacesFail(t *testing.T) {
	c, _ := newSpanishClient(t, map[string]string{"us": "", "es": ""})
	_, err := c.Search(context.Background(), "us", "es", "cien años de soledad")
	if !errors.Is(err, ErrUpstream) {
		t.Fatalf("err = %v", err)
	}
}

func TestDiscoverSpanishSeedsNovelaOnBothMarketplaces(t *testing.T) {
	c, st := newSpanishClient(t, map[string]string{"us": "audible_search_spanish_com.json", "es": "audible_search_spanish_es.json"})
	books, err := c.Discover(context.Background(), "us", "es", 2)
	if err != nil {
		t.Fatal(err)
	}
	if len(books) != 7 {
		t.Errorf("got %d books", len(books))
	}
	for _, r := range []string{"us", "es"} {
		if len(st.hits[r]) != 1 {
			t.Fatalf("region %s hits = %v", r, st.hits[r])
		}
		for _, want := range []string{"keywords=novela", "products_sort_by=BestSellers", "page=2", "content_type=Product"} {
			if !strings.Contains(st.hits[r][0], want) {
				t.Errorf("%s query %q lacks %q", r, st.hits[r][0], want)
			}
		}
	}

	// English discover has no keyword and a single marketplace.
	c, st = newSpanishClient(t, map[string]string{"us": "audible_search_spanish_com.json"})
	if _, err := c.Discover(context.Background(), "us", "en", 0); err != nil {
		t.Fatal(err)
	}
	if q := st.hits["us"][0]; strings.Contains(q, "keywords=") {
		t.Errorf("english discover query %q has a keyword", q)
	}
}

// Category ids belong to one marketplace: a category listing never fans out.
func TestDiscoverCategorySpanishStaysInRegion(t *testing.T) {
	c, st := newSpanishClient(t, map[string]string{"us": "audible_search_spanish_com.json", "es": "audible_search_spanish_es.json"})
	if _, err := c.DiscoverCategory(context.Background(), "us", "es", "18574426011", 0); err != nil {
		t.Fatal(err)
	}
	if len(st.hits["es"]) != 0 || len(st.hits["us"]) != 1 || !strings.Contains(st.hits["us"][0], "category_id=18574426011") {
		t.Errorf("hits = %v", st.hits)
	}
}

// Audnexus knows a Spanish ASIN only under the region it was listed in (checked live
// 2026-11-25: 1481518429 answers region=es, 404 under us); a marketplace that does not carry an
// ASIN answers the catalog endpoint with a bare {asin} stub.
func TestBookSpanishASINFoundUnderEsFromUsRegion(t *testing.T) {
	var mu sync.Mutex
	var seen []string
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		seen = append(seen, r.URL.Path+"?"+r.URL.Query().Get("region"))
		mu.Unlock()
		switch {
		case r.URL.Path == "/books/1481518429" && r.URL.Query().Get("region") == "es":
			serveJSON(fixture(t, "audnexus_book_spanish_es.json"))(w, r)
		case strings.HasPrefix(r.URL.Path, "/books/"):
			w.WriteHeader(http.StatusNotFound)
			_, _ = w.Write(fixture(t, "audnexus_404.json"))
		default:
			serveJSON(fixture(t, "audible_product_stub.json"))(w, r)
		}
	})
	for _, region := range []string{"us", "nl"} {
		seen = nil
		b, err := c.Book(context.Background(), region, "1481518429")
		if err != nil {
			t.Fatal(err)
		}
		if b.Title != "Cien años de soledad" || b.Language != "es" || b.Region != "es" || b.Authors[0] != "Gabriel García Márquez" {
			t.Errorf("%s: book = %+v", region, b)
		}
		want := "/books/1481518429?us /1.0/catalog/products/1481518429? /books/1481518429?es"
		if got := strings.Join(seen, " "); got != want {
			t.Errorf("%s: requests = %s\nwant %s", region, got, want)
		}
	}
}

func TestBookEsRegionFallsBackToUs(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/books/B00RDN6DO4" && r.URL.Query().Get("region") == "us" {
			serveJSON(fixture(t, "audnexus_book_spanish_es.json"))(w, r)
			return
		}
		if strings.HasPrefix(r.URL.Path, "/books/") {
			http.NotFound(w, r)
			return
		}
		serveJSON(fixture(t, "audible_product_stub.json"))(w, r)
	})
	b, err := c.Book(context.Background(), "es", "B00RDN6DO4")
	if err != nil || b.Region != "us" {
		t.Fatalf("book = %+v, err = %v", b, err)
	}
}

// A real failure in the first region is not papered over by the fallback.
func TestBookFallbackOnlyOnNotFound(t *testing.T) {
	var calls int
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		calls++
		w.WriteHeader(http.StatusBadGateway)
	})
	if _, err := c.Book(context.Background(), "us", "1481518429"); !errors.Is(err, ErrUpstream) || calls != 1 {
		t.Fatalf("err = %v, calls = %d", err, calls)
	}
}

func TestBookStubIsNotABook(t *testing.T) {
	c, _ := newTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/books/") {
			http.NotFound(w, r)
			return
		}
		serveJSON(fixture(t, "audible_product_stub.json"))(w, r)
	})
	if _, err := c.Book(context.Background(), "us", "1481518429"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("err = %v", err)
	}
}

func TestMarketplaceRegionSpanish(t *testing.T) {
	for in, want := range map[string]string{"es": "es", "ES": "es", "nl": "us", "us": "us"} {
		if got, err := MarketplaceRegion(in); err != nil || got != want {
			t.Errorf("MarketplaceRegion(%q) = %q, %v", in, got, err)
		}
	}
}

func TestSearchRegions(t *testing.T) {
	for _, tt := range []struct {
		region, lang string
		want         string
	}{
		{"us", "es", "us es"}, {"es", "es", "es"}, {"us", "en", "us"}, {"us", "nl", "us"}, {"uk", "es", "uk es"}, {"us", "", "us"},
	} {
		if got := strings.Join(searchRegions(tt.region, tt.lang), " "); got != tt.want {
			t.Errorf("searchRegions(%q, %q) = %q, want %q", tt.region, tt.lang, got, tt.want)
		}
	}
}
