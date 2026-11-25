package service

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/metadata"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

type fakeUserStore struct {
	mu          sync.Mutex
	clientFiles map[string][]model.File
	userAvail   map[string]bool // fingerprint|provider|hash
	queried     []string        // fingerprints asked for
	durations   map[string]map[string]int64
}

func newFakeUserStore() *fakeUserStore {
	return &fakeUserStore{clientFiles: map[string][]model.File{}, userAvail: map[string]bool{}}
}

func (u *fakeUserStore) GetRelease(context.Context, string) (*model.Release, error) { return nil, nil }
func (u *fakeUserStore) InsertReleaseIfAbsent(context.Context, model.Release) (bool, error) {
	return false, nil
}
func (u *fakeUserStore) ClientFiles(_ context.Context, hs []string) (map[string][]model.File, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	out := map[string][]model.File{}
	for _, h := range hs {
		if f, ok := u.clientFiles[h]; ok {
			out[h] = f
		}
	}
	return out, nil
}
func (u *fakeUserStore) SaveClientFiles(context.Context, string, []model.File) ([]model.File, bool, error) {
	return nil, false, nil
}
func (u *fakeUserStore) GetUserAvailability(_ context.Context, fp, provider string, hs []string, _ time.Time) (map[string]bool, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	u.queried = append(u.queried, fp)
	out := map[string]bool{}
	for _, h := range hs {
		if c, ok := u.userAvail[fp+"|"+provider+"|"+h]; ok {
			out[h] = c
		}
	}
	return out, nil
}
func (u *fakeUserStore) SetUserAvailability(context.Context, string, string, string, bool, time.Time) error {
	return nil
}

func (u *fakeUserStore) FileDurations(_ context.Context, hs []string) (map[string]map[string]int64, error) {
	u.mu.Lock()
	defer u.mu.Unlock()
	out := map[string]map[string]int64{}
	for _, h := range hs {
		if d, ok := u.durations[h]; ok {
			out[h] = d
		}
	}
	return out, nil
}

func withUserStore(us *fakeUserStore) func(*env, *Deps) {
	return func(_ *env, d *Deps) { d.UserStore = us }
}

func bookFor(t *testing.T, e *env, keys ...UserKey) *BookPage {
	t.Helper()
	p, err := e.svc.BookFor(context.Background(), BookRequest{ASIN: testASIN, Region: "us", Languages: []string{"en"}, Keys: keys})
	if err != nil {
		t.Fatalf("BookFor: %v", err)
	}
	return p
}

func TestBookForAddsReportedDurations(t *testing.T) {
	us := newFakeUserStore()
	e := newEnv(t, withUserStore(us))
	e.seedFresh(model.Release{InfoHash: h(1), Title: "Frank Herbert - Dune m4b", Size: 1, Magnet: "magnet:?xt=urn:btih:" + h(1), Parsed: model.ParsedTitle{Format: "m4b", Language: "en"}})
	e.store.files[h(1)] = []model.File{{Index: 4, Path: `d\2.mp3`, Size: 2}, {Index: 3, Path: "d/1.mp3", Size: 1}}
	us.clientFiles[h(1)] = []model.File{{Index: 1, Path: "d/2.mp3", Size: 2}, {Index: 0, Path: "d/1.mp3", Size: 1}}
	us.durations = map[string]map[string]int64{h(1): {"d/2.mp3": 7000}, h(9): {"d/1.mp3": 1}}

	r := bookFor(t, e).Releases[0]
	got := func(fs []model.File) map[string]int64 {
		m := map[string]int64{}
		for _, f := range fs {
			m[f.Path] = f.DurationMs
		}
		return m
	}
	if g := got(r.TorrentFiles); g["d/2.mp3"] != 7000 || g["d/1.mp3"] != 0 {
		t.Fatalf("torrent_files: %v", g)
	}
	if g := got(r.Files); g[`d\2.mp3`] != 7000 || g["d/1.mp3"] != 0 { // matched by normalised path
		t.Fatalf("files: %v", g)
	}
	// The stored lists are not touched (copies are filled).
	if e.store.files[h(1)][0].DurationMs != 0 || us.clientFiles[h(1)][0].DurationMs != 0 {
		t.Fatal("durations leaked into the stored file lists")
	}
	// Without a duration store (or without reports) the page is the same, durations 0.
	us.durations = nil
	if g := got(bookFor(t, e).Releases[0].TorrentFiles); g["d/2.mp3"] != 0 {
		t.Fatalf("%v", g)
	}
}

