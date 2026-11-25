package appapi

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/accounts"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/appupdate"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/library"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/metadata"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/sources"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/store"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/testpg"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

// These tests run the whole /v1 stack (auth middleware, real accounts, real Postgres store, the real
// catalog service) with fake metadata, sources and debrid providers.

const (
	duneASIN  = "B002V1OF70"
	otherASIN = "B0AAAAAAAA"
)

type metaFake struct {
	mu    sync.Mutex
	books map[string]model.Book
	calls int
	chap  metadata.ChapterList
}

func (m *metaFake) Search(_ context.Context, _, _, q string) ([]model.Book, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []model.Book
	for _, b := range m.books {
		if strings.Contains(strings.ToLower(b.Title), strings.ToLower(q)) {
			out = append(out, b)
		}
	}
	return out, nil
}
func (m *metaFake) Discover(context.Context, string, string, int) ([]model.Book, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []model.Book
	for _, b := range m.books {
		out = append(out, b)
	}
	return out, nil
}
func (m *metaFake) Book(_ context.Context, _, asin string) (model.Book, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.calls++
	if b, ok := m.books[asin]; ok {
		return b, nil
	}
	return model.Book{}, fmt.Errorf("fake: %w", metadata.ErrNotFound)
}
func (m *metaFake) ChapterList(_ context.Context, _, asin string) (metadata.ChapterList, error) {
	if asin != duneASIN {
		return metadata.ChapterList{}, metadata.ErrNotFound
	}
	return m.chap, nil
}

type srcFake struct{ rels []model.Release }

func (s *srcFake) Name() string { return "fake" }
func (s *srcFake) Search(context.Context, sources.Query) ([]model.Release, error) {
	return append([]model.Release(nil), s.rels...), nil
}

type appEnv struct {
	*env
	db   *store.Postgres
	meta *metaFake
	rd   *rdFake
	tb   *tbFake
	logs *syncBuf
	dir  string
}

func newAppEnv(t *testing.T) *appEnv { return newAppEnvDir(t, "") }

// newAppEnvDir is newAppEnv whose app update bundle is read from dir (empty: an empty directory).
func newAppEnvDir(t *testing.T, dir string) *appEnv {
	t.Helper()
	db, err := store.Open(context.Background(), testpg.URL(t))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	clk := &clock{t: time.Now().UTC()}
	logs := &syncBuf{}
	log := slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	rd := &rdFake{provBase: newProvBase("realdebrid")}
	rd.files = []model.File{{Index: 1, Path: "d/01.mp3", Size: 10}}
	tb := &tbFake{provBase: newProvBase("torbox"), cached: map[string]debrid.Cached{}}
	provs := debrid.NewRegistry(rd, tb)
	acc, err := accounts.New(accounts.Config{Secret: secret, InstancePassword: "pw", Store: db, Now: clk.Now})
	if err != nil {
		t.Fatal(err)
	}
	keys, err := userkeys.New(userkeys.Config{Secret: secret, Store: db, Providers: provs, Logger: log})
	if err != nil {
		t.Fatal(err)
	}
	dune := model.Book{ASIN: duneASIN, Title: "Dune", Authors: []string{"Frank Herbert"}, Language: "en", RuntimeMin: 1260, Region: "us"}
	other := model.Book{ASIN: otherASIN, Title: "Emma", Authors: []string{"Jane Austen"}, Language: "en", RuntimeMin: 600, Region: "us"}
	meta := &metaFake{books: map[string]model.Book{duneASIN: dune, otherASIN: other},
		chap: metadata.ChapterList{RuntimeMs: 75_000_000, Chapters: []metadata.Chapter{{Title: "Opening Credits", StartMs: 0, LengthMs: 60_970}, {Title: "Book One", StartMs: 60_970, LengthMs: 1000}}}}
	src := &srcFake{rels: []model.Release{
		{InfoHash: h40('a'), Title: "Frank Herbert - Dune (Unabridged) [m4b] 64kbps", Source: "fake", Size: 600 << 20, Seeders: 10, Magnet: "magnet:?xt=urn:btih:" + h40('a')},
		{InfoHash: h40('b'), Title: "Frank Herbert - Dune mp3 128 kbps", Source: "fake", Size: 600 << 20, Seeders: 20, Magnet: "magnet:?xt=urn:btih:" + h40('b')},
	}}
	svc := service.New(service.Deps{Metadata: meta, Sources: []sources.Source{src}, Store: db, UserStore: db, Providers: provs, Logger: log})
	api := New(Deps{Accounts: acc, Keys: keys, Version: "9.9.9", Logger: log})
	if dir == "" {
		dir = t.TempDir()
	}
	api.MountApp(AppDeps{Catalog: svc, Store: db, Library: db, Providers: provs, Update: appupdate.Load(dir, log), PublicURL: "https://audiobooks.example", Logger: log})
	e := &env{t: t, api: api, keys: keys, clk: clk}
	return &appEnv{env: e, db: db, meta: meta, rd: rd, tb: tb, logs: logs, dir: dir}
}

