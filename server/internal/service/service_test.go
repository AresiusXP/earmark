package service

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/AresiusXP/earmark/server/internal/debrid"
	"github.com/AresiusXP/earmark/server/internal/metadata"
	"github.com/AresiusXP/earmark/server/internal/model"
	"github.com/AresiusXP/earmark/server/internal/sources"
	"github.com/AresiusXP/earmark/server/internal/userconfig"
)

const (
	testASIN = "B002V0QK4C"
	tbKey    = "tb-secret-KEY-123"
	rdKey    = "rd-secret-KEY-456"
)

func h(n int) string { return fmt.Sprintf("%040x", n) }

func dune() model.Book {
	return model.Book{ASIN: testASIN, Title: "Dune", Subtitle: "Book One", Authors: []string{"Frank Herbert"}, Language: "en", RuntimeMin: 1260, Region: "us"}
}

func duneSourceRels() []model.Release {
	return []model.Release{
		{InfoHash: strings.ToUpper(h(0xa1)), Title: "Frank Herbert - Dune (Unabridged) [m4b] 64kbps", Source: "fake", Size: 600 << 20, Seeders: 10},
		{InfoHash: h(2), Title: "Frank Herbert - Dune mp3 128 kbps", Source: "fake", Size: 600 << 20, Seeders: 20, Magnet: "magnet:?xt=urn:btih:" + h(2)},
		{InfoHash: h(3), Title: "Stephen King - The Stand mp3", Source: "fake", Size: 600 << 20, Seeders: 50},
		{InfoHash: "not-a-hash", Title: "Frank Herbert - Dune", Source: "fake"},
	}
}

type env struct {
	t     *testing.T
	clock *fakeClock
	store *fakeStore
	meta  *fakeMeta
	src   *fakeSource
	tb    *fakeProvider
	rd    *fakeProvider
	logs  *syncBuffer
	svc   *Service
}

func newEnv(t *testing.T, mut ...func(*env, *Deps)) *env {
	t.Helper()
	e := &env{t: t, clock: newClock(), meta: newFakeMeta(), logs: &syncBuffer{}}
	e.store = newFakeStore(e.clock.Now)
	e.src = &fakeSource{name: "fake", rels: duneSourceRels()}
	e.tb = &fakeProvider{name: "torbox", cached: map[string]debrid.Cached{}}
	e.rd = &fakeProvider{name: "realdebrid", noAPI: true}
	e.meta.books[testASIN] = dune()
	d := Deps{
		Metadata:  e.meta,
		Sources:   []sources.Source{e.src},
		Store:     e.store,
		Providers: debrid.NewRegistry(e.tb, e.rd),
		Clock:     e.clock.Now,
		Logger:    newLogger(e.logs),
	}
	for _, m := range mut {
		m(e, &d)
	}
	e.svc = New(d)
	return e
}

func (e *env) seedFresh(rels ...model.Release) {
	e.t.Helper()
	e.store.mu.Lock()
	defer e.store.mu.Unlock()
	e.store.books[testASIN] = BookRecord{Book: dune(), FetchedAt: e.clock.Now()}
	e.store.searched[testASIN] = e.clock.Now()
	var ms []Match
	for i, r := range rels {
		e.store.releases[r.InfoHash] = r
		ms = append(ms, Match{InfoHash: r.InfoHash, Score: 0.9 - float64(i)*0.01})
	}
	e.store.matches[testASIN] = ms
}

func cfgFor(mut func(*userconfig.Config), providers ...string) userconfig.Config {
	c := userconfig.Config{Debrid: map[string]string{}}
	for _, p := range providers {
		switch p {
		case "torbox":
			c.Debrid[p] = tbKey
		case "realdebrid":
			c.Debrid[p] = rdKey
		default:
			c.Debrid[p] = "key-" + p
		}
	}
	if mut != nil {
		mut(&c)
	}
	n, err := c.Normalize()
	if err != nil {
		panic(err)
	}
	return n
}

func hashesOfPage(p *BookPage) []string {
	out := []string{}
	for _, r := range p.Releases {
		out = append(out, r.Release.InfoHash)
	}
	return out
}

func eq(a, b []string) bool { return strings.Join(a, ",") == strings.Join(b, ",") }

