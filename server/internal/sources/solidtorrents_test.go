package sources

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type solidHarness struct {
	*SolidTorrents
	reqs      atomic.Int32
	mu        sync.Mutex
	clock     time.Time
	remaining int
	reset     string
	retry     string
	status    int
	lastQuery string
}

func (h *solidHarness) advance(d time.Duration) {
	h.mu.Lock()
	h.clock = h.clock.Add(d)
	h.mu.Unlock()
}

func newSolid(t *testing.T) *solidHarness {
	t.Helper()
	h := &solidHarness{clock: time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC), remaining: 150, status: 200}
	h.reset = "2026-11-17T00:00:00.000Z"
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h.reqs.Add(1)
		h.mu.Lock()
		defer h.mu.Unlock()
		h.lastQuery = r.URL.RawQuery
		w.Header().Set("X-RateLimit-Limit", "200")
		if h.remaining >= 0 {
			w.Header().Set("X-RateLimit-Remaining", strconv.Itoa(h.remaining))
		}
		if h.reset != "" {
			w.Header().Set("X-RateLimit-Reset", h.reset)
		}
		if h.retry != "" {
			w.Header().Set("Retry-After", h.retry)
		}
		if h.status != 200 {
			w.WriteHeader(h.status)
			return
		}
		_, _ = w.Write(fixture(t, "solid_dcc.json"))
	}))
	t.Cleanup(srv.Close)
	h.SolidTorrents = &SolidTorrents{BaseURL: srv.URL, HTTP: srv.Client()}
	h.now = func() time.Time { h.mu.Lock(); defer h.mu.Unlock(); return h.clock }
	return h
}

func TestSolidSearchMapsFixture(t *testing.T) {
	h := newSolid(t)
	rels, err := h.Search(context.Background(), Query{Title: "Dungeon Crawler Carl", Author: "Matt Dinniman"})
	if err != nil {
		t.Fatal(err)
	}
	if h.lastQuery != "q=dungeon+crawler+carl&sort=seeders" {
		t.Errorf("query = %q", h.lastQuery)
	}
	// Dropped: epub, bad hash; the lowercase duplicate keeps the higher seeders (uppercase one).
	if len(rels) != 2 {
		t.Fatalf("got %d releases: %+v", len(rels), rels)
	}
	a, b := rels[0], rels[1]
	if a.InfoHash != "8d93b5e58ca01b46ab704da83f89a4cac90fe396" || a.Seeders != 253 || a.Size != 1169324208 ||
		a.Source != "solidtorrents" || a.Title != "A Parade of Horribles - Dungeon Crawler Carl, Book 8.m4b" {
		t.Errorf("first = %+v", a)
	}
	if !a.PublishedAt.Equal(time.Date(2026, 5, 22, 20, 24, 56, 687000000, time.UTC)) {
		t.Errorf("published = %v", a.PublishedAt)
	}
	if b.InfoHash != "7e0636e1b0a6e32955082b37f3db10d6a953a5a3" || !b.PublishedAt.IsZero() {
		t.Errorf("second (createdAt null) = %+v", b)
	}
	if !strings.HasPrefix(a.Magnet, "magnet:?xt=urn:btih:8d93b5e5") || HashFromMagnet(a.Magnet) != a.InfoHash {
		t.Errorf("magnet = %q", a.Magnet)
	}
}

func TestSolidShortTitleSkipsRequest(t *testing.T) {
	h := newSolid(t)
	rels, err := h.Search(context.Background(), Query{Title: "x"})
	if err != nil || rels == nil || len(rels) != 0 || h.reqs.Load() != 0 {
		t.Fatalf("%v %v %d", rels, err, h.reqs.Load())
	}
}

func TestSolidCacheSixHours(t *testing.T) {
	h := newSolid(t)
	for i := 0; i < 3; i++ {
		if _, err := h.Search(context.Background(), Query{Title: "Dungeon Crawler Carl"}); err != nil {
			t.Fatal(err)
		}
	}
	if h.reqs.Load() != 1 {
		t.Fatalf("%d requests, want 1", h.reqs.Load())
	}
	h.advance(5*time.Hour + 59*time.Minute)
	_, _ = h.Search(context.Background(), Query{Title: "Dungeon Crawler Carl"})
	if h.reqs.Load() != 1 {
		t.Fatalf("%d requests after 5h59m, want 1", h.reqs.Load())
	}
	h.advance(2 * time.Minute)
	_, _ = h.Search(context.Background(), Query{Title: "Dungeon Crawler Carl"})
	if h.reqs.Load() != 2 {
		t.Fatalf("%d requests after 6h01m, want 2", h.reqs.Load())
	}
}