// req is env.do without the no-store assertion (catalog responses are private, max-age=300).
func (e *appEnv) req(method, path, token string, body any) resp {
	e.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	default:
		raw, err := json.Marshal(b)
		if err != nil {
			e.t.Fatal(err)
		}
		rd = bytes.NewReader(raw)
	}
	r := httptest.NewRequest(method, path, rd)
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	e.api.ServeHTTP(rec, r)
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		e.t.Errorf("%s %s sends Access-Control-Allow-Origin %q", method, path, got)
	}
	return resp{rec}
}

func (r resp) cacheControl() string { return r.Header().Get("Cache-Control") }

func (e *appEnv) setKey(s session, provider, key string) {
	e.t.Helper()
	e.do("PUT", "/v1/me/debrid/"+provider, s.access, map[string]any{"api_key": key}).status(e.t, 200)
}

func (e *appEnv) twoUsers() (session, session) {
	admin := e.bootstrap()
	return admin, e.join(admin, "Bob")
}

func TestNewRoutesRequireAuthExceptAppUpdate(t *testing.T) {
	e := newAppEnv(t)
	admin := e.bootstrap()
	h := h40('a')
	routes := []struct{ method, path string }{
		{"GET", "/v1/search?q=dune"}, {"GET", "/v1/discover"}, {"GET", "/v1/books/" + duneASIN}, {"GET", "/v1/books/" + duneASIN + "/chapters"},
		{"POST", "/v1/releases/" + h + "/prepare"}, {"GET", "/v1/releases/" + h + "/files/0/download?provider=torbox"},
		{"HEAD", "/v1/releases/" + h + "/files/0/download?provider=torbox"}, {"POST", "/v1/releases/" + h + "/torrent-files"},
		{"GET", "/v1/me/library"}, {"GET", "/v1/me/library/" + duneASIN}, {"PUT", "/v1/me/library/" + duneASIN}, {"DELETE", "/v1/me/library/" + duneASIN},
		{"GET", "/v1/me/library/" + duneASIN + "/bookmarks"}, {"POST", "/v1/me/library/" + duneASIN + "/bookmarks"},
		{"DELETE", "/v1/me/library/" + duneASIN + "/bookmarks/x"},
	}
	for _, rt := range routes {
		t.Run(rt.method+" "+rt.path, func(t *testing.T) {
			r := e.req(rt.method, rt.path, "", nil)
			r.status(t, 401)
			if r.cacheControl() != "no-store" {
				t.Fatalf("Cache-Control = %q", r.cacheControl())
			}
			e.req(rt.method, rt.path, "garbage", nil).status(t, 401)
		})
	}
	_ = admin
	e.req("GET", "/v1/app/update", "", nil).status(t, 200)
	e.req("GET", "/v1/app/update/apk", "", nil).errCode(t, 404, "not_found")
}

func TestSearchDiscoverAndCaching(t *testing.T) {
	e := newAppEnv(t)
	admin := e.bootstrap()

	r := e.req("GET", "/v1/search?q=dune", admin.access, nil)
	r.status(t, 200)
	books := r.json(t)["books"].([]any)
	if len(books) != 1 || books[0].(map[string]any)["asin"] != duneASIN || books[0].(map[string]any)["runtime_min"] != float64(1260) {
		t.Fatalf("%s", r.Body.String())
	}
	if r.cacheControl() != "private, max-age=300" {
		t.Fatalf("Cache-Control = %q", r.cacheControl())
	}
	empty := e.req("GET", "/v1/search?q=nothing-matches", admin.access, nil)
	empty.status(t, 200)
	if empty.json(t)["books"] == nil {
		t.Fatal("books must be [] not null")
	}
	d := e.req("GET", "/v1/discover?page=2", admin.access, nil)
	d.status(t, 200)
	if d.cacheControl() != "private, max-age=300" || len(d.json(t)["books"].([]any)) != 2 {
		t.Fatalf("%v %s", d.Header(), d.Body.String())
	}
	e.req("GET", "/v1/discover", admin.access, nil).status(t, 200)

	// Errors are never cacheable.
	for _, p := range []string{"/v1/search", "/v1/search?q=a", "/v1/search?q=" + strings.Repeat("x", 121), "/v1/search?q=%20%20%20", "/v1/discover?page=-1", "/v1/discover?page=x"} {
		r := e.req("GET", p, admin.access, nil)
		r.errCode(t, 400, "invalid_request")
		if r.cacheControl() != "no-store" {
			t.Errorf("%s: error Cache-Control = %q", p, r.cacheControl())
		}
	}
	// Settings drive region and language of the lookup.
	e.do("PATCH", "/v1/me", admin.access, map[string]any{"settings": map[string]any{"region": "nl", "languages": []string{"nl", "en"}}}).status(t, 200)
	e.req("GET", "/v1/search?q=dune", admin.access, nil).status(t, 200)
}