func (e *env) mustBook(cfg userconfig.Config) *BookPage {
	e.t.Helper()
	p, err := e.svc.Book(context.Background(), cfg, testASIN)
	if err != nil {
		e.t.Fatalf("Book: %v", err)
	}
	return p
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timeout waiting for %s", what)
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// ---- Book: metadata -------------------------------------------------------------------------

func TestBookInvalidASIN(t *testing.T) {
	e := newEnv(t)
	for _, asin := range []string{"", "short", "b002v0qk4c", "B002V0QK4C1", "B002V0QK4!", "../../etc/pw"} {
		if _, err := e.svc.Book(context.Background(), cfgFor(nil), asin); !errors.Is(err, ErrNotFound) {
			t.Errorf("Book(%q) = %v, want ErrNotFound", asin, err)
		}
	}
	if e.meta.count("book") != 0 {
		t.Fatal("metadata must not be called for invalid ASINs")
	}
}

func TestBookMetadata(t *testing.T) {
	t.Run("unknown upstream is ErrNotFound", func(t *testing.T) {
		e := newEnv(t)
		_, err := e.svc.Book(context.Background(), cfgFor(nil), "B000000000")
		if !errors.Is(err, ErrNotFound) {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("upstream failure without record is an error", func(t *testing.T) {
		e := newEnv(t)
		e.meta.bookErr = metadata.ErrUpstream
		_, err := e.svc.Book(context.Background(), cfgFor(nil), testASIN)
		if err == nil || errors.Is(err, ErrNotFound) || !errors.Is(err, metadata.ErrUpstream) {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("fresh record is not refetched", func(t *testing.T) {
		e := newEnv(t)
		e.seedFresh()
		e.clock.Advance(29 * 24 * time.Hour)
		e.store.searched[testASIN] = e.clock.Now()
		if p := e.mustBook(cfgFor(nil)); p.Book.Title != "Dune" {
			t.Fatalf("book = %+v", p.Book)
		}
		if n := e.meta.count("book"); n != 0 {
			t.Fatalf("metadata calls = %d", n)
		}
	})
	t.Run("stale record is refetched and stored", func(t *testing.T) {
		e := newEnv(t)
		e.seedFresh()
		e.clock.Advance(31 * 24 * time.Hour)
		e.store.searched[testASIN] = e.clock.Now()
		nb := dune()
		nb.Title = "Dune (new)"
		e.meta.books[testASIN] = nb
		if p := e.mustBook(cfgFor(nil)); p.Book.Title != "Dune (new)" {
			t.Fatalf("book = %+v", p.Book)
		}
		if rec, _ := e.store.GetBook(context.Background(), testASIN); rec.Book.Title != "Dune (new)" || !rec.FetchedAt.Equal(e.clock.Now()) {
			t.Fatalf("stored = %+v", rec)
		}
	})
	t.Run("refresh failure serves stored record", func(t *testing.T) {
		for _, failure := range []error{metadata.ErrUpstream, metadata.ErrNotFound, metadata.ErrRateLimited} {
			e := newEnv(t)
			e.seedFresh()
			e.clock.Advance(31 * 24 * time.Hour)
			e.store.searched[testASIN] = e.clock.Now()
			e.meta.bookErr = failure
			if p := e.mustBook(cfgFor(nil)); p.Book.Title != "Dune" {
				t.Fatalf("book = %+v", p.Book)
			}
		}
	})
	t.Run("region is the marketplace region", func(t *testing.T) {
		e := newEnv(t)
		e.mustBook(cfgFor(func(c *userconfig.Config) { c.Region = "nl" }))
		if len(e.meta.regions) == 0 || e.meta.regions[0] != "us" {
			t.Fatalf("regions = %v", e.meta.regions)
		}
	})
}

// ---- Book: release search ------------------------------------------------------------------

func TestBookColdSearchesSources(t *testing.T) {
	e := newEnv(t)
	p := e.mustBook(cfgFor(nil))

	if e.meta.count("book") != 1 || e.src.calls() != 1 {
		t.Fatalf("meta=%d src=%d", e.meta.count("book"), e.src.calls())
	}
	q := e.src.queries[0]
	if q.Title != "Dune" || q.Author != "Frank Herbert" || q.Language != "" {
		t.Fatalf("query = %+v", q)
	}
	// m4b first (default format preference), then mp3; the Stand does not match.
	if got := hashesOfPage(p); !eq(got, []string{h(0xa1), h(2)}) {
		t.Fatalf("releases = %v", got)
	}
	for i, r := range p.Releases {
		if r.Season != i+1 || r.Score < 0.7 || r.Cached == nil {
			t.Fatalf("release %d = %+v", i, r)
		}
	}
	r1 := p.Releases[0].Release
	if r1.Parsed.Format != "m4b" || r1.Parsed.BitrateKbps != 64 || !strings.Contains(r1.Magnet, h(0xa1)) {
		t.Fatalf("release not parsed/magnet-filled: %+v", r1)
	}
	if _, ok := e.store.releases[h(3)]; !ok {
		t.Fatal("unmatched releases are stored too")
	}
	if _, ok := e.store.releases["not-a-hash"]; ok {
		t.Fatal("invalid hash stored")
	}
	if at := e.store.searched[testASIN]; !at.Equal(e.clock.Now()) {
		t.Fatalf("searched at %v", at)
	}
	if len(e.store.matches[testASIN]) != 2 {
		t.Fatalf("matches = %v", e.store.matches[testASIN])
	}
}

func TestBookWarmDoesNotSearch(t *testing.T) {
	e := newEnv(t)
	e.mustBook(cfgFor(nil))
	e.clock.Advance(11 * time.Hour)
	p := e.mustBook(cfgFor(nil))
	if e.meta.count("book") != 1 || e.src.calls() != 1 {
		t.Fatalf("meta=%d src=%d", e.meta.count("book"), e.src.calls())
	}
	if len(p.Releases) != 2 {
		t.Fatalf("releases = %d", len(p.Releases))
	}
}

func TestBookStaleResearches(t *testing.T) {
	e := newEnv(t)
	e.mustBook(cfgFor(nil))
	e.clock.Advance(13 * time.Hour)
	e.mustBook(cfgFor(nil))
	if e.src.calls() != 2 {
		t.Fatalf("src calls = %d", e.src.calls())
	}
	if at := e.store.searched[testASIN]; !at.Equal(e.clock.Now()) {
		t.Fatalf("searched at %v", at)
	}
}

func TestBookEmptySearchRetriesAfterAnHour(t *testing.T) {
	e := newEnv(t)
	e.src.rels = nil
	p := e.mustBook(cfgFor(nil))
	if len(p.Releases) != 0 || p.Releases == nil {
		t.Fatalf("releases = %#v", p.Releases)
	}
	e.clock.Advance(59 * time.Minute)
	e.mustBook(cfgFor(nil))
	if e.src.calls() != 1 {
		t.Fatalf("src calls = %d, want 1", e.src.calls())
	}
	e.clock.Advance(2 * time.Minute)
	e.src.mu.Lock()
	e.src.rels = duneSourceRels()
	e.src.mu.Unlock()
	if p := e.mustBook(cfgFor(nil)); len(p.Releases) != 2 {
		t.Fatalf("releases = %d", len(p.Releases))
	}
	if e.src.calls() != 2 {
		t.Fatalf("src calls = %d, want 2", e.src.calls())
	}
}

func TestBookAllSourcesFail(t *testing.T) {
	e := newEnv(t)
	e.seedFresh(duneRel(1, "m4b"))
	e.store.searched[testASIN] = time.Time{} // never searched, but something is stored
	e.src.setErr(errors.New("knaben down"))

	p := e.mustBook(cfgFor(nil))
	if got := hashesOfPage(p); !eq(got, []string{h(1)}) {
		t.Fatalf("stored releases must still be served, got %v", got)
	}
	if !e.store.searched[testASIN].IsZero() || e.store.setMatchesCalls != 0 {
		t.Fatal("a total failure must not mark the book searched or touch matches")
	}
	// Within the cooldown a polling client must not restart the whole fan-out.
	e.mustBook(cfgFor(nil))
	if e.src.calls() != 1 {
		t.Fatalf("must not retry inside the cooldown, src calls = %d", e.src.calls())
	}
	e.clock.Advance(DefaultFailureCooldown + time.Second)
	e.mustBook(cfgFor(nil))
	if e.src.calls() != 2 {
		t.Fatalf("must retry after the cooldown, src calls = %d", e.src.calls())
	}

	e.src.setErr(nil)
	e.clock.Advance(DefaultFailureCooldown + time.Second)
	e.mustBook(cfgFor(nil))
	if e.src.calls() != 3 || e.store.searched[testASIN].IsZero() {
		t.Fatalf("recovered search: calls=%d searched=%v", e.src.calls(), e.store.searched[testASIN])
	}
}

func TestBookOneSourceFailsStillMarksSearched(t *testing.T) {
	bad := &fakeSource{name: "bad", err: errors.New("down")}
	e := newEnv(t, func(e *env, d *Deps) { d.Sources = []sources.Source{bad, e.src} })
	p := e.mustBook(cfgFor(nil))
	if len(p.Releases) != 2 || e.store.searched[testASIN].IsZero() {
		t.Fatalf("releases=%d searched=%v", len(p.Releases), e.store.searched[testASIN])
	}
}

func TestBookNoSources(t *testing.T) {
	e := newEnv(t, func(e *env, d *Deps) { d.Sources = nil })
	p := e.mustBook(cfgFor(nil))
	if len(p.Releases) != 0 || p.Releases == nil {
		t.Fatalf("releases = %#v", p.Releases)
	}
}

func TestBookConcurrentCallersShareOneRun(t *testing.T) {
	e := newEnv(t)
	e.meta.gate = make(chan struct{})
	const n = 20
	var wg sync.WaitGroup
	pages := make([]*BookPage, n)
	errs := make([]error, n)
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			pages[i], errs[i] = e.svc.Book(context.Background(), cfgFor(nil), testASIN)
		}()
	}
	waitFor(t, "metadata call", func() bool { return e.meta.count("book") >= 1 })
	time.Sleep(50 * time.Millisecond) // let the others join the in-flight run
	close(e.meta.gate)
	wg.Wait()
	for i := range n {
		if errs[i] != nil || len(pages[i].Releases) != 2 {
			t.Fatalf("caller %d: %v %v", i, errs[i], pages[i])
		}
	}
	if e.meta.count("book") != 1 || e.src.calls() != 1 {
		t.Fatalf("meta=%d src=%d, want 1/1", e.meta.count("book"), e.src.calls())
	}
}

func TestBookInFlightWorkSurvivesFirstCallerCancel(t *testing.T) {
	e := newEnv(t)
	e.src.gate = make(chan struct{})

	ctx1, cancel1 := context.WithCancel(context.Background())
	first := make(chan error, 1)
	go func() {
		_, err := e.svc.Book(ctx1, cfgFor(nil), testASIN)
		first <- err
	}()
	waitFor(t, "source call", func() bool { return e.src.calls() == 1 })

	type res struct {
		p   *BookPage
		err error
	}
	second := make(chan res, 1)
	go func() {
		p, err := e.svc.Book(context.Background(), cfgFor(nil), testASIN)
		second <- res{p, err}
	}()
	time.Sleep(30 * time.Millisecond)
	cancel1()
	select {
	case err := <-first:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("first caller: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("cancelled caller did not return")
	}

	close(e.src.gate)
	r := <-second
	if r.err != nil || len(r.p.Releases) != 2 {
		t.Fatalf("second caller: %v %v", r.err, r.p)
	}
	if err := e.src.queries[0].ctxErrAtReturn; err != nil {
		t.Fatalf("source context was cancelled: %v", err)
	}
	if e.src.calls() != 1 || e.meta.count("book") != 1 {
		t.Fatalf("src=%d meta=%d", e.src.calls(), e.meta.count("book"))
	}
}

func TestBookPrepareTimeout(t *testing.T) {
	e := newEnv(t, func(e *env, d *Deps) { d.PrepareTimeout = 40 * time.Millisecond })
	e.src.gate = make(chan struct{}) // never opened
	e.seedFresh(duneRel(1, "m4b"))
	e.store.searched[testASIN] = time.Time{}
	p := e.mustBook(cfgFor(nil))
	if !eq(hashesOfPage(p), []string{h(1)}) {
		t.Fatalf("got %v", hashesOfPage(p))
	}
	if !e.store.searched[testASIN].IsZero() {
		t.Fatal("timed-out search must not be marked")
	}
}

func TestBookStoreCandidatesErrorFails(t *testing.T) {
	e := newEnv(t)
	e.seedFresh()
	e.store.failCandidates = errors.New("db down")
	if _, err := e.svc.Book(context.Background(), cfgFor(nil), testASIN); err == nil {
		t.Fatal("want error")
	}
}

// ---- Book: availability, filtering, ranking, files -----------------------------------------

func duneRel(n int, format string) model.Release {
	return model.Release{
		InfoHash: h(n), Title: fmt.Sprintf("Frank Herbert - Dune %d", n), Seeders: n, Size: 600 << 20,
		Magnet: "magnet:?xt=urn:btih:" + h(n),
		Parsed: model.ParsedTitle{Format: format, Language: "en"},
	}
}

func TestBookUncachedFiltering(t *testing.T) {
	setup := func(t *testing.T) *env {
		e := newEnv(t)
		e.seedFresh(duneRel(1, "m4b"), duneRel(2, "m4b"), duneRel(3, "m4b"))
		e.tb.cached[h(3)] = debrid.Cached{}
		return e
	}
	t.Run("hidden by default", func(t *testing.T) {
		e := setup(t)
		p := e.mustBook(cfgFor(nil, "torbox"))
		if got := hashesOfPage(p); !eq(got, []string{h(3)}) {
			t.Fatalf("got %v", got)
		}
		if c := p.Releases[0].Cached; len(c) != 1 || !c["torbox"] {
			t.Fatalf("cached = %v", c)
		}
		if e.tb.keysSeen[0] != tbKey {
			t.Fatal("provider must be called with the user's key")
		}
	})
	t.Run("shown and ranked after cached with ShowUncached", func(t *testing.T) {
		e := setup(t)
		p := e.mustBook(cfgFor(func(c *userconfig.Config) { c.ShowUncached = true }, "torbox"))
		if got := hashesOfPage(p); !eq(got, []string{h(3), h(1), h(2)}) { // equal otherwise: score order from the store
			t.Fatalf("got %v", got)
		}
		if c, ok := p.Releases[1].Cached["torbox"]; !ok || c {
			t.Fatalf("uncached must be explicit false, got %v", p.Releases[1].Cached)
		}
	})
	t.Run("no debrid configured shows everything", func(t *testing.T) {
		e := setup(t)
		p := e.mustBook(cfgFor(nil))
		if len(p.Releases) != 3 || e.tb.checkCount() != 0 {
			t.Fatalf("releases=%d checks=%d", len(p.Releases), e.tb.checkCount())
		}
		for _, r := range p.Releases {
			if len(r.Cached) != 0 {
				t.Fatalf("cached = %v", r.Cached)
			}
		}
	})
	t.Run("provider without cache API keeps everything", func(t *testing.T) {
		e := setup(t)
		p := e.mustBook(cfgFor(nil, "realdebrid"))
		if len(p.Releases) != 3 {
			t.Fatalf("releases = %d", len(p.Releases))
		}
		for _, r := range p.Releases {
			if len(r.Cached) != 0 {
				t.Fatalf("cached must stay unknown: %v", r.Cached)
			}
		}
		if _, ok := e.store.availOf("realdebrid", h(1)); ok {
			t.Fatal("no availability may be invented for a provider without cache API")
		}
		e.mustBook(cfgFor(nil, "realdebrid"))
		if e.rd.checkCount() != 2 {
			t.Fatalf("unknown stays unknown and is asked again, checks = %d", e.rd.checkCount())
		}
	})
	t.Run("torbox plus unknown provider keeps uncached", func(t *testing.T) {
		e := setup(t)
		p := e.mustBook(cfgFor(nil, "torbox", "realdebrid"))
		if got := hashesOfPage(p); !eq(got, []string{h(3), h(1), h(2)}) {
			t.Fatalf("got %v", got)
		}
	})
	t.Run("a legacy non-TorBox row of the shared table is never read", func(t *testing.T) {
		e := setup(t)
		e.store.avail["realdebrid"] = map[string]Availability{h(1): {InfoHash: h(1), Cached: true, CheckedAt: e.clock.Now()}}
		p := e.mustBook(cfgFor(nil, "realdebrid"))
		if len(p.Releases) != 3 {
			t.Fatalf("releases = %d", len(p.Releases))
		}
		for _, r := range p.Releases {
			if len(r.Cached) != 0 {
				t.Fatalf("another account's state leaked: %v", r.Cached)
			}
		}
	})
	t.Run("provider not in the registry is ignored", func(t *testing.T) {
		e := setup(t)
		p := e.mustBook(cfgFor(nil, "premiumize"))
		if len(p.Releases) != 3 {
			t.Fatalf("releases = %d", len(p.Releases))
		}
	})
}

func TestBookAvailabilityCaching(t *testing.T) {
	e := newEnv(t)
	e.seedFresh(duneRel(1, "m4b"), duneRel(2, "m4b"), duneRel(3, "m4b"))
	e.tb.cached[h(1)] = debrid.Cached{}
	cfg := cfgFor(func(c *userconfig.Config) { c.ShowUncached = true }, "torbox")

	e.mustBook(cfg)
	if got := e.tb.lastCheck(); len(got) != 3 {
		t.Fatalf("first check = %v", got)
	}
	if a, ok := e.store.availOf("torbox", h(2)); !ok || a.Cached || !a.CheckedAt.Equal(e.clock.Now()) {
		t.Fatalf("uncached state must be stored: %+v %v", a, ok)
	}
	if a, ok := e.store.availOf("torbox", h(1)); !ok || !a.Cached {
		t.Fatalf("cached state: %+v %v", a, ok)
	}

	e.clock.Advance(5 * time.Hour)
	e.mustBook(cfg)
	if e.tb.checkCount() != 1 {
		t.Fatalf("fresh availability must be reused, checks = %d", e.tb.checkCount())
	}

	e.clock.Advance(2 * time.Hour) // 7h > 6h TTL; releases are 7h old, still fresh
	e.mustBook(cfg)
	if e.tb.checkCount() != 2 {
		t.Fatalf("stale availability must be rechecked, checks = %d", e.tb.checkCount())
	}
}

func TestBookOnlyMissingHashesAreChecked(t *testing.T) {
	e := newEnv(t)
	e.seedFresh(duneRel(1, "m4b"), duneRel(2, "m4b"), duneRel(3, "m4b"))
	e.store.avail["torbox"] = map[string]Availability{
		h(2): {InfoHash: h(2), Cached: true, CheckedAt: e.clock.Now().Add(-time.Hour)},
		h(3): {InfoHash: h(3), Cached: false, CheckedAt: e.clock.Now().Add(-7 * time.Hour)}, // stale
	}
	p := e.mustBook(cfgFor(nil, "torbox"))
	if got := e.tb.lastCheck(); !eq(got, []string{h(1), h(3)}) {
		t.Fatalf("checked %v", got)
	}
	if got := hashesOfPage(p); !eq(got, []string{h(2)}) {
		t.Fatalf("got %v", got)
	}
}

func TestBookProviderErrorDoesNotFailPage(t *testing.T) {
	e := newEnv(t)
	e.seedFresh(duneRel(1, "m4b"), duneRel(2, "m4b"))
	e.tb.checkErr = fmt.Errorf("torbox: HTTP 500 for https://x/?token=%s", tbKey)
	e.rd.noAPI = false
	e.rd.cached = map[string]debrid.Cached{h(2): {}}

	p := e.mustBook(cfgFor(nil, "torbox", "realdebrid"))
	// torbox unknown => nothing is dropped; rd says h(2) cached => it ranks first.
	if got := hashesOfPage(p); !eq(got, []string{h(2), h(1)}) {
		t.Fatalf("got %v", got)
	}
	if _, known := p.Releases[0].Cached["torbox"]; known {
		t.Fatal("failed provider must stay unknown")
	}
	if logs := e.logs.String(); strings.Contains(logs, tbKey) || strings.Contains(logs, rdKey) {
		t.Fatalf("key leaked into logs:\n%s", logs)
	}
	if !strings.Contains(e.logs.String(), "cache check failed") {
		t.Fatalf("failure should be logged:\n%s", e.logs.String())
	}
}

func TestBookCheckTimeout(t *testing.T) {
	e := newEnv(t, func(e *env, d *Deps) { d.CheckTimeout = 30 * time.Millisecond })
	e.seedFresh(duneRel(1, "m4b"))
	p := &slowProvider{fakeProvider: e.tb}
	e.svc.d.Providers = debrid.NewRegistry(p)
	start := time.Now()
	page := e.mustBook(cfgFor(nil, "torbox"))
	if time.Since(start) > 2*time.Second || len(page.Releases) != 1 {
		t.Fatalf("elapsed %v, releases %d", time.Since(start), len(page.Releases))
	}
}

// slowProvider blocks CheckCached until its context ends.
type slowProvider struct{ *fakeProvider }

func (p *slowProvider) CheckCached(ctx context.Context, key string, hashes []string) (map[string]debrid.Cached, error) {
	<-ctx.Done()
	return nil, ctx.Err()
}

func TestBookLanguageFilter(t *testing.T) {
	e := newEnv(t)
	nl := duneRel(2, "m4b")
	nl.Parsed.Language = "nl"
	de := duneRel(3, "m4b")
	de.Parsed.Language = "de"
	unk := duneRel(4, "m4b")
	unk.Parsed.Language = ""
	e.seedFresh(duneRel(1, "m4b"), nl, de, unk)

	p := e.mustBook(cfgFor(nil))
	if got := hashesOfPage(p); !eq(got, []string{h(1), h(4)}) {
		t.Fatalf("en only: %v", got)
	}
	p = e.mustBook(cfgFor(func(c *userconfig.Config) { c.Languages = []string{"nl", "en"} }))
	if got := hashesOfPage(p); !eq(got, []string{h(2), h(1), h(4)}) {
		t.Fatalf("nl,en: %v", got)
	}
}

func TestBookLanguageFilteredReleasesAreNotChecked(t *testing.T) {
	e := newEnv(t)
	nl := duneRel(2, "m4b")
	nl.Parsed.Language = "nl"
	e.seedFresh(duneRel(1, "m4b"), nl)
	e.mustBook(cfgFor(nil, "torbox"))
	if got := e.tb.lastCheck(); !eq(got, []string{h(1)}) {
		t.Fatalf("checked %v", got)
	}
}

func TestBookMaxReleases(t *testing.T) {
	e := newEnv(t)
	var rels []model.Release
	for i := 1; i <= 8; i++ {
		rels = append(rels, duneRel(i, "m4b"))
	}
	e.seedFresh(rels...)
	if p := e.mustBook(cfgFor(nil)); len(p.Releases) != 5 {
		t.Fatalf("default = %d", len(p.Releases))
	}
	p := e.mustBook(cfgFor(func(c *userconfig.Config) { c.MaxReleases = 3 }))
	if len(p.Releases) != 3 {
		t.Fatalf("got %d", len(p.Releases))
	}
	for i, r := range p.Releases {
		if r.Season != i+1 {
			t.Fatalf("season %d at %d", r.Season, i)
		}
	}
	zero := cfgFor(nil)
	zero.MaxReleases = 0 // not normalized by the caller
	if p := e.mustBook(zero); len(p.Releases) != 5 {
		t.Fatalf("zero MaxReleases = %d", len(p.Releases))
	}
}

func TestBookRankingOrder(t *testing.T) {
	e := newEnv(t)
	mk := func(n int, lang, format string, br int, seeders int) model.Release {
		r := duneRel(n, format)
		r.Parsed.Language, r.Parsed.BitrateKbps, r.Seeders = lang, br, seeders
		return r
	}
	e.seedFresh(
		mk(1, "en", "mp3", 128, 5),
		mk(2, "en", "m4b", 0, 5),
		mk(3, "nl", "m4b", 128, 5),
		mk(4, "en", "m4b", 64, 5),
		mk(5, "en", "m4b", 128, 5),
		mk(6, "", "m4b", 128, 5),
	)
	e.tb.cached[h(1)] = debrid.Cached{} // cached anywhere first
	cfg := cfgFor(func(c *userconfig.Config) {
		c.Languages = []string{"nl", "en"}
		c.ShowUncached = true
		c.MaxReleases = 20
	}, "torbox")
	p := e.mustBook(cfg)
	want := []string{h(1), h(3), h(5), h(4), h(2), h(6)}
	if got := hashesOfPage(p); !eq(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestBookFilesFromCheckAreStoredAndOrdered(t *testing.T) {
	e := newEnv(t)
	e.seedFresh(duneRel(1, "m4b"), duneRel(2, "m4b"))
	files := []model.File{
		{Index: 0, Path: "Dune/Chapter 10.mp3", Size: 10},
		{Index: 1, Path: "Dune/cover.jpg", Size: 1},
		{Index: 2, Path: "Dune/Chapter 2.mp3", Size: 10},
		{Index: 3, Path: "Dune/Chapter 1.mp3", Size: 10},
	}
	e.tb.cached[h(1)] = debrid.Cached{Files: files}
	e.tb.cached[h(2)] = debrid.Cached{} // cached but provider reports no files
	cfg := cfgFor(nil, "torbox")
	p := e.mustBook(cfg)
	if len(p.Releases) != 2 {
		t.Fatalf("releases = %d", len(p.Releases))
	}
	var r1, r2 ReleaseView
	for _, r := range p.Releases {
		if r.Release.InfoHash == h(1) {
			r1 = r
		} else {
			r2 = r
		}
	}
	var idx []int
	for _, f := range r1.Files {
		idx = append(idx, f.Index)
	}
	if fmt.Sprint(idx) != "[3 2 0]" {
		t.Fatalf("file order = %v", idx)
	}
	if len(r2.Files) != 0 {
		t.Fatalf("unknown files = %v", r2.Files)
	}
	if len(e.store.filesOf(h(1))) != 4 || len(e.store.filesOf(h(2))) != 0 {
		t.Fatal("all provider files must be stored for cached entries that carry files")
	}
}

func TestBookFilesNotOverwrittenByAnotherProvider(t *testing.T) {
	e := newEnv(t)
	e.seedFresh(duneRel(1, "m4b"))
	e.store.files[h(1)] = []model.File{{Index: 7, Path: "x.mp3"}}
	e.tb.cached[h(1)] = debrid.Cached{Files: []model.File{{Index: 0, Path: "other.mp3"}}}
	p := e.mustBook(cfgFor(nil, "torbox"))
	if e.store.saveFilesCalls != 0 {
		t.Fatal("stored files must not be replaced")
	}
	if len(p.Releases[0].Files) != 1 || p.Releases[0].Files[0].Index != 7 {
		t.Fatalf("files = %v", p.Releases[0].Files)
	}
}

func TestBookFileCap(t *testing.T) {
	e := newEnv(t)
	e.seedFresh(duneRel(1, "m4b"))
	var files []model.File
	for i := 0; i < 450; i++ {
		files = append(files, model.File{Index: i, Path: fmt.Sprintf("d/%03d.mp3", i)})
	}
	e.store.files[h(1)] = files
	p := e.mustBook(cfgFor(nil))
	if n := len(p.Releases[0].Files); n != 399 {
		t.Fatalf("files = %d", n)
	}
	if p.Releases[0].Files[398].Index != 398 {
		t.Fatalf("cap must keep the first files in order, last = %d", p.Releases[0].Files[398].Index)
	}
}

// ---- Streams -------------------------------------------------------------------------------

func TestStreams(t *testing.T) {
	setup := func(t *testing.T) *env {
		e := newEnv(t)
		e.seedFresh(duneRel(1, "m4b"), duneRel(2, "m4b"))
		e.store.files[h(1)] = []model.File{{Index: 4, Path: "a.mp3", Size: 1}, {Index: 5, Path: "b.mp3", Size: 2}}
		e.store.avail["torbox"] = map[string]Availability{h(1): {InfoHash: h(1), Cached: true, CheckedAt: e.clock.Now()}}
		return e
	}
	ctx := context.Background()
	both := cfgFor(nil, "torbox", "realdebrid")

	t.Run("one stream per provider in name order", func(t *testing.T) {
		e := setup(t)
		got, err := e.svc.Streams(ctx, both, VideoRef{ASIN: testASIN, InfoHash: h(1), FileIndex: 5})
		if err != nil || len(got) != 2 {
			t.Fatalf("%v %v", got, err)
		}
		if got[0].Provider != "realdebrid" || got[1].Provider != "torbox" {
			t.Fatalf("order = %s,%s", got[0].Provider, got[1].Provider)
		}
		if got[0].Cached || !got[1].Cached {
			t.Fatalf("cached = %v,%v", got[0].Cached, got[1].Cached)
		}
		if got[1].File == nil || got[1].File.Path != "b.mp3" || got[1].FileIndex != 5 || got[1].Release.InfoHash != h(1) {
			t.Fatalf("stream = %+v", got[1])
		}
	})
	t.Run("legacy shared row of a non-shared provider is ignored", func(t *testing.T) {
		e := setup(t)
		e.store.avail["realdebrid"] = map[string]Availability{h(1): {InfoHash: h(1), Cached: true, CheckedAt: e.clock.Now()}}
		got, err := e.svc.Streams(ctx, both, VideoRef{ASIN: testASIN, InfoHash: h(1), FileIndex: 5})
		if err != nil || len(got) != 2 || got[0].Provider != "realdebrid" || got[0].Cached {
			t.Fatalf("%+v %v", got, err)
		}
	})
	t.Run("auto file has no File", func(t *testing.T) {
		e := setup(t)
		got, err := e.svc.Streams(ctx, both, VideoRef{ASIN: testASIN, InfoHash: h(1), FileIndex: AutoFile})
		if err != nil || got[0].File != nil || got[0].FileIndex != AutoFile {
			t.Fatalf("%+v %v", got, err)
		}
	})
	t.Run("unknown file index", func(t *testing.T) {
		e := setup(t)
		got, err := e.svc.Streams(ctx, both, VideoRef{ASIN: testASIN, InfoHash: h(1), FileIndex: 99})
		if err != nil || got[0].File != nil || got[0].FileIndex != 99 {
			t.Fatalf("%+v %v", got, err)
		}
	})
	t.Run("stale availability is unknown = not cached", func(t *testing.T) {
		e := setup(t)
		e.clock.Advance(7 * time.Hour)
		got, _ := e.svc.Streams(ctx, cfgFor(nil, "torbox"), VideoRef{ASIN: testASIN, InfoHash: h(1), FileIndex: AutoFile})
		if len(got) != 1 || got[0].Cached {
			t.Fatalf("%+v", got)
		}
	})
	t.Run("not a candidate of the book", func(t *testing.T) {
		e := setup(t)
		_, err := e.svc.Streams(ctx, both, VideoRef{ASIN: testASIN, InfoHash: h(9), FileIndex: 0})
		if !errors.Is(err, ErrNotFound) {
			t.Fatalf("%v", err)
		}
		_, err = e.svc.Streams(ctx, both, VideoRef{ASIN: "B000000000", InfoHash: h(1), FileIndex: 0})
		if !errors.Is(err, ErrNotFound) {
			t.Fatalf("%v", err)
		}
	})
	t.Run("malformed refs", func(t *testing.T) {
		e := setup(t)
		for _, ref := range []VideoRef{
			{ASIN: "bad", InfoHash: h(1)},
			{ASIN: testASIN, InfoHash: strings.ToUpper(h(255))},
			{ASIN: testASIN, InfoHash: "xyz"},
			{ASIN: testASIN, InfoHash: h(1), FileIndex: -2},
		} {
			if _, err := e.svc.Streams(ctx, both, ref); !errors.Is(err, ErrNotFound) {
				t.Errorf("%+v: %v", ref, err)
			}
		}
	})
	t.Run("no provider", func(t *testing.T) {
		e := setup(t)
		for _, c := range []userconfig.Config{cfgFor(nil), cfgFor(nil, "premiumize")} {
			_, err := e.svc.Streams(ctx, c, VideoRef{ASIN: testASIN, InfoHash: h(1)})
			if !errors.Is(err, ErrNoProvider) {
				t.Fatalf("%v", err)
			}
		}
	})
}

// ---- Resolve -------------------------------------------------------------------------------

func TestResolve(t *testing.T) {
	ctx := context.Background()
	cfg := cfgFor(nil, "torbox", "realdebrid")

	t.Run("explicit file", func(t *testing.T) {
		e := newEnv(t)
		e.tb.resolveRes = debrid.ResolveResult{URL: "https://dl/x"}
		res, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), 3)
		if err != nil || res.URL != "https://dl/x" {
			t.Fatalf("%+v %v", res, err)
		}
		req := e.tb.resolves[0]
		if req.FileIndex != 3 || req.InfoHash != h(1) || !strings.HasPrefix(req.Magnet, "magnet:?xt=urn:btih:"+h(1)) || e.tb.keysSeen[0] != tbKey {
			t.Fatalf("req = %+v key=%v", req, e.tb.keysSeen)
		}
		if e.tb.checkCount() != 0 {
			t.Fatal("explicit index needs no cache check")
		}
		if a, ok := e.store.availOf("torbox", h(1)); !ok || !a.Cached || !a.CheckedAt.Equal(e.clock.Now()) {
			t.Fatalf("availability not learned: %+v", a)
		}
	})
	t.Run("pending does not mark cached", func(t *testing.T) {
		e := newEnv(t)
		e.tb.resolveRes = debrid.ResolveResult{Pending: true, Progress: 40}
		res, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), 0)
		if err != nil || !res.Pending || res.Progress != 40 {
			t.Fatalf("%+v %v", res, err)
		}
		if _, ok := e.store.availOf("torbox", h(1)); ok {
			t.Fatal("pending must not set availability")
		}
	})
	t.Run("auto picks the largest stored audio file", func(t *testing.T) {
		e := newEnv(t)
		e.store.files[h(1)] = []model.File{{Index: 0, Path: "a.mp3", Size: 5}, {Index: 1, Path: "big.iso", Size: 99}, {Index: 2, Path: "b.mp3", Size: 8}}
		e.tb.resolveRes = debrid.ResolveResult{URL: "u"}
		if _, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), AutoFile); err != nil {
			t.Fatal(err)
		}
		if e.tb.resolves[0].FileIndex != 2 || e.tb.checkCount() != 0 {
			t.Fatalf("index=%d checks=%d", e.tb.resolves[0].FileIndex, e.tb.checkCount())
		}
	})
	t.Run("auto learns files from a cache check", func(t *testing.T) {
		e := newEnv(t)
		e.tb.cached[h(1)] = debrid.Cached{Files: []model.File{{Index: 6, Path: "a.m4b", Size: 50}, {Index: 7, Path: "x.nfo", Size: 90}}}
		e.tb.resolveRes = debrid.ResolveResult{URL: "u"}
		if _, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), AutoFile); err != nil {
			t.Fatal(err)
		}
		if got := e.tb.lastCheck(); !eq(got, []string{h(1)}) {
			t.Fatalf("check = %v", got)
		}
		if e.tb.resolves[0].FileIndex != 6 || len(e.store.filesOf(h(1))) != 2 {
			t.Fatalf("index=%d files=%v", e.tb.resolves[0].FileIndex, e.store.filesOf(h(1)))
		}
	})
	t.Run("auto with nothing known stays auto for the provider", func(t *testing.T) {
		e := newEnv(t)
		e.tb.resolveRes = debrid.ResolveResult{Pending: true}
		res, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), AutoFile)
		if err != nil || !res.Pending {
			t.Fatalf("%+v %v", res, err)
		}
		if e.tb.resolves[0].FileIndex != debrid.AutoFile {
			t.Fatalf("index = %d, want AutoFile (never guess index 0)", e.tb.resolves[0].FileIndex)
		}
		if a, ok := e.store.availOf("torbox", h(1)); !ok || a.Cached {
			t.Fatalf("a not-cached answer is a valid availability: %+v %v", a, ok)
		}
	})
	t.Run("auto with provider without cache API", func(t *testing.T) {
		e := newEnv(t)
		e.rd.resolveRes = debrid.ResolveResult{Pending: true}
		if _, err := e.svc.Resolve(ctx, cfg, "realdebrid", h(1), AutoFile); err != nil {
			t.Fatal(err)
		}
		if e.rd.resolves[0].FileIndex != debrid.AutoFile || e.rd.resolves[0].Magnet == "" {
			t.Fatalf("%+v", e.rd.resolves[0])
		}
		if _, ok := e.store.availOf("realdebrid", h(1)); ok {
			t.Fatal("no availability for a provider without cache API")
		}
	})
	t.Run("auto survives a failing cache check", func(t *testing.T) {
		e := newEnv(t)
		e.tb.checkErr = errors.New("boom")
		e.tb.resolveRes = debrid.ResolveResult{URL: "u"}
		if _, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), AutoFile); err != nil {
			t.Fatal(err)
		}
	})
	t.Run("provider errors pass through", func(t *testing.T) {
		for _, want := range []error{debrid.ErrBadKey, debrid.ErrRateLimited, debrid.ErrNotFound, debrid.ErrFailed} {
			e := newEnv(t)
			wrapped := fmt.Errorf("torbox: resolve: %w", want)
			e.tb.resolveErr = wrapped
			_, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), 0)
			if !errors.Is(err, want) || err != wrapped {
				t.Fatalf("got %v (%T), want the same error as returned by the provider", err, err)
			}
		}
	})
	t.Run("errors never contain the key", func(t *testing.T) {
		e := newEnv(t)
		e.tb.resolveErr = fmt.Errorf("GET https://api/?key=%s: %w", tbKey, debrid.ErrBadKey)
		_, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), 0)
		if err == nil || strings.Contains(err.Error(), tbKey) || !errors.Is(err, debrid.ErrBadKey) {
			t.Fatalf("got %v", err)
		}
	})
	t.Run("provider selection", func(t *testing.T) {
		e := newEnv(t)
		for _, c := range []struct {
			cfg      userconfig.Config
			provider string
		}{
			{cfgFor(nil), "torbox"},                   // not configured
			{cfgFor(nil, "torbox"), "realdebrid"},     // other provider
			{cfgFor(nil, "premiumize"), "premiumize"}, // not in registry
			{cfgFor(nil, "torbox"), ""},
		} {
			if _, err := e.svc.Resolve(ctx, c.cfg, c.provider, h(1), 0); !errors.Is(err, ErrNoProvider) {
				t.Errorf("%q: %v", c.provider, err)
			}
		}
		if len(e.tb.resolves) != 0 {
			t.Fatal("provider must not be called")
		}
	})
	t.Run("bad hash or index", func(t *testing.T) {
		e := newEnv(t)
		for _, hash := range []string{"", "abc", strings.ToUpper(h(255)), h(1) + "0", strings.Repeat("g", 40)} {
			if _, err := e.svc.Resolve(ctx, cfg, "torbox", hash, 0); !errors.Is(err, ErrNotFound) {
				t.Errorf("%q: %v", hash, err)
			}
		}
		if _, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), -2); !errors.Is(err, ErrNotFound) {
			t.Errorf("index -2: %v", err)
		}
	})
}

