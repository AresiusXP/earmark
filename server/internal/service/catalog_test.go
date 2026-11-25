package service

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/AresiusXP/earmark/server/internal/model"
	"github.com/AresiusXP/earmark/server/internal/userconfig"
)

func books(asins ...string) []model.Book {
	out := []model.Book{}
	for _, a := range asins {
		out = append(out, model.Book{ASIN: a, Title: a})
	}
	return out
}

func asinsOf(bs []model.Book) string {
	var s []string
	for _, b := range bs {
		s = append(s, b.ASIN)
	}
	return strings.Join(s, ",")
}

func TestSearchMergesLanguages(t *testing.T) {
	e := newEnv(t)
	e.meta.search["us|en|dune"] = books("E1", "E2", "SHARED")
	e.meta.search["us|nl|dune"] = books("N1", "SHARED", "N3")
	cfg := cfgFor(func(c *userconfig.Config) { c.Languages = []string{"en", "nl"} })

	got, err := e.svc.Search(context.Background(), cfg, "  Dune ")
	if err != nil {
		t.Fatal(err)
	}
	if asinsOf(got) != "E1,N1,E2,SHARED,N3" {
		t.Fatalf("got %s", asinsOf(got))
	}
	if e.meta.count("search") != 2 {
		t.Fatalf("calls = %d", e.meta.count("search"))
	}
}

func TestSearchRegionAndSingleLanguage(t *testing.T) {
	e := newEnv(t)
	e.meta.search["us|en|dune"] = books("E1")
	got, err := e.svc.Search(context.Background(), cfgFor(func(c *userconfig.Config) { c.Region = "nl" }), "dune")
	if err != nil || asinsOf(got) != "E1" {
		t.Fatalf("%v %v", got, err)
	}
	e.meta.search["uk|en|dune"] = books("U1")
	got, _ = e.svc.Search(context.Background(), cfgFor(func(c *userconfig.Config) { c.Region = "uk" }), "dune")
	if asinsOf(got) != "U1" {
		t.Fatalf("cache must be keyed by region, got %s", asinsOf(got))
	}
}

func TestSearchEmptyQueryAndNilResult(t *testing.T) {
	e := newEnv(t)
	got, err := e.svc.Search(context.Background(), cfgFor(nil), "   ")
	if err != nil || got == nil || len(got) != 0 || e.meta.count("search") != 0 {
		t.Fatalf("%#v %v calls=%d", got, err, e.meta.count("search"))
	}
	got, err = e.svc.Search(context.Background(), cfgFor(nil), "nothing")
	if err != nil || got == nil || len(got) != 0 {
		t.Fatalf("%#v %v", got, err)
	}
}

func TestSearchCache(t *testing.T) {
	e := newEnv(t)
	e.meta.search["us|en|dune"] = books("E1")
	cfg := cfgFor(nil)
	for range 3 {
		if _, err := e.svc.Search(context.Background(), cfg, "Dune"); err != nil {
			t.Fatal(err)
		}
	}
	if e.meta.count("search") != 1 {
		t.Fatalf("calls = %d, want 1 (cache hit, query is case-insensitive)", e.meta.count("search"))
	}
	e.clock.Advance(9 * time.Minute)
	e.svc.Search(context.Background(), cfg, "dune")
	if e.meta.count("search") != 1 {
		t.Fatalf("still cached at 9min, calls = %d", e.meta.count("search"))
	}
	e.clock.Advance(2 * time.Minute)
	e.svc.Search(context.Background(), cfg, "dune")
	if e.meta.count("search") != 2 {
		t.Fatalf("expired at 11min, calls = %d", e.meta.count("search"))
	}
}

func TestSearchCacheReturnsCopies(t *testing.T) {
	e := newEnv(t)
	e.meta.search["us|en|dune"] = books("E1", "E2")
	first, _ := e.svc.Search(context.Background(), cfgFor(nil), "dune")
	first[0].ASIN = "MUTATED"
	second, _ := e.svc.Search(context.Background(), cfgFor(nil), "dune")
	if asinsOf(second) != "E1,E2" {
		t.Fatalf("got %s", asinsOf(second))
	}
}

func TestSearchPartialAndTotalFailure(t *testing.T) {
	e := newEnv(t)
	e.meta.search["us|en|dune"] = books("E1")
	e.meta.errs["nl"] = errors.New("nl down")
	cfg := cfgFor(func(c *userconfig.Config) { c.Languages = []string{"en", "nl"} })
	got, err := e.svc.Search(context.Background(), cfg, "dune")
	if err != nil || asinsOf(got) != "E1" {
		t.Fatalf("partial: %v %v", got, err)
	}
	if _, err := e.svc.Search(context.Background(), cfg, "dune"); err != nil || e.meta.count("search") != 3 {
		t.Fatalf("failed language is not cached: err=%v calls=%d", err, e.meta.count("search"))
	}

	e.meta.errs["en"] = errors.New("en down")
	if _, err := e.svc.Search(context.Background(), cfgFor(func(c *userconfig.Config) { c.Languages = []string{"en"} }), "other"); err == nil {
		t.Fatal("total failure must be an error")
	}
}

func TestSearchUnknownRegion(t *testing.T) {
	e := newEnv(t)
	if _, err := e.svc.Search(context.Background(), userconfig.Config{Region: "zz"}, "dune"); err == nil {
		t.Fatal("want error")
	}
	if _, err := e.svc.Discover(context.Background(), userconfig.Config{Region: "zz"}, 0); err == nil {
		t.Fatal("want error")
	}
}