func TestSolidStopsAtTwentyRemaining(t *testing.T) {
	h := newSolid(t)
	ctx := context.Background()

	h.remaining = 21
	if _, err := h.Search(ctx, Query{Title: "one book"}); err != nil {
		t.Fatal(err)
	}
	h.remaining = 20 // this answer still counts, then the source backs off
	if _, err := h.Search(ctx, Query{Title: "two book"}); err != nil {
		t.Fatal(err)
	}
	if h.reqs.Load() != 2 {
		t.Fatalf("%d requests", h.reqs.Load())
	}

	_, err := h.Search(ctx, Query{Title: "three book"})
	if !errors.Is(err, ErrRateLimited) {
		t.Fatalf("err = %v, want ErrRateLimited", err)
	}
	var he *HTTPError
	if !errors.As(err, &he) || he.RetryAfter != 12*time.Hour {
		t.Errorf("RetryAfter = %v", he)
	}
	if h.reqs.Load() != 2 {
		t.Fatal("a request was made during the cooldown")
	}
	// cached queries still answer during the cooldown
	if _, err := h.Search(ctx, Query{Title: "two book"}); err != nil {
		t.Errorf("cached query during cooldown: %v", err)
	}

	h.advance(11*time.Hour + 59*time.Minute)
	if _, err := h.Search(ctx, Query{Title: "three book"}); !errors.Is(err, ErrRateLimited) {
		t.Fatalf("one minute before reset: %v", err)
	}
	h.advance(2 * time.Minute)
	h.remaining = 199
	if _, err := h.Search(ctx, Query{Title: "three book"}); err != nil {
		t.Fatalf("after reset: %v", err)
	}
	if h.reqs.Load() != 3 {
		t.Fatalf("%d requests", h.reqs.Load())
	}
	if _, err := h.Search(ctx, Query{Title: "four book"}); err != nil {
		t.Fatalf("budget restored: %v", err)
	}
}

func TestSolid429(t *testing.T) {
	h := newSolid(t)
	ctx := context.Background()
	h.status, h.remaining = 429, 0
	_, err := h.Search(ctx, Query{Title: "dune"})
	if !errors.Is(err, ErrRateLimited) {
		t.Fatalf("err = %v", err)
	}
	var he *HTTPError
	if !errors.As(err, &he) || he.RetryAfter != 12*time.Hour {
		t.Errorf("RetryAfter = %+v", he)
	}
	h.status = 200
	if _, err := h.Search(ctx, Query{Title: "hobbit"}); !errors.Is(err, ErrRateLimited) {
		t.Fatalf("during cooldown: %v", err)
	}
	if h.reqs.Load() != 1 {
		t.Fatalf("%d requests, want 1", h.reqs.Load())
	}
}

func TestSolid429FallbackOneHour(t *testing.T) {
	h := newSolid(t)
	ctx := context.Background()
	h.status, h.remaining, h.reset = 429, -1, "" // no headers at all
	if _, err := h.Search(ctx, Query{Title: "dune"}); !errors.Is(err, ErrRateLimited) {
		t.Fatal(err)
	}
	h.status = 200
	h.advance(59 * time.Minute)
	if _, err := h.Search(ctx, Query{Title: "dune"}); !errors.Is(err, ErrRateLimited) {
		t.Fatalf("at 59 min: %v", err)
	}
	h.advance(2 * time.Minute)
	if _, err := h.Search(ctx, Query{Title: "dune"}); err != nil {
		t.Fatalf("at 61 min: %v", err)
	}
}

func TestSolidLowRemainingWithoutResetUsesFallback(t *testing.T) {
	h := newSolid(t)
	h.remaining, h.reset = 5, "garbage"
	if _, err := h.Search(context.Background(), Query{Title: "dune"}); err != nil {
		t.Fatal(err)
	}
	_, err := h.Search(context.Background(), Query{Title: "hobbit"})
	var he *HTTPError
	if !errors.As(err, &he) || he.RetryAfter != time.Hour {
		t.Fatalf("err = %v", err)
	}
}