// ---- ValidateKeys / Providers ---------------------------------------------------------------

func TestValidateKeys(t *testing.T) {
	e := newEnv(t)
	e.tb.validate = fmt.Errorf("torbox: rejected %s: %w", tbKey, debrid.ErrBadKey)
	got := e.svc.ValidateKeys(context.Background(), map[string]string{
		"torbox": tbKey, "realdebrid": rdKey, "premiumize": "k",
	})
	if len(got) != 2 {
		t.Fatalf("got %v", got)
	}
	if err := got["torbox"]; !errors.Is(err, debrid.ErrBadKey) || strings.Contains(err.Error(), tbKey) {
		t.Fatalf("torbox: %v", err)
	}
	if err := got["premiumize"]; err == nil || !strings.Contains(err.Error(), "unknown provider") {
		t.Fatalf("premiumize: %v", err)
	}
	if _, bad := got["realdebrid"]; bad {
		t.Fatal("realdebrid is valid")
	}
	if e := New(Deps{Metadata: e.meta, Store: e.store}).ValidateKeys(context.Background(), nil); e == nil || len(e) != 0 {
		t.Fatalf("empty input must give an empty non-nil map, got %#v", e)
	}
}

func TestValidateKeysRunsConcurrentlyWithTimeout(t *testing.T) {
	var wg sync.WaitGroup
	wg.Add(2)
	barrier := func(ctx context.Context, key string) error {
		wg.Done()
		done := make(chan struct{})
		go func() { wg.Wait(); close(done) }()
		select {
		case <-done:
			return nil
		case <-ctx.Done():
			return ctx.Err()
		}
	}
	e := newEnv(t, func(e *env, d *Deps) { d.ValidateTimeout = 2 * time.Second })
	e.tb.validateFn, e.rd.validateFn = barrier, barrier
	got := e.svc.ValidateKeys(context.Background(), map[string]string{"torbox": "a", "realdebrid": "b"})
	if len(got) != 0 {
		t.Fatalf("both validations must overlap, got %v", got)
	}

	e2 := newEnv(t, func(e *env, d *Deps) { d.ValidateTimeout = 30 * time.Millisecond })
	e2.tb.validateFn = func(ctx context.Context, key string) error { <-ctx.Done(); return ctx.Err() }
	got = e2.svc.ValidateKeys(context.Background(), map[string]string{"torbox": "a"})
	if !errors.Is(got["torbox"], context.DeadlineExceeded) {
		t.Fatalf("got %v", got)
	}
}

