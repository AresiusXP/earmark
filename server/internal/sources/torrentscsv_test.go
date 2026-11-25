package sources

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func newTorrentsCSV(t *testing.T, h http.HandlerFunc) *TorrentsCSV {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return &TorrentsCSV{BaseURL: srv.URL, HTTP: srv.Client()}
}

func TestTorrentsCSVSearchMapsFixture(t *testing.T) {
	var path, rawQuery, ua string
	tc := newTorrentsCSV(t, func(w http.ResponseWriter, r *http.Request) {
		path, rawQuery, ua = r.URL.Path, r.URL.RawQuery, r.Header.Get("User-Agent")
		_, _ = w.Write(fixture(t, "torrentscsv_dcc.json"))
	})
	rels, err := tc.Search(context.Background(), Query{Title: "Dungeon Crawler Carl", Author: "Matt Dinniman"})
	if err != nil {
		t.Fatal(err)
	}
	if path != "/service/search" || rawQuery != "q=dungeon+crawler+carl&size=50" || !strings.HasPrefix(ua, "earmark/") {
		t.Errorf("path=%q query=%q ua=%q", path, rawQuery, ua)
	}
	// Dropped: EPUB, S01E02/1080p, bad hash, 5 MB sample; the duplicate keeps the higher seeders.
	if len(rels) != 2 {
		t.Fatalf("got %d releases: %+v", len(rels), rels)
	}
	a, b := rels[0], rels[1]
	if a.InfoHash != "549152bb35b0eab529d6e6d69fba88e7b373f5f5" || a.Seeders != 481 || a.Size != 8347549512 || a.Source != "torrents-csv" {
		t.Errorf("first = %+v", a)
	}
	if b.InfoHash != "92e715f6b0a3c0f5a7d8e1c2b3a49586776655aa" || b.Seeders != 149 || b.Title != "Dungeon Crawler Carl 1 - Matt Dinniman (Jeff Hays) [M4B]" {
		t.Errorf("second = %+v", b)
	}
	if !b.PublishedAt.Equal(time.Unix(1790830045, 0)) {
		t.Errorf("published = %v", b.PublishedAt)
	}
	if !strings.HasPrefix(b.Magnet, "magnet:?xt=urn:btih:92e715f6b0a3c0f5a7d8e1c2b3a49586776655aa&dn=") {
		t.Errorf("magnet = %q", b.Magnet)
	}
	if HashFromMagnet(b.Magnet) != b.InfoHash {
		t.Error("magnet hash mismatch")
	}
}

func TestTorrentsCSVQueryIsTitleOnly(t *testing.T) {
	var got string
	tc := newTorrentsCSV(t, func(w http.ResponseWriter, r *http.Request) {
		got = r.URL.Query().Get("q")
		_, _ = w.Write([]byte(`{"torrents":[]}`))
	})
	rels, err := tc.Search(context.Background(), Query{Title: "Carl's “Quest”: Part-1!", Author: "Matt Dinniman"})
	if err != nil || rels == nil || len(rels) != 0 {
		t.Fatalf("%v %v", rels, err)
	}
	if got != "carls quest part 1" {
		t.Errorf("q = %q", got)
	}
}

func TestTorrentsCSVShortTitleSkipsRequest(t *testing.T) {
	var n atomic.Int32
	tc := newTorrentsCSV(t, func(w http.ResponseWriter, r *http.Request) { n.Add(1) })
	for _, title := range []string{"", "a", "!!", " ' "} {
		rels, err := tc.Search(context.Background(), Query{Title: title})
		if err != nil || rels == nil || len(rels) != 0 {
			t.Errorf("%q: %v %v", title, rels, err)
		}
	}
	if n.Load() != 0 {
		t.Errorf("%d requests", n.Load())
	}
}

func TestTorrentsCSVCache(t *testing.T) {
	var n atomic.Int32
	tc := newTorrentsCSV(t, func(w http.ResponseWriter, r *http.Request) {
		n.Add(1)
		_, _ = w.Write(fixture(t, "torrentscsv_dcc.json"))
	})
	clock := time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC)
	tc.now = func() time.Time { return clock }
	first, err := tc.Search(context.Background(), Query{Title: "Dungeon Crawler Carl"})
	if err != nil {
		t.Fatal(err)
	}
	first[0].Parsed.Title = "mutated by the caller"
	again, err := tc.Search(context.Background(), Query{Title: "dungeon  crawler CARL!", Author: "other"})
	if err != nil {
		t.Fatal(err)
	}
	if n.Load() != 1 {
		t.Fatalf("%d requests, want 1 (cache hit)", n.Load())
	}
	if again[0].Parsed.Title != "" {
		t.Error("cache handed out shared slice")
	}
	if _, err := tc.Search(context.Background(), Query{Title: "The Hobbit"}); err != nil {
		t.Fatal(err)
	}
	if n.Load() != 2 {
		t.Fatalf("%d requests, want 2 (other query)", n.Load())
	}
	clock = clock.Add(31 * time.Minute)
	if _, err := tc.Search(context.Background(), Query{Title: "Dungeon Crawler Carl"}); err != nil {
		t.Fatal(err)
	}
	if n.Load() != 3 {
		t.Fatalf("%d requests, want 3 (expired)", n.Load())
	}
}