func TestBookPageIsComputedForTheCaller(t *testing.T) {
	e := newAppEnv(t)
	admin, bob := e.twoUsers()
	carol := e.join(admin, "Carol")
	const aTB, aRD, bRD = "tb-key-of-alice-0001", "rd-key-of-alice-0001", "rd-key-of-bob-00002"
	e.setKey(admin, "torbox", aTB)
	e.setKey(admin, "realdebrid", aRD)
	e.setKey(bob, "realdebrid", bRD)
	e.tb.cached[h40('a')] = debrid.Cached{Files: []model.File{{Index: 3, Path: "d/02.mp3", Size: 2}, {Index: 2, Path: "d/01.mp3", Size: 1}, {Index: 9, Path: "d/cover.jpg", Size: 1}}}

	page := func(s session) map[string]any {
		r := e.req("GET", "/v1/books/"+duneASIN, s.access, nil)
		r.status(t, 200)
		if r.cacheControl() != "private, max-age=300" {
			t.Fatalf("Cache-Control = %q", r.cacheControl())
		}
		for _, k := range []string{aTB, aRD, bRD} {
			if strings.Contains(r.Body.String(), k) {
				t.Fatalf("a key is in the response: %s", r.Body.String())
			}
		}
		return r.json(t)
	}
	byHash := func(m map[string]any) map[string]map[string]any {
		out := map[string]map[string]any{}
		for _, r := range m["releases"].([]any) {
			rel := r.(map[string]any)
			out[rel["info_hash"].(string)] = rel
		}
		return out
	}

	m := page(admin)
	rels := byHash(m)
	if m["book"].(map[string]any)["asin"] != duneASIN || len(rels) != 2 || m["searched_at"] == nil {
		t.Fatalf("%v", m)
	}
	a, b := rels[h40('a')], rels[h40('b')]
	if !equalJSON(a["cached"], map[string]any{"torbox": true}) || !equalJSON(b["cached"], map[string]any{"torbox": false}) {
		t.Fatalf("cached: a=%v b=%v", a["cached"], b["cached"])
	}
	if !equalJSON(a["usable"], map[string]any{"debrid": []any{"realdebrid", "torbox"}, "p2p": false}) {
		t.Fatalf("usable = %v", a["usable"])
	}
	files := a["files"].([]any)
	if len(files) != 2 || files[0].(map[string]any)["path"] != "d/01.mp3" || files[0].(map[string]any)["index"] != float64(2) {
		t.Fatalf("files = %v", files)
	}
	if a["torrent_files"] == nil || len(a["torrent_files"].([]any)) != 0 || b["files"] == nil {
		t.Fatal("files and torrent_files are [] when unknown, never null")
	}
	if a["magnet"] == "" || a["format"] != "m4b" || a["source"] != "fake" {
		t.Fatalf("%v", a)
	}
	if !equalJSON(e.tb.keysSeen, map[string]any{aTB: true}) {
		t.Fatalf("TorBox was asked with %v: only Alice's own key", e.tb.keysSeen)
	}

	// Bob has no TorBox key: unknown (no entry), even though the shared table knows TorBox's state.
	for h, rel := range byHash(page(bob)) {
		if len(rel["cached"].(map[string]any)) != 0 {
			t.Fatalf("Bob sees %v for %s", rel["cached"], h[:2])
		}
		if !equalJSON(rel["usable"], map[string]any{"debrid": []any{"realdebrid"}, "p2p": false}) {
			t.Fatalf("usable = %v", rel["usable"])
		}
	}
	// Carol has no key at all: the releases are listed all the same.
	cm := byHash(page(carol))
	if len(cm) != 2 || !equalJSON(cm[h40('a')]["usable"], map[string]any{"debrid": []any{}, "p2p": false}) {
		t.Fatalf("%v", cm)
	}
	if !equalJSON(e.tb.keysSeen, map[string]any{aTB: true}) {
		t.Fatalf("another key reached TorBox: %v", e.tb.keysSeen)
	}
	if len(e.rd.keysSeen) != 0 {
		t.Fatal("RealDebrid has no cache check: the book page must not call it")
	}

	// Alice probes b with prepare (RD answers ready at once): per-key state, visible to her key only.
	e.req("POST", "/v1/releases/"+h40('b')+"/prepare", admin.access, map[string]any{"provider": "realdebrid"}).status(t, 200)
	if got := byHash(page(admin))[h40('b')]["cached"]; !equalJSON(got, map[string]any{"torbox": false, "realdebrid": true}) {
		t.Fatalf("Alice cached = %v", got)
	}
	if got := byHash(page(bob))[h40('b')]["cached"]; len(got.(map[string]any)) != 0 {
		t.Fatalf("Bob sees Alice's probe: %v", got)
	}
	if got := byHash(page(carol))[h40('b')]["cached"]; len(got.(map[string]any)) != 0 {
		t.Fatalf("Carol sees Alice's probe: %v", got)
	}
	// A household (the same key) shares the probe.
	e.setKey(bob, "realdebrid", aRD)
	if got := byHash(page(bob))[h40('b')]["cached"]; !equalJSON(got, map[string]any{"realdebrid": true}) {
		t.Fatalf("Bob with Alice's key: %v", got)
	}
	if n := count(t, e, "availability"); n != 0 {
		t.Fatalf("shared availability rows for RealDebrid = %d", n)
	}

	// p2p: the user's own choice, never an admin switch.
	for _, tc := range []struct {
		patch map[string]any
		p2p   bool
	}{
		{map[string]any{"p2p_enabled": true}, true},
		{map[string]any{"p2p_enabled": true, "source_mode": "debrid"}, false},
		{map[string]any{"p2p_enabled": true, "source_mode": "p2p"}, true},
		{map[string]any{"p2p_enabled": false, "source_mode": "p2p"}, false},
	} {
		e.do("PATCH", "/v1/me", carol.access, map[string]any{"settings": tc.patch}).status(t, 200)
		got := byHash(page(carol))[h40('a')]["usable"].(map[string]any)["p2p"]
		if got != tc.p2p {
			t.Errorf("%v: p2p = %v, want %v", tc.patch, got, tc.p2p)
		}
	}

	// A key the provider rejected is no longer "usable" and the book page stops using it.
	e.rd.mu.Lock()
	e.rd.validate = debrid.ErrBadKey
	e.rd.mu.Unlock()
	e.do("POST", "/v1/me/debrid/realdebrid", admin.access, nil).status(t, 200)
	if got := byHash(page(admin))[h40('a')]["usable"]; !equalJSON(got, map[string]any{"debrid": []any{"torbox"}, "p2p": false}) {
		t.Fatalf("usable = %v", got)
	}
	if got := byHash(page(admin))[h40('b')]["cached"]; !equalJSON(got, map[string]any{"torbox": false}) {
		t.Fatalf("cached = %v (an invalid key shows no per-key state)", got)
	}

	e.req("GET", "/v1/books/B000000000", admin.access, nil).errCode(t, 404, "not_found")
	e.req("GET", "/v1/books/short", admin.access, nil).errCode(t, 404, "not_found")
}