func TestValidateKeysSurvivesPanic(t *testing.T) {
	e := newEnv(t)
	e.tb.validateFn = func(context.Context, string) error { panic("boom") }
	got := e.svc.ValidateKeys(context.Background(), map[string]string{"torbox": "k"})
	if got["torbox"] == nil {
		t.Fatal("panic must become an error")
	}
}

func TestProviders(t *testing.T) {
	e := newEnv(t)
	if got := e.svc.Providers(); !eq(got, []string{"realdebrid", "torbox"}) {
		t.Fatalf("got %v", got)
	}
}

// A cached torrent that only holds an archive (e.g. one big .zip of FLACs) cannot be streamed:
// it must not be listed, while a release with unknown files must stay.
func TestBookDropsReleasesWithoutAudioFiles(t *testing.T) {
	e := newEnv(t)
	e.seedFresh(duneRel(1, "flac"), duneRel(2, "m4b"), duneRel(3, "mp3"))
	e.tb.cached[h(1)] = debrid.Cached{Files: []model.File{{Index: 0, Path: "Dune [flac]/Dune [flac].zip", Size: 700}}}
	e.tb.cached[h(2)] = debrid.Cached{} // cached, files unknown: stays
	e.tb.cached[h(3)] = debrid.Cached{Files: []model.File{{Index: 4, Path: "Dune/01.mp3", Size: 9}, {Index: 5, Path: "Dune/cover.jpg", Size: 1}}}
	p := e.mustBook(cfgFor(nil, "torbox"))
	var got []string
	for _, r := range p.Releases {
		got = append(got, r.Release.InfoHash)
	}
	if len(p.Releases) != 2 {
		t.Fatalf("releases = %v, want the zip-only one dropped", got)
	}
	for _, r := range p.Releases {
		if r.Release.InfoHash == h(1) {
			t.Fatal("zip-only release is still listed")
		}
	}
}