func TestTorrentsCSVErrors(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		max    int64
		want   error
	}{
		{"429", 429, "", 0, ErrRateLimited},
		{"503", 503, "", 0, ErrUpstream},
		{"500", 500, "", 0, ErrUpstream},
		{"invalid json", 200, "<html>nope", 0, ErrInvalidResponse},
		{"empty object", 200, `{}`, 0, ErrInvalidResponse},
		{"null torrents", 200, `{"torrents":null}`, 0, ErrInvalidResponse},
		{"torrents not an array", 200, `{"torrents":"x"}`, 0, ErrInvalidResponse},
		{"too large", 200, `{"torrents":[]}` + strings.Repeat(" ", 100), 50, ErrBodyTooLarge},
	} {
		var n atomic.Int32
		src := newTorrentsCSV(t, func(w http.ResponseWriter, r *http.Request) {
			n.Add(1)
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(tc.body))
		})
		src.MaxBodyBytes = tc.max
		_, err := src.Search(context.Background(), Query{Title: "Dune"})
		if !errors.Is(err, tc.want) {
			t.Errorf("%s: err = %v, want %v", tc.name, err, tc.want)
		}
		// failures are not cached
		_, _ = src.Search(context.Background(), Query{Title: "Dune"})
		if n.Load() != 2 {
			t.Errorf("%s: %d requests, want 2", tc.name, n.Load())
		}
	}
}

func TestTorrentsCSVContextCancel(t *testing.T) {
	release := make(chan struct{})
	tc := newTorrentsCSV(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-release:
		case <-r.Context().Done():
		}
	})
	defer close(release)
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(50 * time.Millisecond); cancel() }()
	start := time.Now()
	_, err := tc.Search(ctx, Query{Title: "Dune"})
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Error("cancel was not prompt")
	}
}

func TestTorrentsCSVRefusesForeignRedirect(t *testing.T) {
	var foreign atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		foreign.Add(1)
		_, _ = w.Write([]byte(`{"torrents":[]}`))
	}))
	defer other.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL+r.URL.RequestURI(), http.StatusMovedPermanently)
	}))
	defer srv.Close()
	tc := &TorrentsCSV{BaseURL: srv.URL} // default client: host-pinned
	if _, err := tc.Search(context.Background(), Query{Title: "Dune"}); err == nil {
		t.Fatal("expected an error")
	}
	if foreign.Load() != 0 {
		t.Error("foreign host was contacted")
	}
}

func TestTorrentsCSVName(t *testing.T) {
	var s Source = NewTorrentsCSV()
	if s.Name() != "torrents-csv" {
		t.Error(s.Name())
	}
}

func TestTorrentsCSVEmptyArrayIsValidAndCached(t *testing.T) {
	var n atomic.Int32
	tc := newTorrentsCSV(t, func(w http.ResponseWriter, r *http.Request) {
		n.Add(1)
		_, _ = w.Write([]byte(`{"torrents":[]}`))
	})
	for i := 0; i < 2; i++ {
		rels, err := tc.Search(context.Background(), Query{Title: "nothing here"})
		if err != nil || rels == nil || len(rels) != 0 {
			t.Fatalf("%v %v", rels, err)
		}
	}
	if n.Load() != 1 {
		t.Errorf("%d requests, want 1", n.Load())
	}
}

func TestTorrentsCSVCapsStoredReleases(t *testing.T) {
	tc := newTorrentsCSV(t, func(w http.ResponseWriter, r *http.Request) {
		var b strings.Builder
		b.WriteString(`{"torrents":[`)
		for i := 1; i <= 300; i++ {
			if i > 1 {
				b.WriteString(",")
			}
			fmt.Fprintf(&b, `{"infohash":"%040x","name":"Book %d","size_bytes":104857600,"seeders":%d}`, i, i, i)
		}
		b.WriteString(`]}`)
		_, _ = w.Write([]byte(b.String()))
	})
	rels, err := tc.Search(context.Background(), Query{Title: "book"})
	if err != nil {
		t.Fatal(err)
	}
	if len(rels) != 200 || rels[0].Seeders != 300 || rels[199].Seeders != 101 {
		t.Fatalf("len=%d first=%d last=%d", len(rels), rels[0].Seeders, rels[len(rels)-1].Seeders)
	}
}

func TestTorrentsCSVQueueBoundFailsFastAndWaitRespectsContext(t *testing.T) {
	var total atomic.Int32
	tc := newTorrentsCSV(t, func(w http.ResponseWriter, r *http.Request) {
		total.Add(1)
		_, _ = w.Write([]byte(`{"torrents":[]}`))
	})
	tc.setup()
	tc.lim = newRateLimiter(0.001, 1, time.Now) // one request, then a very long wait

	const callers = 20
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	var wg sync.WaitGroup
	var limited, canceled, ok atomic.Int32
	for i := 0; i < callers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := tc.Search(ctx, Query{Title: fmt.Sprintf("book number %d", i)})
			switch {
			case err == nil:
				ok.Add(1)
			case errors.Is(err, ErrRateLimited):
				limited.Add(1)
			case errors.Is(err, context.Canceled):
				canceled.Add(1)
			default:
				t.Error(err)
			}
		}()
	}
	// 1 passes the limiter; at most 8 wait (the passing one may still be counted); the rest fail fast.
	deadline := time.Now().Add(5 * time.Second)
	for (limited.Load() < callers-9 || ok.Load() < 1) && time.Now().Before(deadline) {
		time.Sleep(time.Millisecond)
	}
	if got := limited.Load(); got < callers-9 || got > callers-8 {
		t.Fatalf("%d callers failed fast, want %d or %d", got, callers-9, callers-8)
	}
	cancel() // waiters on the limiter give up promptly
	wg.Wait()
	if total.Load() != 1 || ok.Load() != 1 {
		t.Errorf("requests=%d ok=%d, want 1/1", total.Load(), ok.Load())
	}
	if ok.Load()+limited.Load()+canceled.Load() != callers {
		t.Errorf("ok=%d limited=%d canceled=%d", ok.Load(), limited.Load(), canceled.Load())
	}
}