func equalJSON(a, b any) bool {
	x, _ := json.Marshal(a)
	y, _ := json.Marshal(b)
	return bytes.Equal(x, y)
}

func count(t *testing.T, e *appEnv, table string) int {
	t.Helper()
	// A tiny read through the public store API is not available for every table, so ask the
	// release/availability ports where they exist.
	switch table {
	case "availability":
		got, err := e.db.GetAvailability(context.Background(), "realdebrid", []string{h40('a'), h40('b')}, time.Time{})
		if err != nil {
			t.Fatal(err)
		}
		return len(got)
	}
	t.Fatalf("unknown table %s", table)
	return 0
}

func TestChapters(t *testing.T) {
	e := newAppEnv(t)
	admin := e.bootstrap()
	r := e.req("GET", "/v1/books/"+duneASIN+"/chapters", admin.access, nil)
	r.status(t, 200)
	m := r.json(t)
	chs := m["chapters"].([]any)
	if m["asin"] != duneASIN || m["runtime_ms"] != float64(75_000_000) || len(chs) != 2 ||
		!equalJSON(chs[0], map[string]any{"title": "Opening Credits", "start_ms": 0, "length_ms": 60970}) || r.cacheControl() != "private, max-age=300" {
		t.Fatalf("%s %v", r.Body.String(), r.Header())
	}
	r = e.req("GET", "/v1/books/"+otherASIN+"/chapters", admin.access, nil) // unknown to Audnexus: empty, not an error
	r.status(t, 200)
	if m := r.json(t); m["chapters"] == nil || len(m["chapters"].([]any)) != 0 {
		t.Fatalf("%s", r.Body.String())
	}
	e.req("GET", "/v1/books/lowercase/chapters", admin.access, nil).errCode(t, 404, "not_found")
}

func (e *appEnv) putEntry(s session, asin string, body map[string]any) resp {
	return e.req("PUT", "/v1/me/library/"+asin, s.access, body)
}

