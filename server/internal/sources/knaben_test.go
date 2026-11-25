package sources

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/AresiusXP/earmark/server/internal/model"
)

func fixture(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func newKnaben(t *testing.T, h http.HandlerFunc) *Knaben {
	t.Helper()
	srv := httptest.NewServer(h)
	t.Cleanup(srv.Close)
	return &Knaben{BaseURL: srv.URL, HTTP: srv.Client()}
}

func TestKnabenSearchMapsLiveFixture(t *testing.T) {
	var got knabenRequest
	var method, ctype, ua string
	k := newKnaben(t, func(w http.ResponseWriter, r *http.Request) {
		method, ctype, ua = r.Method, r.Header.Get("Content-Type"), r.Header.Get("User-Agent")
		b, _ := io.ReadAll(r.Body)
		if err := json.Unmarshal(b, &got); err != nil {
			t.Error(err)
		}
		_, _ = w.Write(fixture(t, "knaben_dune.json"))
	})
	rels, err := k.Search(context.Background(), Query{Title: "Dune", Author: "Frank Herbert"})
	if err != nil {
		t.Fatal(err)
	}
	if method != http.MethodPost || ctype != "application/json" || !strings.HasPrefix(ua, "earmark/") {
		t.Errorf("method=%s ctype=%s ua=%s", method, ctype, ua)
	}
	want := knabenRequest{
		SearchType: "100%", SearchField: "title", Query: "Dune Herbert", Categories: []int{1003000},
		OrderBy: "seeders", OrderDirection: "desc", Size: 50, HideUnsafe: true,
	}
	if got.SearchType != want.SearchType || got.SearchField != want.SearchField || got.Query != want.Query ||
		len(got.Categories) != 1 || got.Categories[0] != 1003000 || got.OrderBy != "seeders" ||
		got.OrderDirection != "desc" || got.Size != 50 || !got.HideUnsafe {
		t.Errorf("request = %+v", got)
	}
	if len(rels) != 5 {
		t.Fatalf("got %d releases", len(rels))
	}
	r := rels[0]
	if r.InfoHash != "bb5ca4f00d22ea48662c26f155a166849099f510" || r.Seeders != 102 || r.Size != 3385141933 ||
		r.Source != "knaben" || r.Title != "Frank Herbert - Dune (Books 1-6)" {
		t.Errorf("release = %+v", r)
	}
	if !r.PublishedAt.Equal(time.Date(2021, 2, 27, 9, 36, 47, 0, time.UTC)) {
		t.Errorf("date = %v", r.PublishedAt)
	}
	if !strings.HasPrefix(r.Magnet, "magnet:?xt=urn:btih:bb5ca4f0") {
		t.Errorf("magnet = %q", r.Magnet)
	}
	for i := 1; i < len(rels); i++ {
		if rels[i].Seeders > rels[i-1].Seeders {
			t.Errorf("not sorted by seeders at %d", i)
		}
	}
}

func TestKnabenEdgeCases(t *testing.T) {
	k := newKnaben(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(fixture(t, "knaben_edge.json")) })
	rels, err := k.Search(context.Background(), Query{Title: "x"})
	if err != nil {
		t.Fatal(err)
	}
	byHash := map[string]model.Release{}
	for _, r := range rels {
		if len(r.InfoHash) != 40 || r.InfoHash != strings.ToLower(r.InfoHash) {
			t.Errorf("bad hash %q", r.InfoHash)
		}
		if _, dup := byHash[r.InfoHash]; dup {
			t.Errorf("duplicate hash %s", r.InfoHash)
		}
		byHash[r.InfoHash] = r
	}
	if len(rels) != 4 {
		t.Fatalf("want 4 releases, got %d: %+v", len(rels), rels)
	}

	a := byHash[strings.Repeat("a", 40)]
	if a.Seeders != 30 || a.Title != "Same hash, high seeders" || a.Size != 200 {
		t.Errorf("dedupe should keep highest seeders: %+v", a)
	}
	if !strings.Contains(a.Magnet, "xt=urn:btih:"+strings.Repeat("a", 40)) || !strings.Contains(a.Magnet, "dn=Same+hash%2C+high+seeders") ||
		!strings.Contains(a.Magnet, "&tr=udp%3A%2F%2Ftracker.opentrackr.org") {
		t.Errorf("built magnet = %q", a.Magnet)
	}

	b := byHash[strings.Repeat("b", 40)]
	// Trackers supplied by the index are never forwarded: the magnet is rebuilt with our list.
	if b.Title != "Hash only in magnet" || strings.Contains(b.Magnet, "example.org") ||
		!strings.Contains(b.Magnet, "tr=udp%3A%2F%2Ftracker.opentrackr.org") {
		t.Errorf("magnet-only: %+v", b)
	}
	if _, ok := byHash["0123456789abcdef0123456789abcdef01234567"]; !ok {
		t.Errorf("base32 btih not converted: %v", byHash)
	}
	c := byHash[strings.Repeat("c", 40)]
	if c.Seeders != 0 || c.Size != 0 || !c.PublishedAt.IsZero() {
		t.Errorf("null fields: %+v", c)
	}
	if _, ok := byHash[strings.Repeat("d", 40)]; ok {
		t.Error("release with empty title should be skipped")
	}
}

