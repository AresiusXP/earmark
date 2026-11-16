package service

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"testing"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

const dccPackTitle = "Dungeon Crawler Carl Books 1-8 - Matt Dinniman"

func dccBook() model.Book {
	return model.Book{ASIN: testASIN, Title: "Dungeon Crawler Carl", Authors: []string{"Matt Dinniman"},
		Narrators: []string{"Jeff Hays"}, Language: "en", RuntimeMin: 700, Region: "us",
		Series: "Dungeon Crawler Carl", SeriesPosition: "1"}
}

func dccFiles() []model.File {
	pack := "Dungeon Crawler Carl Books 1-8 - Matt Dinniman/"
	return []model.File{
		{Index: 0, Path: pack + "cover.jpg", Size: 1},
		{Index: 1, Path: pack + "Dungeon Crawler Carl, Book 1.m4b", Size: 400},
		{Index: 2, Path: pack + "Carl's Doomsday Scenario Dungeon Crawler Carl, Book 2.m4b", Size: 900},
		{Index: 3, Path: pack + "The Princess Who Saved Herself Dungeon Crawler Carl, Book 3.m4b", Size: 800},
	}
}

func packRel(n int) model.Release {
	return model.Release{InfoHash: h(n), Title: dccPackTitle, Size: 20 << 30, Seeders: 5,
		Magnet: "magnet:?xt=urn:btih:" + h(n), Parsed: model.ParsedTitle{Format: "m4b", Language: "en"}}
}

func singleRel(n int) model.Release {
	return model.Release{InfoHash: h(n), Title: "Matt Dinniman - Dungeon Crawler Carl", Size: 500 << 20, Seeders: 1,
		Magnet: "magnet:?xt=urn:btih:" + h(n), Parsed: model.ParsedTitle{Format: "m4b", Language: "en"}}
}

type matchSpec struct {
	rel  model.Release
	coll bool
}

// seedDCC stores the DCC book and links the releases (collection flags as given, descending scores).
func (e *env) seedDCC(specs ...matchSpec) {
	e.t.Helper()
	e.store.mu.Lock()
	defer e.store.mu.Unlock()
	e.store.books[testASIN] = BookRecord{Book: dccBook(), FetchedAt: e.clock.Now()}
	e.store.searched[testASIN] = e.clock.Now()
	var ms []Match
	for i, s := range specs {
		e.store.releases[s.rel.InfoHash] = s.rel
		ms = append(ms, Match{InfoHash: s.rel.InfoHash, Score: 0.9 - float64(i)*0.01, Collection: s.coll})
	}
	e.store.matches[testASIN] = ms
}

func pathsOfFiles(fs []model.File) []string {
	out := []string{}
	for _, f := range fs {
		out = append(out, f.Path)
	}
	return out
}