func TestLibraryOverHTTP(t *testing.T) {
	e := newAppEnv(t)
	admin, bob := e.twoUsers()
	now := time.Now().UnixMilli()
	// A known release the entry may point at.
	e.req("GET", "/v1/books/"+duneASIN, admin.access, nil).status(t, 200)

	r := e.putEntry(admin, duneASIN, map[string]any{"status": "listening", "release_hash": h40('a'), "position_ms": 5000, "file_path": "d/01.mp3", "file_position_ms": 2500, "speed": 1.5, "updated_at": now})
	r.status(t, 200)
	m := r.json(t)
	if m["status"] != "listening" || m["release_hash"] != h40('a') || m["position_ms"] != float64(5000) || m["file_path"] != "d/01.mp3" ||
		m["speed"] != 1.5 || m["updated_at"] != float64(now) || m["book"].(map[string]any)["title"] != "Dune" || r.cacheControl() != "no-store" {
		t.Fatalf("%s", r.Body.String())
	}
	// Defaults of a minimal write.
	r = e.putEntry(admin, otherASIN, map[string]any{"status": "saved", "updated_at": now - 10}) // unknown ASIN: fetched into the catalog
	r.status(t, 200)
	if m := r.json(t); m["speed"] != 1.0 || m["position_ms"] != float64(0) || m["release_hash"] != nil || m["file_path"] != nil || m["book"].(map[string]any)["title"] != "Emma" {
		t.Fatalf("%s", r.Body.String())
	}
	e.putEntry(admin, "B000000000", map[string]any{"status": "saved", "updated_at": now}).errCode(t, 404, "not_found")
	e.meta.mu.Lock()
	callsBefore := e.meta.calls
	e.meta.mu.Unlock()
	e.putEntry(admin, otherASIN, map[string]any{"status": "saved", "updated_at": now - 5}).status(t, 200)
	e.meta.mu.Lock()
	if e.meta.calls != callsBefore {
		t.Error("a stored book is fetched again")
	}
	e.meta.mu.Unlock()

	// Last write wins.
	r = e.putEntry(admin, duneASIN, map[string]any{"status": "saved", "position_ms": 1, "updated_at": now - 1000})
	r.status(t, 200)
	if m := r.json(t); m["status"] != "listening" || m["position_ms"] != float64(5000) {
		t.Fatalf("an older write must return the stored entry: %s", r.Body.String())
	}
	r = e.putEntry(admin, duneASIN, map[string]any{"status": "finished", "position_ms": 9000, "updated_at": now + 1000})
	if m := r.json(t); m["status"] != "finished" || m["position_ms"] != float64(9000) {
		t.Fatalf("%s", r.Body.String())
	}

	// Validation.
	long := strings.Repeat("a", 513)
	bad := map[string]map[string]any{
		"no status":         {"updated_at": now},
		"bad status":        {"status": "reading", "updated_at": now},
		"no updated_at":     {"status": "saved"},
		"negative updated":  {"status": "saved", "updated_at": -1},
		"far future":        {"status": "saved", "updated_at": now + int64(48*time.Hour/time.Millisecond)},
		"negative position": {"status": "saved", "updated_at": now, "position_ms": -1},
		"negative file pos": {"status": "saved", "updated_at": now, "file_position_ms": -1},
		"speed too low":     {"status": "saved", "updated_at": now, "speed": 0.4},
		"speed too high":    {"status": "saved", "updated_at": now, "speed": 3.1},
		"long path":         {"status": "saved", "updated_at": now, "file_path": long},
		"control in path":   {"status": "saved", "updated_at": now, "file_path": "a\x00b"},
		"bad hash":          {"status": "saved", "updated_at": now, "release_hash": "XYZ"},
		"unknown release":   {"status": "saved", "updated_at": now, "release_hash": h40('f')},
		"wrong type":        {"status": 1, "updated_at": now},
	}
	for name, body := range bad {
		t.Run(name, func(t *testing.T) { e.putEntry(admin, duneASIN, body).errCode(t, 400, "invalid_request") })
	}
	e.putEntry(admin, "lowercase", map[string]any{"status": "saved", "updated_at": now}).errCode(t, 400, "invalid_request")

	// Reads: scoped to the caller.
	if es := e.req("GET", "/v1/me/library", admin.access, nil).json(t)["entries"].([]any); len(es) != 2 || es[0].(map[string]any)["book"].(map[string]any)["asin"] != duneASIN {
		t.Fatalf("most recently touched first: %v", es)
	}
	if es := e.req("GET", "/v1/me/library?status=saved", admin.access, nil).json(t)["entries"].([]any); len(es) != 1 {
		t.Fatalf("saved = %v", es)
	}
	e.req("GET", "/v1/me/library?status=nope", admin.access, nil).errCode(t, 400, "invalid_request")
	e.req("GET", "/v1/me/library/"+duneASIN, admin.access, nil).status(t, 200)
	e.req("GET", "/v1/me/library/"+duneASIN, bob.access, nil).errCode(t, 404, "not_found")
	if es := e.req("GET", "/v1/me/library", bob.access, nil).json(t)["entries"]; es == nil || len(es.([]any)) != 0 {
		t.Fatalf("Bob's library: %v", es)
	}
	// Bob's own entry is independent of Alice's, and Bob's delete does not reach hers.
	e.putEntry(bob, duneASIN, map[string]any{"status": "saved", "updated_at": now - 5000}).status(t, 200)
	e.req("DELETE", "/v1/me/library/"+duneASIN, bob.access, nil).status(t, 204)
	if m := e.req("GET", "/v1/me/library/"+duneASIN, admin.access, nil).json(t); m["status"] != "finished" {
		t.Fatalf("%v", m)
	}
	e.req("GET", "/v1/me/library/"+duneASIN, bob.access, nil).errCode(t, 404, "not_found")
	e.req("DELETE", "/v1/me/library/"+duneASIN, bob.access, nil).status(t, 204) // idempotent
	e.req("DELETE", "/v1/me/library/lowercase", bob.access, nil).status(t, 204)
}