func TestBookForListsEveryReleaseWithoutKeys(t *testing.T) {
	us := newFakeUserStore()
	e := newEnv(t, withUserStore(us))
	r1 := model.Release{InfoHash: h(1), Title: "Frank Herbert - Dune m4b", Size: 1, Magnet: "magnet:?xt=urn:btih:" + h(1), Parsed: model.ParsedTitle{Format: "m4b", Language: "en"}}
	r2 := model.Release{InfoHash: h(2), Title: "Frank Herbert - Dune mp3", Size: 1, Magnet: "magnet:?xt=urn:btih:" + h(2), Parsed: model.ParsedTitle{Format: "mp3", Language: "en"}}
	e.seedFresh(r1, r2)
	e.store.files[h(1)] = []model.File{{Index: 4, Path: "d/2.mp3", Size: 2}, {Index: 3, Path: "d/1.mp3", Size: 1}, {Index: 5, Path: "d/cover.jpg", Size: 1}}
	us.clientFiles[h(1)] = []model.File{{Index: 1, Path: "d/2.mp3", Size: 2}, {Index: 0, Path: "d/1.mp3", Size: 1}}
	e.tb.cached[h(1)] = debrid.Cached{}

	p := bookFor(t, e)
	if !eq(hashesOfPage(p), []string{h(1), h(2)}) {
		t.Fatalf("releases = %v", hashesOfPage(p))
	}
	if e.tb.checkCount() != 0 || len(p.Releases[0].Cached) != 0 {
		t.Fatalf("without a key nothing is checked and the state is unknown: checks=%d cached=%v", e.tb.checkCount(), p.Releases[0].Cached)
	}
	r := p.Releases[0]
	if !reflect.DeepEqual(pathsOfFiles(r.Files), []string{"d/1.mp3", "d/2.mp3"}) || r.Files[0].Index != 3 {
		t.Fatalf("files (debrid index): %+v", r.Files)
	}
	if !reflect.DeepEqual(pathsOfFiles(r.TorrentFiles), []string{"d/1.mp3", "d/2.mp3"}) || r.TorrentFiles[0].Index != 0 {
		t.Fatalf("torrent_files (metainfo index) are kept apart: %+v", r.TorrentFiles)
	}
	if p.SearchedAt.IsZero() {
		t.Fatal("searched_at")
	}
}