func TestKnabenRetriesWithTitleOnly(t *testing.T) {
	var queries []string
	k := newKnaben(t, func(w http.ResponseWriter, r *http.Request) {
		var req knabenRequest
		b, _ := io.ReadAll(r.Body)
		_ = json.Unmarshal(b, &req)
		queries = append(queries, req.Query)
		if req.Query == "Dune Herbert" {
			_, _ = w.Write(fixture(t, "knaben_empty.json"))
			return
		}
		_, _ = w.Write(fixture(t, "knaben_dune.json"))
	})
	rels, err := k.Search(context.Background(), Query{Title: "Dune!", Author: "Frank Herbert"})
	if err != nil {
		t.Fatal(err)
	}
	if len(rels) != 5 || len(queries) != 2 || queries[1] != "Dune" {
		t.Errorf("rels=%d queries=%v", len(rels), queries)
	}
}

func TestKnabenNoRetryWithoutAuthorAndEmptyTitle(t *testing.T) {
	var calls atomic.Int32
	k := newKnaben(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		_, _ = w.Write(fixture(t, "knaben_empty.json"))
	})
	rels, err := k.Search(context.Background(), Query{Title: "Nothing"})
	if err != nil || rels == nil || len(rels) != 0 || calls.Load() != 1 {
		t.Errorf("rels=%v err=%v calls=%d", rels, err, calls.Load())
	}
	rels, err = k.Search(context.Background(), Query{Title: " !! "})
	if err != nil || rels == nil || len(rels) != 0 || calls.Load() != 1 {
		t.Errorf("empty title: rels=%v err=%v calls=%d", rels, err, calls.Load())
	}
}

func TestKnabenStatusErrors(t *testing.T) {
	for status, want := range map[int]error{429: ErrRateLimited, 500: ErrUpstream, 503: ErrUpstream} {
		k := newKnaben(t, func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Retry-After", "3")
			w.WriteHeader(status)
		})
		_, err := k.Search(context.Background(), Query{Title: "Dune"})
		if !errors.Is(err, want) {
			t.Errorf("status %d: err = %v", status, err)
		}
		var he *HTTPError
		if !errors.As(err, &he) || he.Status != status || he.RetryAfter != 3*time.Second {
			t.Errorf("status %d: he = %+v", status, he)
		}
	}
}

func TestKnabenBadJSONAndSizeLimit(t *testing.T) {
	k := newKnaben(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write([]byte("<html>")) })
	if _, err := k.Search(context.Background(), Query{Title: "Dune"}); err == nil {
		t.Error("expected decode error")
	}
	k = newKnaben(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(fixture(t, "knaben_dune.json")) })
	k.MaxBodyBytes = 512
	if _, err := k.Search(context.Background(), Query{Title: "Dune"}); !errors.Is(err, ErrBodyTooLarge) {
		t.Errorf("err = %v", err)
	}
}

func TestKnabenContext(t *testing.T) {
	block := make(chan struct{})
	k := newKnaben(t, func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-block:
		case <-r.Context().Done():
		}
	})
	defer close(block)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := k.Search(ctx, Query{Title: "Dune"}); !errors.Is(err, context.Canceled) {
		t.Errorf("cancel: %v", err)
	}
	k.Timeout = 50 * time.Millisecond
	if _, err := k.Search(context.Background(), Query{Title: "Dune"}); !errors.Is(err, context.DeadlineExceeded) {
		t.Errorf("timeout: %v", err)
	}
}