func TestBookmarksOverHTTP(t *testing.T) {
	e := newAppEnv(t)
	admin, bob := e.twoUsers()
	now := time.Now().UnixMilli()
	path := "/v1/me/library/" + duneASIN + "/bookmarks"

	e.req("POST", path, admin.access, map[string]any{"position_ms": 1}).errCode(t, 404, "not_found") // not in the library yet
	e.putEntry(admin, duneASIN, map[string]any{"status": "listening", "updated_at": now}).status(t, 200)
	e.putEntry(bob, duneASIN, map[string]any{"status": "listening", "updated_at": now}).status(t, 200)

	r := e.req("POST", path, admin.access, map[string]any{"position_ms": 9000, "note": "  late  "})
	r.status(t, 200)
	late := r.json(t)
	if late["id"] == "" || late["position_ms"] != float64(9000) || late["note"] != "late" || late["created_at"] == "" {
		t.Fatalf("%s", r.Body.String())
	}
	e.req("POST", path, admin.access, map[string]any{"position_ms": 100}).status(t, 200)
	for name, body := range map[string]any{
		"missing position": map[string]any{"note": "x"}, "negative": map[string]any{"position_ms": -1},
		"long note": map[string]any{"position_ms": 1, "note": strings.Repeat("é", 281)}, "control": map[string]any{"position_ms": 1, "note": "a\x01"},
	} {
		t.Run(name, func(t *testing.T) { e.req("POST", path, admin.access, body).errCode(t, 400, "invalid_request") })
	}
	e.req("POST", path, admin.access, map[string]any{"position_ms": 1, "note": strings.Repeat("é", 280)}).status(t, 200)

	bm := e.req("GET", path, admin.access, nil).json(t)["bookmarks"].([]any)
	if len(bm) != 3 || bm[0].(map[string]any)["position_ms"] != float64(1) || bm[2].(map[string]any)["position_ms"] != float64(9000) {
		t.Fatalf("ordered by position: %v", bm)
	}
	if bb := e.req("GET", path, bob.access, nil).json(t)["bookmarks"].([]any); len(bb) != 0 {
		t.Fatalf("Bob sees %v", bb)
	}
	// Bob cannot delete Alice's bookmark.
	e.req("DELETE", path+"/"+late["id"].(string), bob.access, nil).status(t, 204)
	if len(e.req("GET", path, admin.access, nil).json(t)["bookmarks"].([]any)) != 3 {
		t.Fatal("Bob deleted Alice's bookmark")
	}
	e.req("DELETE", path+"/"+late["id"].(string), admin.access, nil).status(t, 204)
	e.req("DELETE", path+"/"+late["id"].(string), admin.access, nil).status(t, 204)
	e.req("DELETE", path+"/garbage", admin.access, nil).status(t, 204)
	if len(e.req("GET", path, admin.access, nil).json(t)["bookmarks"].([]any)) != 2 {
		t.Fatal("not deleted")
	}
	e.req("DELETE", "/v1/me/library/"+duneASIN, admin.access, nil).status(t, 204)
	if bm := e.req("GET", path, admin.access, nil).json(t)["bookmarks"].([]any); len(bm) != 0 {
		t.Fatalf("bookmarks outlived the entry: %v", bm)
	}
}

// limitLib answers the limits the real store only reaches after 2000 entries.
type limitLib struct {
	library.Store
	entries, bookmarks bool
}