func TestBookForCachedStateIsPerCaller(t *testing.T) {
	us := newFakeUserStore()
	e := newEnv(t, withUserStore(us))
	r1 := model.Release{InfoHash: h(1), Title: "Frank Herbert - Dune m4b", Magnet: "m", Parsed: model.ParsedTitle{Format: "m4b", Language: "en"}}
	r2 := model.Release{InfoHash: h(2), Title: "Frank Herbert - Dune mp3", Magnet: "m", Parsed: model.ParsedTitle{Format: "mp3", Language: "en"}}
	e.seedFresh(r1, r2)
	e.tb.cached[h(2)] = debrid.Cached{Files: []model.File{{Index: 0, Path: "a.mp3", Size: 1}}}
	us.userAvail["fpA|realdebrid|"+h(1)] = true // key A's own probe of h(1)

	// Caller A: TorBox key (checked with THAT key) + RealDebrid key (read by fingerprint only).
	p := bookFor(t, e, UserKey{Provider: "torbox", Fingerprint: "fpTB", APIKey: tbKey}, UserKey{Provider: "realdebrid", Fingerprint: "fpA"})
	if !eq(hashesOfPage(p), []string{h(1), h(2)}) { // both cached somewhere for A: m4b before mp3
		t.Fatalf("order: %v", hashesOfPage(p))
	}
	want := map[string]map[string]bool{h(2): {"torbox": true}, h(1): {"torbox": false, "realdebrid": true}}
	for _, r := range p.Releases {
		if !reflect.DeepEqual(r.Cached, want[r.Release.InfoHash]) {
			t.Errorf("%s cached = %v, want %v", r.Release.InfoHash[:2], r.Cached, want[r.Release.InfoHash])
		}
	}
	if !reflect.DeepEqual(e.tb.keysSeen, []string{tbKey}) {
		t.Fatalf("TorBox was asked with %v: only the caller's key", e.tb.keysSeen)
	}
	if len(e.rd.keysSeen) != 0 || e.rd.checkCount() != 0 {
		t.Fatal("RealDebrid has no batch check: no provider call, no key")
	}
	if _, ok := e.store.availOf("realdebrid", h(1)); ok {
		t.Fatal("RealDebrid state must never reach the shared availability table")
	}
	if a, ok := e.store.availOf("torbox", h(2)); !ok || !a.Cached {
		t.Fatal("TorBox state is shared")
	}

	// Caller B holds another RealDebrid key and no TorBox key: B never sees A's RealDebrid state,
	// and the stored TorBox state is not shown to someone without a key ("unknown").
	p = bookFor(t, e, UserKey{Provider: "realdebrid", Fingerprint: "fpB"})
	for _, r := range p.Releases {
		if len(r.Cached) != 0 {
			t.Fatalf("B sees %v for %s", r.Cached, r.Release.InfoHash[:2])
		}
	}
	if !reflect.DeepEqual(us.queried, []string{"fpA", "fpB"}) {
		t.Fatalf("per-key lookups = %v", us.queried)
	}
}

func TestBookForPackVisibleThroughClientFiles(t *testing.T) {
	us := newFakeUserStore()
	e := newEnv(t, withUserStore(us))
	e.seedDCC(matchSpec{packRel(1), true})
	cfg := cfgFor(func(c *userconfig.Config) { c.ShowUncached = true }, "realdebrid")
	if p := e.mustBook(cfg); len(p.Releases) != 0 {
		t.Fatal("Stremio: a pack with unknown debrid files stays hidden")
	}
	if p := bookFor(t, e); len(p.Releases) != 0 {
		t.Fatal("a pack nobody reported files for stays hidden")
	}
	us.clientFiles[h(1)] = dccFiles()
	p := bookFor(t, e)
	if len(p.Releases) != 1 || !p.Releases[0].Collection {
		t.Fatalf("%+v", p.Releases)
	}
	r := p.Releases[0]
	if len(r.Files) != 0 || len(r.TorrentFiles) != 1 || r.TorrentFiles[0].Index != 1 {
		t.Fatalf("only this book's files, with the metainfo index: files=%v torrent=%v", r.Files, r.TorrentFiles)
	}
	if p := e.mustBook(cfg); len(p.Releases) != 0 {
		t.Fatal("Stremio never uses client files")
	}
}

func TestSharedCacheProvidersOnly(t *testing.T) {
	if !SharedCache("torbox") || SharedCache("realdebrid") || SharedCache("") {
		t.Fatal("only TorBox has a cache that is the same for every account")
	}
}

func TestStremioResolveNeverWritesRealDebridToTheSharedTable(t *testing.T) {
	e := newEnv(t)
	e.seedFresh(model.Release{InfoHash: h(1), Title: "Frank Herbert - Dune", Magnet: "m"})
	e.rd.resolveRes = debrid.ResolveResult{URL: "https://x.real-debrid.com/y"}
	e.tb.resolveRes = debrid.ResolveResult{URL: "https://x.tb-cdn.st/y"}
	cfg := cfgFor(nil, "torbox", "realdebrid")
	for _, p := range []string{"realdebrid", "torbox"} {
		if _, err := e.svc.Resolve(context.Background(), cfg, p, h(1), 1); err != nil {
			t.Fatal(err)
		}
	}
	if _, ok := e.store.availOf("realdebrid", h(1)); ok {
		t.Fatal("RealDebrid availability was written to the shared table")
	}
	if a, ok := e.store.availOf("torbox", h(1)); !ok || !a.Cached {
		t.Fatal("TorBox availability is still learned")
	}
}