func TestHashHelpers(t *testing.T) {
	const hex40 = "0123456789abcdef0123456789abcdef01234567"
	for in, want := range map[string]string{
		"0123456789ABCDEF0123456789ABCDEF01234567": hex40,
		"AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH":         hex40,
		"aerukz4jvpg66ajdivtytk6n54asgrlh":         hex40,
		"short":                                    "",
		strings.Repeat("z", 40):                    "",
		strings.Repeat("1", 32):                    "", // '1' is not base32
	} {
		if got := normalizeHash(in); got != want {
			t.Errorf("normalizeHash(%q) = %q, want %q", in, got, want)
		}
	}
	for in, want := range map[string]string{
		"magnet:?xt=urn:btih:AERUKZ4JVPG66AJDIVTYTK6N54ASGRLH&dn=x": hex40,
		"magnet:?dn=x&xt=urn:BTIH:" + strings.ToUpper(hex40):        hex40,
		"magnet:?xt=urn:sha1:" + hex40:                              "",
		"http://example.org/" + hex40:                               "",
	} {
		if got := HashFromMagnet(in); got != want {
			t.Errorf("HashFromMagnet(%q) = %q, want %q", in, got, want)
		}
	}
	if got := surnameOf("Frank Herbert"); got != "Herbert" {
		t.Errorf("surname = %q", got)
	}
	if got := surnameOf("  "); got != "" {
		t.Errorf("surname = %q", got)
	}
}

type fakeSource struct {
	name  string
	rels  []model.Release
	err   error
	delay time.Duration
	panic bool
	seen  *atomic.Int32
}

func (f fakeSource) Name() string { return f.name }
func (f fakeSource) Search(ctx context.Context, q Query) ([]model.Release, error) {
	if f.seen != nil {
		f.seen.Add(1)
	}
	if f.panic {
		panic("boom")
	}
	if f.delay > 0 {
		select {
		case <-time.After(f.delay):
		case <-ctx.Done():
			return nil, ctx.Err()
		}
	}
	return f.rels, f.err
}

func rel(hash string, seeders int, src string) model.Release {
	return model.Release{InfoHash: strings.Repeat(hash, 40), Seeders: seeders, Source: src, Title: src + hash}
}

func TestMultiSearchMergesAndSurvivesFailures(t *testing.T) {
	var n atomic.Int32
	srcs := []Source{
		fakeSource{name: "a", rels: []model.Release{rel("1", 5, "a"), rel("2", 9, "a")}, seen: &n},
		fakeSource{name: "b", rels: []model.Release{rel("1", 50, "b"), rel("3", 1, "b")}, seen: &n},
		fakeSource{name: "broken", err: &HTTPError{Source: "x", Status: 429}, seen: &n},
		fakeSource{name: "slow", delay: time.Minute, seen: &n},
		fakeSource{name: "panicky", panic: true, seen: &n},
		nil,
	}
	start := time.Now()
	rels, errs := multiSearch(context.Background(), srcs, Query{Title: "x"}, 100*time.Millisecond)
	if time.Since(start) > 5*time.Second {
		t.Error("slow source was not cut off")
	}
	if len(rels) != 3 {
		t.Fatalf("rels = %+v", rels)
	}
	if rels[0].Source != "b" || rels[0].Seeders != 50 || rels[1].Seeders != 9 || rels[2].Seeders != 1 {
		t.Errorf("merge/order wrong: %+v", rels)
	}
	if len(errs) != 3 {
		t.Fatalf("errs = %v", errs)
	}
	var rl, dl bool
	for _, e := range errs {
		rl = rl || errors.Is(e, ErrRateLimited)
		dl = dl || errors.Is(e, context.DeadlineExceeded)
	}
	if !rl || !dl {
		t.Errorf("errors not wrapped: %v", errs)
	}
	if n.Load() != 5 {
		t.Errorf("sources called = %d", n.Load())
	}
}

func TestMultiSearchEdge(t *testing.T) {
	rels, errs := MultiSearch(context.Background(), nil, Query{Title: "x"})
	if rels != nil && len(rels) != 0 || errs == nil || len(errs) != 0 {
		t.Errorf("rels=%v errs=%v", rels, errs)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, errs = MultiSearch(ctx, []Source{fakeSource{name: "s", delay: time.Minute}}, Query{Title: "x"})
	if len(errs) != 1 || !errors.Is(errs[0], context.Canceled) {
		t.Errorf("errs = %v", errs)
	}
}

func TestMultiSearchWithKnabenAndFailingKnaben(t *testing.T) {
	good := newKnaben(t, func(w http.ResponseWriter, r *http.Request) { _, _ = w.Write(fixture(t, "knaben_dune.json")) })
	bad := newKnaben(t, func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(502) })
	rels, errs := MultiSearch(context.Background(), []Source{good, bad}, Query{Title: "Dune"})
	if len(rels) != 5 || len(errs) != 1 || !errors.Is(errs[0], ErrUpstream) {
		t.Errorf("rels=%d errs=%v", len(rels), errs)
	}
}

var _ Source = (*Knaben)(nil)