func TestDiscover(t *testing.T) {
	e := newEnv(t)
	e.meta.discover["us|en|0"] = books("E1", "E2")
	e.meta.discover["us|nl|0"] = books("N1")
	e.meta.discover["us|en|1"] = books("E3")
	cfg := cfgFor(func(c *userconfig.Config) { c.Languages = []string{"nl", "en"} })

	got, err := e.svc.Discover(context.Background(), cfg, 0)
	if err != nil || asinsOf(got) != "N1,E1,E2" {
		t.Fatalf("%s %v", asinsOf(got), err)
	}
	e.svc.Discover(context.Background(), cfg, 0)
	if e.meta.count("discover") != 2 {
		t.Fatalf("page 0 cached, calls = %d", e.meta.count("discover"))
	}
	got, _ = e.svc.Discover(context.Background(), cfg, 1)
	if asinsOf(got) != "E3" {
		t.Fatalf("page 1: %s", asinsOf(got))
	}
	got, err = e.svc.Discover(context.Background(), cfg, -5)
	if err != nil || asinsOf(got) != "N1,E1,E2" {
		t.Fatalf("negative page: %s %v", asinsOf(got), err)
	}
}

func TestSearchDoesNotShareCacheWithDiscover(t *testing.T) {
	e := newEnv(t)
	e.meta.search["us|en|0"] = books("S1")
	e.meta.discover["us|en|0"] = books("D1")
	s, _ := e.svc.Search(context.Background(), cfgFor(nil), "0")
	d, _ := e.svc.Discover(context.Background(), cfgFor(nil), 0)
	if asinsOf(s) != "S1" || asinsOf(d) != "D1" {
		t.Fatalf("search=%s discover=%s", asinsOf(s), asinsOf(d))
	}
}

func TestBookCacheBounded(t *testing.T) {
	clock := newClock()
	c := newBookCache(3, clock.Now)
	for i := range 10 {
		c.put(fmt.Sprint(i), books("X"), time.Hour)
		clock.Advance(time.Second)
		if c.len() > 3 {
			t.Fatalf("len = %d", c.len())
		}
	}
	if _, ok := c.get("0"); ok {
		t.Fatal("oldest must have been evicted")
	}
	if _, ok := c.get("9"); !ok {
		t.Fatal("newest must be present")
	}
	// expired entries are purged before live ones are evicted
	c2 := newBookCache(2, clock.Now)
	c2.put("old", books("X"), time.Second)
	c2.put("live", books("X"), time.Hour)
	clock.Advance(time.Minute)
	c2.put("new", books("X"), time.Hour)
	if _, ok := c2.get("live"); !ok {
		t.Fatal("live entry evicted while an expired one existed")
	}
}

func TestSearchConcurrentRace(t *testing.T) {
	e := newEnv(t)
	e.meta.search["us|en|dune"] = books("E1")
	var wg sync.WaitGroup
	for range 30 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if got, err := e.svc.Search(context.Background(), cfgFor(nil), "dune"); err != nil || len(got) != 1 {
				t.Errorf("%v %v", got, err)
			}
		}()
	}
	wg.Wait()
}

func TestFlightGroup(t *testing.T) {
	var g flightGroup[int]
	var runs atomic.Int32
	gate := make(chan struct{})
	const n = 10
	var wg sync.WaitGroup
	results := make([]int, n)
	for i := range n {
		wg.Add(1)
		go func() {
			defer wg.Done()
			v, err := g.do(context.Background(), "k", func() (int, error) {
				runs.Add(1)
				<-gate
				return 42, nil
			})
			if err != nil {
				t.Error(err)
			}
			results[i] = v
		}()
	}
	waitFor(t, "first run", func() bool { return runs.Load() == 1 })
	time.Sleep(30 * time.Millisecond)
	close(gate)
	wg.Wait()
	if runs.Load() != 1 {
		t.Fatalf("runs = %d", runs.Load())
	}
	for _, v := range results {
		if v != 42 {
			t.Fatalf("results = %v", results)
		}
	}

	// the key is free again afterwards
	if v, _ := g.do(context.Background(), "k", func() (int, error) { return 7, nil }); v != 7 {
		t.Fatalf("second round = %d", v)
	}
	// errors and panics are delivered, not crashed
	if _, err := g.do(context.Background(), "p", func() (int, error) { panic("boom") }); err == nil {
		t.Fatal("panic must be an error")
	}
	want := errors.New("x")
	if _, err := g.do(context.Background(), "e", func() (int, error) { return 0, want }); !errors.Is(err, want) {
		t.Fatalf("err = %v", err)
	}
}

// "es" is just another language to the service: one lookup per language, the metadata client
// decides which marketplaces answer it.
func TestSearchAndDiscoverSpanishLanguage(t *testing.T) {
	e := newEnv(t)
	e.meta.search["us|en|dune"] = books("E1", "E2")
	e.meta.search["us|es|dune"] = books("S1", "SHARED")
	e.meta.discover["us|es|0"] = books("D1")
	cfg := cfgFor(func(c *userconfig.Config) { c.Languages = []string{"es", "en"} })

	got, err := e.svc.Search(context.Background(), cfg, "dune")
	if err != nil || asinsOf(got) != "S1,E1,SHARED,E2" {
		t.Fatalf("search: %s, %v", asinsOf(got), err)
	}
	got, err = e.svc.Discover(context.Background(), cfg, 0)
	if err != nil || asinsOf(got) != "D1" {
		t.Fatalf("discover: %s, %v", asinsOf(got), err)
	}
}