func TestEnsureBook(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	b, err := e.svc.EnsureBook(ctx, "us", testASIN)
	if err != nil || b.ASIN != testASIN || e.meta.count("book") != 1 {
		t.Fatalf("%+v %v calls=%d", b, err, e.meta.count("book"))
	}
	if rec, _ := e.store.GetBook(ctx, testASIN); rec == nil {
		t.Fatal("not stored in the catalog")
	}
	if _, err := e.svc.EnsureBook(ctx, "us", testASIN); err != nil || e.meta.count("book") != 1 {
		t.Fatalf("a stored book is not fetched again: calls=%d", e.meta.count("book"))
	}
	if _, err := e.svc.EnsureBook(ctx, "us", "B000000000"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("unknown ASIN: %v", err)
	}
	if _, err := e.svc.EnsureBook(ctx, "us", "bad"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("malformed ASIN: %v", err)
	}
	e.meta.bookErr = errors.New("audible down")
	if _, err := e.svc.EnsureBook(ctx, "us", "B000000001"); err == nil || errors.Is(err, ErrNotFound) {
		t.Fatalf("an outage is not 'not found': %v", err)
	}
}

type chapterMeta struct {
	*fakeMeta
	mu    sync.Mutex
	lists map[string]metadata.ChapterList // by region
	err   error
	asked []string
}

func (c *chapterMeta) ChapterList(_ context.Context, region, asin string) (metadata.ChapterList, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.asked = append(c.asked, region)
	if c.err != nil {
		return metadata.ChapterList{}, c.err
	}
	if l, ok := c.lists[region]; ok {
		return l, nil
	}
	return metadata.ChapterList{}, fmt.Errorf("fake: %w", metadata.ErrNotFound)
}

func TestChapters(t *testing.T) {
	ctx := context.Background()
	mk := func() (*env, *chapterMeta) {
		var cm *chapterMeta
		e := newEnv(t, func(e *env, d *Deps) {
			cm = &chapterMeta{fakeMeta: e.meta, lists: map[string]metadata.ChapterList{}}
			d.Metadata = cm
		})
		return e, cm
	}

	e, cm := mk()
	cm.lists["uk"] = metadata.ChapterList{RuntimeMs: 5000, IntroMs: 40, OutroMs: 50, Chapters: []metadata.Chapter{{Title: "One", StartMs: 0, LengthMs: 5000}}}
	b := dune()
	b.Region = "uk"
	e.store.books[testASIN] = BookRecord{Book: b, FetchedAt: e.clock.Now()}
	rt, chs, err := e.svc.Chapters(ctx, "us", testASIN)
	if err != nil || rt != 5000 || len(chs) != 1 || !eq(cm.asked, []string{"uk"}) {
		t.Fatalf("the stored book's region is asked first: %d %v %v asked=%v", rt, chs, err, cm.asked)
	}

	if cl, err := e.svc.ChapterList(ctx, "us", testASIN); err != nil || cl.IntroMs != 40 || cl.OutroMs != 50 {
		t.Fatalf("branding must pass through: %+v %v", cl, err)
	}

	e, cm = mk() // unknown everywhere: empty chapters, runtime from the catalog
	e.store.books[testASIN] = BookRecord{Book: dune(), FetchedAt: e.clock.Now()}
	rt, chs, err = e.svc.Chapters(ctx, "uk", testASIN)
	if err != nil || chs == nil || len(chs) != 0 || rt != 1260*60_000 || !eq(cm.asked, []string{"us", "uk"}) {
		t.Fatalf("%d %#v %v asked=%v", rt, chs, err, cm.asked)
	}

	e, cm = mk() // an outage is an error, not "no chapters"
	cm.err = metadata.ErrUpstream
	if _, _, err = e.svc.Chapters(ctx, "us", testASIN); !errors.Is(err, metadata.ErrUpstream) {
		t.Fatalf("%v", err)
	}

	e = newEnv(t) // metadata without chapter support
	if rt, chs, err = e.svc.Chapters(ctx, "us", testASIN); err != nil || len(chs) != 0 || chs == nil || rt != 0 {
		t.Fatalf("%d %v %v", rt, chs, err)
	}
	if _, _, err = e.svc.Chapters(ctx, "us", "bad"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("%v", err)
	}
}