// Series books are searched under the series name too, because the pack that holds them is
// named after the series, not after the book.
func TestBookSearchesTheSeriesNameToo(t *testing.T) {
	e := newEnv(t)
	b := dune()
	b.Series, b.SeriesPosition = "Dune Chronicles", "1"
	e.meta.books[testASIN] = b
	e.mustBook(cfgFor(nil))
	var titles []string
	for _, q := range e.src.queries {
		titles = append(titles, q.Title)
	}
	sort.Strings(titles) // the two queries run concurrently
	if !eq(titles, []string{"Dune", "Dune Chronicles"}) {
		t.Fatalf("queries = %v", titles)
	}

	// A series named like the book does not double the work.
	e2 := newEnv(t)
	b2 := dune()
	b2.Series = "Dune"
	e2.meta.books[testASIN] = b2
	e2.mustBook(cfgFor(nil))
	if e2.src.calls() != 1 {
		t.Fatalf("calls = %d", e2.src.calls())
	}
}

// Indexes match accents literally and Spanish releases come both ways, so an accented book is
// searched twice: as written and folded. Plain ASCII books are searched once.
func TestBookSearchesAccentFoldedToo(t *testing.T) {
	e := newEnv(t)
	b := dune()
	b.Title, b.Authors, b.Language = "Cien años de soledad", []string{"Gabriel García Márquez"}, "es"
	e.meta.books[testASIN] = b
	e.mustBook(cfgFor(func(c *userconfig.Config) { c.Languages = []string{"es"} }))
	var got []string
	for _, q := range e.src.queries {
		got = append(got, q.Title+"|"+q.Author)
	}
	sort.Strings(got)
	want := []string{"Cien años de soledad|Gabriel García Márquez", "cien anos de soledad|gabriel garcia marquez"}
	if !eq(got, want) {
		t.Fatalf("queries = %q, want %q", got, want)
	}
}