func TestParseRateLimitReset(t *testing.T) {
	now := time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC)
	for _, tc := range []struct {
		in   string
		want time.Time
		ok   bool
	}{
		{"2026-11-17T00:00:00.000Z", time.Date(2026, 11, 17, 0, 0, 0, 0, time.UTC), true},
		{"2026-11-17T02:00:00+02:00", time.Date(2026, 11, 17, 0, 0, 0, 0, time.UTC), true},
		{"1794009600", time.Unix(1794009600, 0), true},
		{"1794009600000", time.UnixMilli(1794009600000), true},
		{"900", now.Add(900 * time.Second), true},
		{"", time.Time{}, false},
		{"soon", time.Time{}, false},
		{"-5", time.Time{}, false},
	} {
		got, ok := parseRateLimitReset(tc.in, now)
		if ok != tc.ok || (ok && !got.Equal(tc.want)) {
			t.Errorf("%q = %v, %v; want %v, %v", tc.in, got, ok, tc.want, tc.ok)
		}
	}
}

func TestSolidErrors(t *testing.T) {
	for _, tc := range []struct {
		name   string
		status int
		body   string
		max    int64
		want   error
	}{
		{"503", 503, "", 0, ErrUpstream},
		{"success false", 200, `{"success":false,"results":[]}`, 0, ErrUpstream},
		{"invalid json", 200, "<html>", 0, ErrInvalidResponse},
		{"empty object", 200, `{}`, 0, ErrInvalidResponse},
		{"no results key", 200, `{"success":true}`, 0, ErrInvalidResponse},
		{"null results", 200, `{"success":true,"results":null}`, 0, ErrInvalidResponse},
		{"no success key", 200, `{"results":[]}`, 0, ErrInvalidResponse},
		{"success false without results", 200, `{"success":false}`, 0, ErrUpstream},
		{"too large", 200, `{"success":true,"results":[]}` + strings.Repeat(" ", 100), 50, ErrBodyTooLarge},
	} {
		var n atomic.Int32
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			n.Add(1)
			w.WriteHeader(tc.status)
			_, _ = w.Write([]byte(tc.body))
		}))
		s := &SolidTorrents{BaseURL: srv.URL, HTTP: srv.Client(), MaxBodyBytes: tc.max}
		_, err := s.Search(context.Background(), Query{Title: "dune"})
		if !errors.Is(err, tc.want) {
			t.Errorf("%s: err = %v, want %v", tc.name, err, tc.want)
		}
		if errors.Is(err, ErrRateLimited) {
			t.Errorf("%s: must not be ErrRateLimited", tc.name)
		}
		_, _ = s.Search(context.Background(), Query{Title: "dune"})
		if n.Load() != 2 {
			t.Errorf("%s: %d requests, want 2 (errors are not cached or throttled)", tc.name, n.Load())
		}
		srv.Close()
	}
}

func TestSolidContextCancel(t *testing.T) {
	release := make(chan struct{})
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-release:
		case <-r.Context().Done():
		}
	}))
	defer srv.Close()
	defer close(release)
	s := &SolidTorrents{BaseURL: srv.URL, HTTP: srv.Client()}
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(50 * time.Millisecond); cancel() }()
	start := time.Now()
	if _, err := s.Search(ctx, Query{Title: "dune"}); !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v", err)
	}
	if time.Since(start) > 2*time.Second {
		t.Error("cancel was not prompt")
	}
}

func TestSolidRefusesForeignRedirect(t *testing.T) {
	var foreign atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		foreign.Add(1)
		_, _ = w.Write([]byte(`{"success":true,"results":[]}`))
	}))
	defer other.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, other.URL+r.URL.RequestURI(), http.StatusMovedPermanently)
	}))
	defer srv.Close()
	s := &SolidTorrents{BaseURL: srv.URL}
	if _, err := s.Search(context.Background(), Query{Title: "dune"}); err == nil {
		t.Fatal("expected an error")
	}
	if foreign.Load() != 0 {
		t.Error("foreign host was contacted")
	}
}

func TestSolidName(t *testing.T) {
	var s Source = NewSolidTorrents()
	if s.Name() != "solidtorrents" {
		t.Error(s.Name())
	}
}