func TestBookCollection(t *testing.T) {
	pack := "Dungeon Crawler Carl Books 1-8 - Matt Dinniman/"
	t.Run("a cached pack shows only this book's files", func(t *testing.T) {
		e := newEnv(t)
		e.seedDCC(matchSpec{packRel(1), true})
		e.tb.cached[h(1)] = debrid.Cached{Files: dccFiles()}
		p := e.mustBook(cfgFor(nil, "torbox"))
		if len(p.Releases) != 1 {
			t.Fatalf("releases = %v", hashesOfPage(p))
		}
		r := p.Releases[0]
		if !r.Collection || !reflect.DeepEqual(pathsOfFiles(r.Files), []string{pack + "Dungeon Crawler Carl, Book 1.m4b"}) || r.Files[0].Index != 1 {
			t.Fatalf("view = %+v", r)
		}
		if len(e.store.filesOf(h(1))) != 4 {
			t.Fatal("the whole file list must stay stored")
		}
	})
	t.Run("stored files are enough (provider without cache API)", func(t *testing.T) {
		e := newEnv(t)
		e.seedDCC(matchSpec{packRel(1), true})
		e.store.files[h(1)] = dccFiles()
		p := e.mustBook(cfgFor(nil, "realdebrid"))
		if len(p.Releases) != 1 || !p.Releases[0].Collection || len(p.Releases[0].Files) != 1 {
			t.Fatalf("%+v", p.Releases)
		}
	})
	t.Run("unknown files: dropped", func(t *testing.T) {
		for name, setup := range map[string]func(e *env){
			"cached without file list": func(e *env) { e.tb.cached[h(1)] = debrid.Cached{} },
			"not cached anywhere":      func(e *env) {},
		} {
			t.Run(name, func(t *testing.T) {
				e := newEnv(t)
				e.seedDCC(matchSpec{packRel(1), true})
				setup(e)
				p := e.mustBook(cfgFor(func(c *userconfig.Config) { c.ShowUncached = true }, "torbox"))
				if len(p.Releases) != 0 {
					t.Fatalf("pack with unknown files shown: %+v", p.Releases)
				}
			})
		}
	})
	t.Run("no file belongs to the book: dropped", func(t *testing.T) {
		e := newEnv(t)
		e.seedDCC(matchSpec{packRel(1), true})
		e.tb.cached[h(1)] = debrid.Cached{Files: []model.File{
			{Index: 0, Path: pack + "Carl's Doomsday Scenario Dungeon Crawler Carl, Book 2.m4b", Size: 900},
			{Index: 1, Path: pack + "The Princess Who Saved Herself Dungeon Crawler Carl, Book 3.m4b", Size: 800},
		}}
		if p := e.mustBook(cfgFor(nil, "torbox")); len(p.Releases) != 0 {
			t.Fatalf("%+v", p.Releases)
		}
	})
	t.Run("an uncached pack with stored files follows ShowUncached", func(t *testing.T) {
		for _, show := range []bool{false, true} {
			e := newEnv(t)
			e.seedDCC(matchSpec{packRel(1), true})
			e.store.files[h(1)] = dccFiles()
			p := e.mustBook(cfgFor(func(c *userconfig.Config) { c.ShowUncached = show }, "torbox"))
			if got := len(p.Releases) == 1; got != show {
				t.Fatalf("show=%v: releases = %v", show, hashesOfPage(p))
			}
		}
	})
	t.Run("chapter folders come out in natural order", func(t *testing.T) {
		e := newEnv(t)
		e.seedDCC(matchSpec{packRel(1), true})
		b1 := pack + "Dungeon Crawler Carl Book 1/"
		b2 := pack + "Dungeon Crawler Carl Book 2/"
		e.store.files[h(1)] = []model.File{
			{Index: 0, Path: b1 + "Chapter 10.mp3"}, {Index: 1, Path: b2 + "Chapter 01.mp3"},
			{Index: 2, Path: b1 + "Chapter 02.mp3"}, {Index: 3, Path: b1 + "Chapter 01.mp3"},
		}
		p := e.mustBook(cfgFor(func(c *userconfig.Config) { c.ShowUncached = true }, "realdebrid"))
		want := []string{b1 + "Chapter 01.mp3", b1 + "Chapter 02.mp3", b1 + "Chapter 10.mp3"}
		if len(p.Releases) != 1 || !reflect.DeepEqual(pathsOfFiles(p.Releases[0].Files), want) {
			t.Fatalf("%+v", p.Releases)
		}
	})
	t.Run("a dropped pack does not take a slot", func(t *testing.T) {
		e := newEnv(t)
		e.seedDCC(matchSpec{packRel(1), true}, matchSpec{singleRel(2), false})
		e.tb.cached[h(1)] = debrid.Cached{Files: []model.File{{Index: 0, Path: pack + "Book 2.m4b"}}}
		e.tb.cached[h(2)] = debrid.Cached{}
		p := e.mustBook(cfgFor(func(c *userconfig.Config) { c.MaxReleases = 1 }, "torbox"))
		if got := hashesOfPage(p); !eq(got, []string{h(2)}) || p.Releases[0].Collection {
			t.Fatalf("%v", got)
		}
	})
	t.Run("a book's own release ranks before an equally good pack", func(t *testing.T) {
		e := newEnv(t)
		// The pack is stored first with the higher score; both are cached with the same format.
		e.seedDCC(matchSpec{packRel(1), true}, matchSpec{singleRel(2), false})
		e.store.mu.Lock()
		e.store.matches[testASIN][1].Score = e.store.matches[testASIN][0].Score
		e.store.mu.Unlock()
		e.tb.cached[h(1)] = debrid.Cached{Files: dccFiles()}
		e.tb.cached[h(2)] = debrid.Cached{}
		p := e.mustBook(cfgFor(nil, "torbox"))
		if got := hashesOfPage(p); !eq(got, []string{h(2), h(1)}) {
			t.Fatalf("%v", got)
		}
		if p.Releases[0].Collection || !p.Releases[1].Collection {
			t.Fatalf("flags: %+v", p.Releases)
		}
	})
}

func TestRankCollectionTieBreak(t *testing.T) {
	mk := func(n int, coll bool, seeders int) scored {
		return scored{Candidate: Candidate{Release: model.Release{InfoHash: h(n), Seeders: seeders}, Score: 0.8, Collection: coll}}
	}
	got := rankReleases([]scored{mk(1, true, 100), mk(2, false, 1), mk(3, true, 50), mk(4, false, 2)}, []string{"en"}, "m4b")
	var order []string
	for _, g := range got {
		order = append(order, g.Release.InfoHash)
	}
	if !eq(order, []string{h(4), h(2), h(1), h(3)}) {
		t.Fatalf("order = %v", order)
	}
}