func (l limitLib) PutEntry(ctx context.Context, u string, w library.Write) (library.Entry, error) {
	if l.entries {
		return library.Entry{}, library.ErrLimitReached
	}
	return l.Store.PutEntry(ctx, u, w)
}
func (l limitLib) CreateBookmark(ctx context.Context, u, a string, p int64, n string) (library.Bookmark, error) {
	if l.bookmarks {
		return library.Bookmark{}, library.ErrLimitReached
	}
	return l.Store.CreateBookmark(ctx, u, a, p, n)
}

func TestLimitReachedIs409(t *testing.T) {
	e := newAppEnv(t)
	admin := e.bootstrap()
	// The real handlers over a library store that is at its limits.
	wrapped := &app{API: e.api, d: AppDeps{Library: limitLib{Store: e.db, entries: true, bookmarks: true}, Catalog: nopCatalog{}, Store: e.db}, log: e.api.log, now: time.Now}
	call := func(h http.HandlerFunc, method, body string, path map[string]string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, "/x", strings.NewReader(body))
		for k, v := range path {
			r.SetPathValue(k, v)
		}
		r = r.WithContext(accounts.WithPrincipal(r.Context(), accounts.Principal{UserID: admin.userID, Role: accounts.RoleAdmin}))
		w := httptest.NewRecorder()
		h(w, r)
		return w
	}
	w := call(wrapped.putLibrary, "PUT", fmt.Sprintf(`{"status":"saved","updated_at":%d}`, time.Now().UnixMilli()), map[string]string{"asin": duneASIN})
	if w.Code != 409 || !strings.Contains(w.Body.String(), `"limit_reached"`) {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
	w = call(wrapped.createBookmark, "POST", `{"position_ms":1}`, map[string]string{"asin": duneASIN})
	if w.Code != 409 || !strings.Contains(w.Body.String(), `"limit_reached"`) {
		t.Fatalf("%d %s", w.Code, w.Body.String())
	}
}

type nopCatalog struct{}

func (nopCatalog) SearchFor(context.Context, service.Prefs, string) ([]model.Book, error) {
	return nil, nil
}
func (nopCatalog) DiscoverFor(context.Context, service.Prefs, int) ([]model.Book, error) {
	return nil, nil
}
func (nopCatalog) BookFor(context.Context, service.BookRequest) (*service.BookPage, error) {
	return nil, errors.New("unused")
}
func (nopCatalog) EnsureBook(_ context.Context, _, asin string) (model.Book, error) {
	return model.Book{ASIN: asin}, nil
}
func (nopCatalog) Chapters(context.Context, string, string) (int64, []metadata.Chapter, error) {
	return 0, nil, nil
}

func TestReleaseRoutesOverHTTP(t *testing.T) {
	e := newAppEnv(t)
	admin, bob := e.twoUsers()
	e.setKey(admin, "realdebrid", "rd-key-of-alice-0001")
	e.req("GET", "/v1/books/"+duneASIN, admin.access, nil).status(t, 200) // finds the two releases
	a := h40('a')

	// torrent-files: stored once, never overwritten, per hash for everybody.
	url := "/v1/releases/" + a + "/torrent-files"
	files := map[string]any{"files": []any{map[string]any{"index": 0, "path": `Disc 1\01.mp3`, "size": 10}, map[string]any{"index": 1, "path": "Disc 1/02.mp3", "size": 20}}}
	r := e.req("POST", url, bob.access, files)
	r.status(t, 200)
	if got := r.json(t)["torrent_files"].([]any); len(got) != 2 || got[0].(map[string]any)["path"] != "Disc 1/01.mp3" || r.cacheControl() != "no-store" {
		t.Fatalf("%s", r.Body.String())
	}
	r = e.req("POST", url, admin.access, map[string]any{"files": []any{map[string]any{"index": 0, "path": "evil.mp3", "size": 1}}})
	if got := r.json(t)["torrent_files"].([]any); len(got) != 2 || got[0].(map[string]any)["path"] != "Disc 1/01.mp3" {
		t.Fatalf("overwritten: %s", r.Body.String())
	}
	e.req("POST", "/v1/releases/"+h40('f')+"/torrent-files", admin.access, files).errCode(t, 404, "not_found")
	// ...and the book page shows them as torrent_files, apart from files.
	rel := e.req("GET", "/v1/books/"+duneASIN, admin.access, nil).json(t)["releases"].([]any)
	for _, x := range rel {
		x := x.(map[string]any)
		if x["info_hash"] == a && (len(x["torrent_files"].([]any)) != 2 || len(x["files"].([]any)) != 0) {
			t.Fatalf("%v", x)
		}
	}

	// prepare with a magnet creates an unknown release; the download route then finds it.
	h := h40('c')
	e.req("POST", "/v1/releases/"+h+"/prepare", admin.access, map[string]any{"provider": "realdebrid"}).errCode(t, 404, "not_found")
	e.req("POST", "/v1/releases/"+h+"/prepare", admin.access, map[string]any{"provider": "realdebrid", "magnet": "magnet:?xt=urn:btih:" + h}).status(t, 200)
	if r := e.req("GET", "/v1/releases/"+h+"/files/1/download?provider=realdebrid", admin.access, nil); r.Code != 302 || r.cacheControl() != "no-store" ||
		!strings.HasPrefix(r.Header().Get("Location"), "https://22-4.download.real-debrid.com/") {
		t.Fatalf("%d %v", r.Code, r.Header())
	}
	if r := e.req("HEAD", "/v1/releases/"+h+"/files/1/download?provider=realdebrid", admin.access, nil); r.Code != 302 {
		t.Fatalf("HEAD: %d", r.Code)
	}
	// Bob has no key: 403 on every debrid action, and nothing was sent to a provider on his behalf.
	calls := e.rd.calls()
	e.req("POST", "/v1/releases/"+h+"/prepare", bob.access, map[string]any{"provider": "realdebrid"}).errCode(t, 403, "no_debrid_key")
	e.req("GET", "/v1/releases/"+h+"/files/1/download?provider=realdebrid", bob.access, nil).errCode(t, 403, "no_debrid_key")
	e.req("GET", "/v1/releases/"+h+"/files/1/download?provider=torbox", admin.access, nil).errCode(t, 403, "no_debrid_key")
	if e.rd.calls() != calls {
		t.Fatal("a keyless request reached the provider")
	}
	// No key or link ever shows up in the server log.
	for _, s := range []string{"rd-key-of-alice-0001", "LINKTOKEN"} {
		if strings.Contains(e.logs.String(), s) {
			t.Fatalf("%q leaked into the log:\n%s", s, e.logs.String())
		}
	}
}