func TestSolidEmptyResultsIsValidAndCached(t *testing.T) {
	var n atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		n.Add(1)
		_, _ = w.Write([]byte(`{"success":true,"results":[]}`))
	}))
	defer srv.Close()
	s := &SolidTorrents{BaseURL: srv.URL, HTTP: srv.Client()}
	for i := 0; i < 2; i++ {
		rels, err := s.Search(context.Background(), Query{Title: "nothing here"})
		if err != nil || rels == nil || len(rels) != 0 {
			t.Fatalf("%v %v", rels, err)
		}
	}
	if n.Load() != 1 {
		t.Errorf("%d requests, want 1", n.Load())
	}
}

func TestSolid429KeepsLongerOfRetryAfterAndReset(t *testing.T) {
	for _, tc := range []struct {
		name, retry, reset string
		want               time.Duration
	}{
		{"retry-after longer", "172800", "2026-11-17T00:00:00.000Z", 48 * time.Hour},
		{"reset longer", "60", "2026-11-17T00:00:00.000Z", 12 * time.Hour},
		{"retry-after only", "300", "", 5 * time.Minute},
		{"reset only", "", "2026-11-17T00:00:00.000Z", 12 * time.Hour},
		{"neither: 1h fallback", "", "", time.Hour},
	} {
		h := newSolid(t)
		h.status, h.remaining, h.retry, h.reset = 429, -1, tc.retry, tc.reset
		_, err := h.Search(context.Background(), Query{Title: "dune"})
		var he *HTTPError
		if !errors.Is(err, ErrRateLimited) || !errors.As(err, &he) || he.RetryAfter != tc.want {
			t.Errorf("%s: err = %v (%+v), want RetryAfter %v", tc.name, err, he, tc.want)
			continue
		}
		// the cooldown itself lasts as long
		h.status = 200
		h.advance(tc.want - time.Minute)
		if _, err := h.Search(context.Background(), Query{Title: "hobbit"}); !errors.Is(err, ErrRateLimited) {
			t.Errorf("%s: one minute early: %v", tc.name, err)
		}
		h.advance(2 * time.Minute)
		if _, err := h.Search(context.Background(), Query{Title: "hobbit"}); err != nil {
			t.Errorf("%s: after cooldown: %v", tc.name, err)
		}
	}
}

func TestSolidConcurrentDistinctQueriesStayWithinBudget(t *testing.T) {
	var inflight, maxInflight, total atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		cur := inflight.Add(1)
		defer inflight.Add(-1)
		for {
			m := maxInflight.Load()
			if cur <= m || maxInflight.CompareAndSwap(m, cur) {
				break
			}
		}
		total.Add(1)
		time.Sleep(10 * time.Millisecond)
		_, _ = w.Write([]byte(`{"success":true,"results":[]}`)) // no rate-limit headers
	}))
	defer srv.Close()
	// limit 26 -> at most 6 requests may be issued (limit - 20)
	s := &SolidTorrents{BaseURL: srv.URL, HTTP: srv.Client(), limit: 26}

	const callers = 8 // one in flight + seven queued: within the queue bound
	var wg sync.WaitGroup
	var ok, limited atomic.Int32
	for i := 0; i < callers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, err := s.Search(context.Background(), Query{Title: fmt.Sprintf("book number %d", i)})
			switch {
			case err == nil:
				ok.Add(1)
			case errors.Is(err, ErrRateLimited):
				limited.Add(1)
			default:
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if total.Load() != 6 || ok.Load() != 6 || limited.Load() != 2 {
		t.Errorf("requests=%d ok=%d limited=%d, want 6/6/2", total.Load(), ok.Load(), limited.Load())
	}
	if maxInflight.Load() != 1 {
		t.Errorf("max in flight = %d, want 1", maxInflight.Load())
	}
	// the budget is spent: further misses do not reach the site
	if _, err := s.Search(context.Background(), Query{Title: "one more"}); !errors.Is(err, ErrRateLimited) || total.Load() != 6 {
		t.Errorf("err = %v, requests = %d", err, total.Load())
	}
}