func TestSearchPersistsCollectionFlag(t *testing.T) {
	e := newEnv(t, func(e *env, d *Deps) {
		e.meta.books[testASIN] = dccBook()
		e.src.rels = []model.Release{
			{InfoHash: h(1), Title: dccPackTitle, Size: 20 << 30, Seeders: 3},
			{InfoHash: h(2), Title: "Matt Dinniman - Dungeon Crawler Carl [M4B]", Size: 500 << 20, Seeders: 1},
			{InfoHash: h(3), Title: "Dungeon Crawler Carl Books 1-8 - Jane Doe", Size: 20 << 30},
		}
	})
	e.tb.cached[h(1)] = debrid.Cached{Files: dccFiles()}
	e.tb.cached[h(2)] = debrid.Cached{}
	p := e.mustBook(cfgFor(nil, "torbox"))
	e.store.mu.Lock()
	got := map[string]bool{}
	for _, m := range e.store.matches[testASIN] {
		got[m.InfoHash] = m.Collection
	}
	e.store.mu.Unlock()
	if !reflect.DeepEqual(got, map[string]bool{h(1): true, h(2): false}) {
		t.Fatalf("stored matches = %v", got)
	}
	if hs := hashesOfPage(p); !eq(hs, []string{h(2), h(1)}) && !eq(hs, []string{h(1), h(2)}) {
		t.Fatalf("page = %v", hs)
	}
	for _, r := range p.Releases {
		if r.Collection != (r.Release.InfoHash == h(1)) {
			t.Errorf("collection flag of %s = %v", r.Release.InfoHash, r.Collection)
		}
	}
}

func TestStreamsCollection(t *testing.T) {
	ctx := context.Background()
	cfg := cfgFor(nil, "torbox")
	setup := func(t *testing.T) *env {
		e := newEnv(t)
		e.seedDCC(matchSpec{packRel(1), true}, matchSpec{singleRel(2), false})
		e.store.files[h(1)] = dccFiles()
		e.store.files[h(2)] = []model.File{{Index: 9, Path: "x.m4b"}}
		return e
	}
	t.Run("the book's own file", func(t *testing.T) {
		e := setup(t)
		got, err := e.svc.Streams(ctx, cfg, VideoRef{ASIN: testASIN, InfoHash: h(1), FileIndex: 1})
		if err != nil || len(got) != 1 || got[0].File == nil || got[0].File.Index != 1 {
			t.Fatalf("%+v %v", got, err)
		}
	})
	for name, idx := range map[string]int{
		"another book's file":   2,
		"a non-audio file":      0,
		"an unknown file index": 99,
		"AutoFile on a pack":    AutoFile,
	} {
		t.Run(name, func(t *testing.T) {
			e := setup(t)
			if _, err := e.svc.Streams(ctx, cfg, VideoRef{ASIN: testASIN, InfoHash: h(1), FileIndex: idx}); !errors.Is(err, ErrNotFound) {
				t.Fatalf("err = %v, want ErrNotFound", err)
			}
		})
	}
	t.Run("plain releases are unaffected", func(t *testing.T) {
		e := setup(t)
		got, err := e.svc.Streams(ctx, cfg, VideoRef{ASIN: testASIN, InfoHash: h(2), FileIndex: 9})
		if err != nil || len(got) != 1 || got[0].File == nil {
			t.Fatalf("%+v %v", got, err)
		}
	})
}

func TestResolveAutoFileGuardsPacks(t *testing.T) {
	ctx := context.Background()
	cfg := cfgFor(nil, "torbox")
	t.Run("stored pack files", func(t *testing.T) {
		e := newEnv(t)
		e.store.files[h(1)] = dccFiles()
		_, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), AutoFile)
		if !errors.Is(err, ErrNotFound) || len(e.tb.resolves) != 0 {
			t.Fatalf("err=%v resolves=%v", err, e.tb.resolves)
		}
	})
	t.Run("pack files learned from a cache check", func(t *testing.T) {
		e := newEnv(t)
		e.tb.cached[h(1)] = debrid.Cached{Files: dccFiles()}
		_, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), AutoFile)
		if !errors.Is(err, ErrNotFound) || len(e.tb.resolves) != 0 {
			t.Fatalf("err=%v resolves=%v", err, e.tb.resolves)
		}
		if len(e.store.filesOf(h(1))) != 4 {
			t.Fatal("the learned files should still be stored")
		}
	})
	t.Run("an explicit file of a pack still resolves", func(t *testing.T) {
		e := newEnv(t)
		e.store.files[h(1)] = dccFiles()
		e.tb.resolveRes = debrid.ResolveResult{URL: "https://dl/x"}
		if _, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), 1); err != nil || e.tb.resolves[0].FileIndex != 1 {
			t.Fatalf("%v %+v", err, e.tb.resolves)
		}
	})
	t.Run("a normal multi-file book is not a pack", func(t *testing.T) {
		e := newEnv(t)
		var fs []model.File
		for i := 1; i <= 12; i++ {
			fs = append(fs, model.File{Index: i, Path: fmt.Sprintf("Dune/%02d - Chapter.mp3", i), Size: int64(i)})
		}
		fs = append(fs, model.File{Index: 20, Path: "Dune/Part 3.mp3", Size: 99})
		e.store.files[h(1)] = fs
		e.tb.resolveRes = debrid.ResolveResult{URL: "u"}
		if _, err := e.svc.Resolve(ctx, cfg, "torbox", h(1), AutoFile); err != nil || e.tb.resolves[0].FileIndex != 20 {
			t.Fatalf("%v %+v", err, e.tb.resolves)
		}
	})
}