func TestAppUpdate(t *testing.T) {
	e := newAppEnv(t)
	var info struct {
		Available bool   `json:"available"`
		Version   string `json:"version"`
		Code      int    `json:"version_code"`
		Size      int64  `json:"size"`
		SHA256    string `json:"sha256"`
		URL       string `json:"url"`
	}
	r := e.req("GET", "/v1/app/update", "", nil)
	r.status(t, 200)
	if r.cacheControl() != "no-store" || strings.TrimSpace(r.Body.String()) != `{"available":false}` {
		t.Fatalf("nothing bundled: %s %v", r.Body.String(), r.Header())
	}

	// Bundle an APK the way the release pipeline will: update.json + audiobooks.apk.
	apk := []byte("PK\x03\x04 pretend apk bytes")
	sum := sha256Hex(apk)
	meta := fmt.Sprintf(`{"version_name":"1.2.3","version_code":10203,"sha256":%q,"size":%d,"notes":"n","released_at":"2026-11-25T12:00:00Z"}`, sum, len(apk))
	if err := os.WriteFile(filepath.Join(e.dir, "update.json"), []byte(meta), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(e.dir, "audiobooks.apk"), apk, 0o644); err != nil {
		t.Fatal(err)
	}
	// The service loads the bundle at start-up: rebuild the API like main does.
	e2 := newAppEnvDir(t, e.dir)
	r = e2.req("GET", "/v1/app/update", "", nil)
	r.status(t, 200)
	if err := json.Unmarshal(r.Body.Bytes(), &info); err != nil {
		t.Fatal(err)
	}
	if !info.Available || info.Version != "1.2.3" || info.Code != 10203 || info.Size != int64(len(apk)) || info.SHA256 != sum || info.URL != "https://audiobooks.example/v1/app/update/apk" {
		t.Fatalf("%+v", info)
	}
	r = e2.req("GET", "/v1/app/update/apk", "", nil)
	r.status(t, 200)
	if !bytes.Equal(r.Body.Bytes(), apk) || r.Header().Get("Content-Type") != "application/vnd.android.package-archive" || r.Header().Get("ETag") != `"`+sum+`"` {
		t.Fatalf("%v", r.Header())
	}
	if cc := r.Header().Get("Cache-Control"); cc != "no-store" {
		t.Fatalf("APK Cache-Control = %q, the contract says no-store", cc)
	}
	// Range requests work (resumable downloads).
	rq := httptest.NewRequest("GET", "/v1/app/update/apk", nil)
	rq.Header.Set("Range", "bytes=2-5")
	rec := httptest.NewRecorder()
	e2.api.ServeHTTP(rec, rq)
	if rec.Code != 206 || !bytes.Equal(rec.Body.Bytes(), apk[2:6]) {
		t.Fatalf("range: %d %q", rec.Code, rec.Body.String())
	}
	if cc := rec.Header().Get("Cache-Control"); cc != "no-store" {
		t.Fatalf("APK 206 Cache-Control = %q, the contract says no-store", cc)
	}
}

func sha256Hex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}