func TestSolidDefaultLimitIs200(t *testing.T) {
	s := NewSolidTorrents()
	s.setup()
	if s.limit != 200 {
		t.Fatalf("limit = %d", s.limit)
	}
	now := time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC)
	s.now = func() time.Time { return now }
	for i := 0; i < 180; i++ {
		if err := s.reserve(); err != nil {
			t.Fatalf("request %d refused: %v", i+1, err)
		}
	}
	if err := s.reserve(); !errors.Is(err, ErrRateLimited) {
		t.Fatalf("181st request: %v", err)
	}
	now = now.Add(24*time.Hour + time.Minute)
	if err := s.reserve(); err != nil {
		t.Fatalf("after the window: %v", err)
	}
}

func TestSolidQueueBoundFailsFast(t *testing.T) {
	gate := make(chan struct{})
	var total atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		total.Add(1)
		<-gate
		_, _ = w.Write([]byte(`{"success":true,"results":[]}`))
	}))
	defer srv.Close()
	s := &SolidTorrents{BaseURL: srv.URL, HTTP: srv.Client()}

	const callers = 20
	type res struct{ err error }
	done := make(chan res, callers)
	for i := 0; i < callers; i++ {
		go func() {
			_, err := s.Search(context.Background(), Query{Title: fmt.Sprintf("book number %d", i)})
			done <- res{err}
		}()
	}
	// 1 in flight + 8 waiting are admitted; the other 11 must fail without waiting for the gate.
	var rejected int
	for rejected < callers-1-8 {
		select {
		case r := <-done:
			if !errors.Is(r.err, ErrRateLimited) {
				t.Fatalf("early answer: %v", r.err)
			}
			rejected++
		case <-time.After(5 * time.Second):
			t.Fatalf("only %d callers failed fast", rejected)
		}
	}
	select {
	case r := <-done:
		t.Fatalf("an admitted caller finished before the gate opened: %v", r.err)
	case <-time.After(50 * time.Millisecond):
	}
	close(gate)
	for i := 0; i < 9; i++ {
		if r := <-done; r.err != nil {
			t.Errorf("admitted caller failed: %v", r.err)
		}
	}
	if total.Load() != 9 {
		t.Errorf("%d requests, want 9", total.Load())
	}
}

func TestSolidQueuedCallersStopAfter429(t *testing.T) {
	gate := make(chan struct{})
	var total atomic.Int32
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		total.Add(1)
		<-gate
		w.Header().Set("X-RateLimit-Reset", time.Now().Add(time.Hour).UTC().Format(time.RFC3339))
		w.WriteHeader(http.StatusTooManyRequests)
	}))
	defer srv.Close()
	s := &SolidTorrents{BaseURL: srv.URL, HTTP: srv.Client()}
	s.setup()

	errs := make(chan error, 5)
	for i := 0; i < 5; i++ {
		go func() {
			_, err := s.Search(context.Background(), Query{Title: fmt.Sprintf("book number %d", i)})
			errs <- err
		}()
	}
	for len(s.slot) == 0 || s.queue.n.Load() < 4 { // one in flight, four queued
		time.Sleep(time.Millisecond)
	}
	close(gate)
	for i := 0; i < 5; i++ {
		if err := <-errs; !errors.Is(err, ErrRateLimited) {
			t.Errorf("err = %v", err)
		}
	}
	if total.Load() != 1 {
		t.Errorf("%d requests, want 1: queued callers fired after the 429", total.Load())
	}
}

func TestSolidCapsStoredReleases(t *testing.T) {
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var b strings.Builder
		b.WriteString(`{"success":true,"results":[`)
		for i := 1; i <= 300; i++ {
			if i > 1 {
				b.WriteString(",")
			}
			fmt.Fprintf(&b, `{"infohash":"%040X","title":"Book %d","size":104857600,"seeders":%d}`, i, i, i)
		}
		b.WriteString(`]}`)
		_, _ = w.Write([]byte(b.String()))
	}))
	defer srv.Close()
	s := &SolidTorrents{BaseURL: srv.URL, HTTP: srv.Client()}
	rels, err := s.Search(context.Background(), Query{Title: "book"})
	if err != nil {
		t.Fatal(err)
	}
	if len(rels) != 200 || rels[0].Seeders != 300 || rels[199].Seeders != 101 {
		t.Fatalf("len=%d first=%d last=%d", len(rels), rels[0].Seeders, rels[len(rels)-1].Seeders)
	}
}
