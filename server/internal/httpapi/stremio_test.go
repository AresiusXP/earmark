package httpapi

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/AresiusXP/earmark/server/internal/debrid"
	"github.com/AresiusXP/earmark/server/internal/model"
	"github.com/AresiusXP/earmark/server/internal/service"
	"github.com/AresiusXP/earmark/server/internal/userconfig"
)

func TestParseVideoID(t *testing.T) {
	h := testHash
	tests := []struct {
		in   string
		want service.VideoRef
		ok   bool
	}{
		{"abk:B002V0QK4C:" + h + ":0", service.VideoRef{ASIN: "B002V0QK4C", InfoHash: h, FileIndex: 0}, true},
		{"abk:B002V0QK4C:" + h + ":12", service.VideoRef{ASIN: "B002V0QK4C", InfoHash: h, FileIndex: 12}, true},
		{"abk:B002V0QK4C:" + h + ":-1", service.VideoRef{ASIN: "B002V0QK4C", InfoHash: h, FileIndex: -1}, true},
		{"abk:B002V0QK4C:" + h + ":-2", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + h + ":+1", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + h + ":01", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + h + ":1.5", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + h + ":", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + h + ":9999999", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + h + ":1:2", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + h + ":1.json", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + strings.ToUpper(h) + ":1", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + h[:39] + ":1", service.VideoRef{}, false},
		{"abk:B002V0QK4C:" + h[:39] + "g:1", service.VideoRef{}, false},
		{"abk:B002V0QK4C:1:1", service.VideoRef{}, false},
		{"abk:B002V0QK4C", service.VideoRef{}, false},
		{"abk::" + h + ":1", service.VideoRef{}, false},
		{"abk:B00 2:" + h + ":1", service.VideoRef{}, false},
		{"abk:../x:" + h + ":1", service.VideoRef{}, false},
		{"tt0111161:1:1", service.VideoRef{}, false},
		{"abc:B002V0QK4C:" + h + ":1", service.VideoRef{}, false},
		{"", service.VideoRef{}, false},
		{":::", service.VideoRef{}, false},
		{"abk:" + strings.Repeat("A", 41) + ":" + h + ":1", service.VideoRef{}, false},
	}
	for _, tc := range tests {
		got, ok := parseVideoID(tc.in)
		if ok != tc.ok || got != tc.want {
			t.Errorf("parseVideoID(%q) = %+v,%v want %+v,%v", tc.in, got, ok, tc.want, tc.ok)
		}
	}
}

func TestParseBookID(t *testing.T) {
	for in, want := range map[string]bool{
		"abk:B002V0QK4C": true, "abk:1234567890": true, "abk:": false, "abk": false, "abk:B0:1": false,
		"abk:B002V0QK4C.json": false, "abk:é": false, "x:B002V0QK4C": false,
	} {
		if _, ok := parseBookID(in); ok != want {
			t.Errorf("parseBookID(%q) ok=%v want %v", in, ok, want)
		}
	}
}

func TestCORSOnEveryResponse(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	tests := []struct {
		name, method, path string
		status             int
	}{
		{"options manifest", http.MethodOptions, "/manifest.json", 204},
		{"options catalog", http.MethodOptions, "/" + tok + "/catalog/audiobook/abk-top.json", 204},
		{"options api", http.MethodOptions, "/api/config", 204},
		{"options unknown", http.MethodOptions, "/nope", 204},
		{"not found", http.MethodGet, "/nope/nope/nope/nope/nope/nope", 404},
		{"tampered cfg", http.MethodGet, "/AAAA/manifest.json", 400},
		{"tampered catalog", http.MethodGet, "/AAAA/catalog/audiobook/abk-top.json", 400},
		{"tampered meta", http.MethodGet, "/AAAA/meta/audiobook/abk:B002V0QK4C.json", 400},
		{"tampered stream", http.MethodGet, "/AAAA/stream/audiobook/abk:B002V0QK4C:" + testHash + ":1.json", 400},
		{"tampered resolve", http.MethodGet, "/resolve/AAAA/torbox/" + testHash + "/1", 400},
		{"bad resolve hash", http.MethodGet, "/resolve/" + tok + "/torbox/zz/1", 404},
		{"unknown meta", http.MethodGet, "/" + tok + "/meta/audiobook/abk:B002V0QK4C.json", 404},
		{"method not allowed", http.MethodPost, "/manifest.json", 405},
		{"healthz", http.MethodGet, "/healthz", 200},
		{"static logo", http.MethodGet, "/static/logo.png", 200},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			rec := e.do(tc.method, tc.path, nil)
			mustStatus(t, rec, tc.status)
			if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "*" {
				t.Errorf("Allow-Origin = %q", got)
			}
			if rec.Header().Get("Access-Control-Allow-Methods") == "" || rec.Header().Get("Access-Control-Allow-Headers") == "" {
				t.Error("missing Allow-Methods/Headers")
			}
			if rec.Header().Get("Referrer-Policy") != "no-referrer" {
				t.Error("missing Referrer-Policy")
			}
		})
	}
}

func TestTamperedConfigIsGeneric(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	flipped := []byte(tok)
	flipped[len(flipped)/2] ^= 1
	for _, c := range []string{string(flipped), tok[:len(tok)-2], "x", strings.Repeat("A", 20000)} {
		rec := e.get("/" + c + "/manifest.json")
		mustStatus(t, rec, 400)
		if body := strings.TrimSpace(rec.Body.String()); body != `{"error":"invalid config"}` {
			t.Errorf("body = %s", body)
		}
	}
}

func TestManifest(t *testing.T) {
	e := newEnv(t, "")

	m := decode(t, e.get("/manifest.json"))
	hints := m["behaviorHints"].(map[string]any)
	if hints["configurable"] != true || hints["configurationRequired"] != true {
		t.Errorf("unconfigured hints = %v", hints)
	}
	if cats, ok := m["catalogs"].([]any); !ok || len(cats) != 0 {
		t.Errorf("catalogs must be [] when unconfigured: %v", m["catalogs"])
	}
	if m["id"] != "xyz.aresius.audiobooks" || m["version"] != "1.2.3" {
		t.Errorf("id/version: %v %v", m["id"], m["version"])
	}
	if m["logo"] != testPublic+"/static/logo.png" {
		t.Errorf("logo = %v", m["logo"])
	}

	tok := e.token(t, userconfig.Config{Debrid: map[string]string{"torbox": "k1", "realdebrid": "k2"}})
	rec := e.get("/" + tok + "/manifest.json")
	mustStatus(t, rec, 200)
	if cc := rec.Header().Get("Cache-Control"); !strings.HasPrefix(cc, "public, max-age=") {
		t.Errorf("Cache-Control = %q", cc)
	}
	m = decode(t, rec)
	if m["name"] != "Audiobooks (RD, TB)" {
		t.Errorf("name = %v", m["name"])
	}
	hints = m["behaviorHints"].(map[string]any)
	if hints["configurable"] != true || hints["configurationRequired"] != nil {
		t.Errorf("configured hints = %v", hints)
	}
	cats := m["catalogs"].([]any)
	if len(cats) != 2 {
		t.Fatalf("catalogs = %v", cats)
	}
	search := cats[0].(map[string]any)
	if search["id"] != "abk-search" || search["type"] != "audiobook" {
		t.Errorf("search catalog = %v", search)
	}
	if ex := search["extra"].([]any)[0].(map[string]any); ex["name"] != "search" || ex["isRequired"] != true {
		t.Errorf("search extra = %v", ex)
	}
	top := cats[1].(map[string]any)
	if top["id"] != "abk-top" || top["name"] != "Best sellers" || top["extra"].([]any)[0].(map[string]any)["name"] != "skip" {
		t.Errorf("top catalog = %v", top)
	}
	res := m["resources"].([]any)
	if len(res) != 3 || res[0] != "catalog" {
		t.Fatalf("resources = %v", res)
	}
	for _, r := range res[1:] {
		o := r.(map[string]any)
		if o["idPrefixes"].([]any)[0] != "abk:" || o["types"].([]any)[0] != "audiobook" {
			t.Errorf("resource = %v", o)
		}
	}
}

func TestManifestVersionFallback(t *testing.T) {
	e := newEnv(t, "")
	h := New(Deps{API: e.api, Sealer: e.sealer, PublicURL: testPublic, Version: "dev"})
	m := decode(t, doOn(h, http.MethodGet, "/manifest.json"))
	if m["version"] != "0.0.0" {
		t.Errorf("version = %v", m["version"])
	}
}

func TestCatalog(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	e.api.search = func(_ userconfig.Config, q string) ([]model.Book, error) {
		return []model.Book{sampleBook(), {ASIN: "", Title: "no asin"}, {ASIN: "B0:bad", Title: "bad"}}, nil
	}
	e.api.discover = func(_ userconfig.Config, page int) ([]model.Book, error) { return nil, nil }

	t.Run("search", func(t *testing.T) {
		rec := e.get("/" + tok + "/catalog/audiobook/abk-search/search=dune.json")
		mustStatus(t, rec, 200)
		m := decode(t, rec)
		metas := m["metas"].([]any)
		if len(metas) != 1 {
			t.Fatalf("metas = %v", metas)
		}
		p := metas[0].(map[string]any)
		if p["id"] != "abk:B002V0QK4C" || p["type"] != "audiobook" || p["posterShape"] != "square" || p["name"] != "Dune" {
			t.Errorf("preview = %v", p)
		}
		if p["description"] != "Frank Herbert · read by Scott Brick, Orlagh Cassidy" {
			t.Errorf("description = %v", p["description"])
		}
		if e.api.gotQuery != "dune" || e.api.gotCfg.Region != "uk" {
			t.Errorf("query=%q region=%q", e.api.gotQuery, e.api.gotCfg.Region)
		}
		if cc := rec.Header().Get("Cache-Control"); cc != "public, max-age=120" {
			t.Errorf("Cache-Control = %q", cc)
		}
	})
	t.Run("search encoded", func(t *testing.T) {
		e.get("/" + tok + "/catalog/audiobook/abk-search/search=c%2B%2B%20%26%20go.json")
		if e.api.gotQuery != "c++ & go" {
			t.Errorf("query = %q", e.api.gotQuery)
		}
	})
	t.Run("search with skip is exhausted", func(t *testing.T) {
		m := decode(t, e.get("/"+tok+"/catalog/audiobook/abk-search/search=dune&skip=50.json"))
		if len(m["metas"].([]any)) != 0 {
			t.Errorf("metas = %v", m["metas"])
		}
	})
	t.Run("search without query", func(t *testing.T) {
		m := decode(t, e.get("/"+tok+"/catalog/audiobook/abk-search.json"))
		if got, ok := m["metas"].([]any); !ok || len(got) != 0 {
			t.Errorf("metas = %v", m["metas"])
		}
	})
	t.Run("top pages", func(t *testing.T) {
		for path, page := range map[string]int{
			"abk-top.json": 0, "abk-top/skip=0.json": 0, "abk-top/skip=49.json": 0, "abk-top/skip=50.json": 1,
			"abk-top/skip=125.json": 2, "abk-top/skip=-5.json": 0, "abk-top/skip=abc.json": 0, "abk-top/skip=999999.json": 20,
		} {
			rec := e.get("/" + tok + "/catalog/audiobook/" + path)
			mustStatus(t, rec, 200)
			if e.api.gotPage != page {
				t.Errorf("%s: page = %d, want %d", path, e.api.gotPage, page)
			}
			if got, ok := decode(t, rec)["metas"].([]any); !ok || got == nil {
				t.Errorf("%s: metas must be [] not null", path)
			}
		}
	})
	t.Run("unknown catalog", func(t *testing.T) {
		for _, p := range []string{"abk-nope.json", "abk-top", "x"} {
			rec := e.get("/" + tok + "/catalog/audiobook/" + p)
			mustStatus(t, rec, 404)
			if _, ok := decode(t, rec)["metas"].([]any); !ok {
				t.Errorf("%s: want metas array", p)
			}
		}
	})
	t.Run("wrong type", func(t *testing.T) {
		mustStatus(t, e.get("/"+tok+"/catalog/movie/abk-top.json"), 404)
	})
	t.Run("service error gives empty", func(t *testing.T) {
		e.api.discover = func(userconfig.Config, int) ([]model.Book, error) { return nil, errors.New("boom") }
		rec := e.get("/" + tok + "/catalog/audiobook/abk-top.json")
		mustStatus(t, rec, 200)
		if got, ok := decode(t, rec)["metas"].([]any); !ok || len(got) != 0 {
			t.Errorf("metas = %v", decode(t, rec)["metas"])
		}
		if rec.Header().Get("Cache-Control") != "no-store" {
			t.Error("errors must not be cached")
		}
		if !strings.Contains(e.logs.String(), "catalog failed") {
			t.Error("error not logged")
		}
	})
	t.Run("outage gives 502", func(t *testing.T) {
		e.api.discover = func(userconfig.Config, int) ([]model.Book, error) { return nil, &net.DNSError{Err: "no such host"} }
		mustStatus(t, e.get("/"+tok+"/catalog/audiobook/abk-top.json"), 502)
		e.api.discover = func(userconfig.Config, int) ([]model.Book, error) {
			return nil, fmt.Errorf("x: %w", context.DeadlineExceeded)
		}
		mustStatus(t, e.get("/"+tok+"/catalog/audiobook/abk-top.json"), 502)
	})
}

func TestMeta(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	h1, h2, h3 := strings.Repeat("a", 40), strings.Repeat("b", 40), strings.Repeat("c", 40)
	e.api.book = func(_ userconfig.Config, asin string) (*service.BookPage, error) {
		if asin != "B002V0QK4C" {
			return nil, service.ErrNotFound
		}
		return &service.BookPage{Book: sampleBook(), Releases: []service.ReleaseView{
			{Season: 1, Release: model.Release{InfoHash: h1, Title: "Dune [MP3 64k]", Size: 1 << 30, Seeders: 12, Parsed: model.ParsedTitle{Format: "mp3", BitrateKbps: 64}},
				Cached: map[string]bool{"torbox": true},
				Files:  []model.File{{Index: 3, Path: "Dune/01_Prologue.mp3"}, {Index: 4, Path: "Dune/02 Chapter_One.mp3"}, {Index: 7, Path: ""}}},
			{Season: 2, Release: model.Release{InfoHash: h2, Title: "Dune M4B", Parsed: model.ParsedTitle{Format: "m4b"}},
				Files: []model.File{{Index: 0, Path: "Dune.m4b"}}},
			{Season: 3, Release: model.Release{InfoHash: h3, Title: "Dune unknown files"}},
		}}, nil
	}
	rec := e.get("/" + tok + "/meta/audiobook/abk%3AB002V0QK4C.json")
	mustStatus(t, rec, 200)
	if cc := rec.Header().Get("Cache-Control"); cc != "private, max-age=300" { // the body carries a link with the sealed config
		t.Errorf("Cache-Control = %q", cc)
	}
	m := decode(t, rec)["meta"].(map[string]any)
	if m["id"] != "abk:B002V0QK4C" || m["type"] != "audiobook" || m["name"] != "Dune" || m["posterShape"] != "square" ||
		m["poster"] != "https://img.example/dune.jpg" || m["background"] != "https://img.example/dune.jpg" ||
		m["releaseInfo"] != "2007" || m["runtime"] != "21h 15m" || m["description"] != "Desert planet." {
		t.Errorf("meta = %v", m)
	}
	links := m["links"].([]any)
	if len(links) != 5 {
		t.Fatalf("links = %v", links)
	}
	want := []struct{ name, cat, url string }{
		{"Frank Herbert", "author", "stremio:///search?search=Frank%20Herbert"},
		{"Scott Brick", "narrator", "stremio:///search?search=Scott%20Brick"},
		{"Orlagh Cassidy", "narrator", "stremio:///search?search=Orlagh%20Cassidy"},
		{"Dune Chronicles", "series", "stremio:///search?search=Dune%20Chronicles"},
		{"Listen in a podcast app", "Podcast", testPublic + "/" + tok + "/listen/B002V0QK4C"},
	}
	for i, w := range want {
		l := links[i].(map[string]any)
		if l["name"] != w.name || l["category"] != w.cat || l["url"] != w.url {
			t.Errorf("link %d = %v", i, l)
		}
	}

	videos := m["videos"].([]any)
	type vwant struct {
		id, title      string
		season, episod int
	}
	wants := []vwant{
		{"abk:B002V0QK4C:" + h1 + ":3", "01 Prologue", 1, 1},
		{"abk:B002V0QK4C:" + h1 + ":4", "02 Chapter One", 1, 2},
		{"abk:B002V0QK4C:" + h1 + ":7", "Chapter 3", 1, 3},
		{"abk:B002V0QK4C:" + h2 + ":0", "Full book (M4B)", 2, 1},
		{"abk:B002V0QK4C:" + h3 + ":-1", "Play full release", 3, 1},
	}
	if len(videos) != len(wants) {
		t.Fatalf("got %d videos: %v", len(videos), videos)
	}
	prev := ""
	for i, w := range wants {
		v := videos[i].(map[string]any)
		if v["id"] != w.id || v["title"] != w.title || int(v["season"].(float64)) != w.season || int(v["episode"].(float64)) != w.episod {
			t.Errorf("video %d = %v, want %+v", i, v, w)
		}
		if v["thumbnail"] != "https://img.example/dune.jpg" {
			t.Errorf("video %d thumbnail = %v", i, v["thumbnail"])
		}
		rel := v["released"].(string)
		if _, err := time.Parse("2006-01-02T15:04:05.000Z", rel); err != nil {
			t.Errorf("video %d released %q: %v", i, rel, err)
		}
		if rel <= prev {
			t.Errorf("released not strictly increasing: %q after %q", rel, prev)
		}
		prev = rel
	}
	if v := videos[0].(map[string]any); v["released"] != "2001-02-05T00:00:00.000Z" {
		// 2000-01-01 + 1*400 + 1 days
		t.Errorf("released = %v", v["released"])
	}
	if ov := videos[0].(map[string]any)["overview"].(string); !strings.HasPrefix(ov, "S1 · MP3 64k · 1.0 GB · 12 seeders · ⚡ TB\n") {
		t.Errorf("overview = %q", ov)
	}
}

func TestMetaEdgeCases(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	t.Run("not found", func(t *testing.T) {
		rec := e.get("/" + tok + "/meta/audiobook/abk:B002V0QK4C.json")
		mustStatus(t, rec, 404)
		if m := decode(t, rec); m["meta"] != nil {
			t.Errorf("body = %v", m)
		}
		if _, ok := decode(t, rec)["meta"]; !ok {
			t.Error("meta key must be present")
		}
	})
	t.Run("garbage ids", func(t *testing.T) {
		for _, id := range []string{"abk:B002V0QK4C", "abk:.json", "B002V0QK4C.json", "abk:B0:1.json", "tt1.json", "abk:a%2Fb.json"} {
			mustStatus(t, e.get("/"+tok+"/meta/audiobook/"+id), 404)
		}
	})
	t.Run("minimal book has non-null arrays", func(t *testing.T) {
		e.api.book = func(userconfig.Config, string) (*service.BookPage, error) {
			return &service.BookPage{Book: model.Book{ASIN: "B000000001", Title: "T"}}, nil
		}
		m := decode(t, e.get("/"+tok+"/meta/audiobook/abk:B000000001.json"))["meta"].(map[string]any)
		for _, k := range []string{"genres", "links", "videos"} {
			if a, ok := m[k].([]any); !ok || len(a) != 0 {
				t.Errorf("%s = %v, want []", k, m[k])
			}
		}
	})
	t.Run("outage", func(t *testing.T) {
		e.api.book = func(userconfig.Config, string) (*service.BookPage, error) { return nil, &net.DNSError{} }
		mustStatus(t, e.get("/"+tok+"/meta/audiobook/abk:B000000001.json"), 502)
	})
	t.Run("other error", func(t *testing.T) {
		e.api.book = func(userconfig.Config, string) (*service.BookPage, error) { return nil, errors.New("db") }
		rec := e.get("/" + tok + "/meta/audiobook/abk:B000000001.json")
		mustStatus(t, rec, 200)
		if rec.Header().Get("Cache-Control") != "no-store" {
			t.Error("must not cache failures")
		}
	})
}

func TestStream(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	rel := model.Release{InfoHash: testHash, Title: "Dune MP3", Size: 5 << 20, Seeders: 7, Parsed: model.ParsedTitle{Format: "mp3", BitrateKbps: 64}}
	e.api.streams = func(_ userconfig.Config, ref service.VideoRef) ([]service.Stream, error) {
		return []service.Stream{
			{Provider: "torbox", Cached: true, Release: rel, File: &model.File{Index: 3, Path: "Dune/01_Prologue.mp3", Size: 1234}, FileIndex: 3},
			{Provider: "realdebrid", Release: rel, FileIndex: -1},
			{Provider: "evil/../x", Release: rel, FileIndex: 1},
		}, nil
	}
	rec := e.get("/" + tok + "/stream/audiobook/abk%3AB002V0QK4C%3A" + testHash + "%3A3.json")
	mustStatus(t, rec, 200)
	if rec.Header().Get("Cache-Control") != "no-store" {
		t.Errorf("Cache-Control = %q", rec.Header().Get("Cache-Control"))
	}
	if e.api.gotRef != (service.VideoRef{ASIN: "B002V0QK4C", InfoHash: testHash, FileIndex: 3}) {
		t.Errorf("ref = %+v", e.api.gotRef)
	}
	streams := decode(t, rec)["streams"].([]any)
	if len(streams) != 3 { // two playable streams + the podcast option
		t.Fatalf("streams = %v", streams)
	}
	ext := streams[2].(map[string]any)
	if want := testPublic + "/" + tok + "/listen/B002V0QK4C"; ext["externalUrl"] != want || ext["url"] != nil || ext["behaviorHints"] != nil {
		t.Errorf("podcast stream = %v, want an externalUrl-only stream %s", ext, want)
	}
	if !strings.Contains(ext["title"].(string), "podcast") {
		t.Errorf("podcast stream title = %v", ext["title"])
	}
	s0 := streams[0].(map[string]any)
	if s0["name"] != "ABK ⚡ TB" {
		t.Errorf("name = %v", s0["name"])
	}
	if want := "MP3 64k · 1.2 KB · 7 seeders\n01_Prologue.mp3"; s0["title"] != want {
		t.Errorf("title = %q, want %q", s0["title"], want)
	}
	if want := testPublic + "/resolve/" + tok + "/torbox/" + testHash + "/3"; s0["url"] != want {
		t.Errorf("url = %v", s0["url"])
	}
	bh := s0["behaviorHints"].(map[string]any)
	if bh["bingeGroup"] != "abk-"+testHash || bh["filename"] != "01_Prologue.mp3" || bh["videoSize"] != float64(1234) || bh["notWebReady"] != nil {
		t.Errorf("hints = %v", bh)
	}
	s1 := streams[1].(map[string]any)
	if s1["name"] != "ABK RD" || !strings.HasSuffix(s1["url"].(string), "/realdebrid/"+testHash+"/-1") {
		t.Errorf("s1 = %v", s1)
	}
	if !strings.Contains(s1["title"].(string), "not cached") || s1["behaviorHints"].(map[string]any)["filename"] != "Dune MP3" {
		t.Errorf("s1 title/hints = %v", s1)
	}
	if strings.Contains(rec.Body.String(), testKey) {
		t.Error("stream response leaks the key")
	}
}

func TestStreamEdgeCases(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	for _, id := range []string{
		"abk:B002V0QK4C:" + testHash + ":1", // no .json
		"abk:B002V0QK4C:" + testHash + ":x.json",
		"abk:B002V0QK4C.json",
		"garbage.json",
		".json",
		"abk:B002V0QK4C:" + testHash + ":1:1.json",
	} {
		rec := e.get("/" + tok + "/stream/audiobook/" + id)
		mustStatus(t, rec, 200)
		if s, ok := decode(t, rec)["streams"].([]any); !ok || len(s) != 0 {
			t.Errorf("%s: streams = %v", id, decode(t, rec)["streams"])
		}
	}
	if (e.api.gotRef != service.VideoRef{}) {
		t.Errorf("service must not be called for malformed ids: %+v", e.api.gotRef)
	}
	good := "/" + tok + "/stream/audiobook/abk:B002V0QK4C:" + testHash + ":1.json"
	e.api.streams = func(userconfig.Config, service.VideoRef) ([]service.Stream, error) { return nil, service.ErrNoProvider }
	if s := decode(t, e.get(good))["streams"].([]any); len(s) != 0 {
		t.Errorf("streams = %v", s)
	}
	e.api.streams = func(userconfig.Config, service.VideoRef) ([]service.Stream, error) { return nil, nil }
	if s, ok := decode(t, e.get(good))["streams"].([]any); !ok || len(s) != 0 {
		t.Errorf("streams = %v", s)
	}
	e.api.streams = func(userconfig.Config, service.VideoRef) ([]service.Stream, error) { return nil, errors.New("db") }
	mustStatus(t, e.get(good), 200)
	e.api.streams = func(userconfig.Config, service.VideoRef) ([]service.Stream, error) { return nil, &net.DNSError{} }
	mustStatus(t, e.get(good), 502)
}

func TestPanicIsRecovered(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	e.api.book = func(userconfig.Config, string) (*service.BookPage, error) { panic("kaboom") }
	rec := e.get("/" + tok + "/meta/audiobook/abk:B000000001.json")
	mustStatus(t, rec, 500)
	if rec.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Error("CORS missing on 500")
	}
	if !strings.Contains(e.logs.String(), "panic in handler") || strings.Contains(e.logs.String(), tok) {
		t.Errorf("logs: %s", e.logs.String())
	}
}

func TestHealthzAndMetrics(t *testing.T) {
	e := newEnv(t, "")
	rec := e.get("/healthz")
	mustStatus(t, rec, 200)
	if rec.Body.String() != "ok" {
		t.Errorf("body = %q", rec.Body.String())
	}
	e.get("/manifest.json")
	rec = e.get("/metrics")
	mustStatus(t, rec, 200)
	body := rec.Body.String()
	for _, want := range []string{
		`abk_http_requests_total{route="GET /manifest.json",status="200"} 1`,
		`abk_http_request_duration_seconds_bucket{route="GET /healthz"`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("metrics missing %q", want)
		}
	}
}

func TestMetricsWithOwnRegistry(t *testing.T) {
	e := newEnv(t, "")
	h := New(Deps{API: e.api, Sealer: e.sealer, PublicURL: testPublic})
	rec := doOn(h, "GET", "/metrics")
	mustStatus(t, rec, 200)
	if !strings.Contains(rec.Body.String(), "go_goroutines") {
		t.Error("default registry should expose Go metrics")
	}
	// same registry twice must not panic
	New(Deps{API: e.api, Sealer: e.sealer, PublicURL: testPublic, Registry: e.reg})
}

func TestLogsNeverContainSecrets(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	e.api.resolve = func(userconfig.Config, string, string, int) (debrid.ResolveResult, error) {
		return debrid.ResolveResult{URL: "https://nexus-1.weur.tb-cdn.st/secret-download-link"}, nil
	}
	e.get("/resolve/" + tok + "/torbox/" + testHash + "/1")
	e.get("/" + tok + "/manifest.json")
	e.get("/" + tok + "/stream/audiobook/abk:B002V0QK4C:" + testHash + ":1.json")
	logs := e.logs.String()
	for _, secret := range []string{tok, testKey, "secret-download-link", testHash} {
		if strings.Contains(logs, secret) {
			t.Errorf("logs contain %q:\n%s", secret, logs)
		}
	}
	if !strings.Contains(logs, "route=") {
		t.Errorf("expected route pattern in logs:\n%s", logs)
	}
}

func TestStaticAssets(t *testing.T) {
	e := newEnv(t, "")
	for p, tc := range map[string]struct{ ctype, magic string }{
		"/static/logo.png":       {"image/png", "\x89PNG"},
		"/static/icon-192.png":   {"image/png", "\x89PNG"},
		"/static/favicon.png":    {"image/png", "\x89PNG"},
		"/favicon.ico":           {"image/png", "\x89PNG"},
		"/static/background.jpg": {"image/jpeg", "\xff\xd8\xff"},
	} {
		rec := e.get(p)
		mustStatus(t, rec, 200)
		if rec.Header().Get("Content-Type") != tc.ctype || !strings.HasPrefix(rec.Body.String(), tc.magic) {
			t.Errorf("%s: wrong content type or magic bytes", p)
		}
	}
}

func TestSafeErr(t *testing.T) {
	err := fmt.Errorf("wrap: %w", &urlErr)
	if got := safeErr(err); strings.Contains(got, "apikey") || !strings.Contains(got, "connection refused") {
		t.Errorf("safeErr = %q", got)
	}
}

var urlErr = url.Error{Op: "Get", URL: "https://api.example/?apikey=SECRET", Err: errors.New("connection refused")}

func TestReleaseOverviewMarksSeriesPacks(t *testing.T) {
	rv := service.ReleaseView{
		Season: 2, Collection: true, Cached: map[string]bool{"torbox": true},
		Release: model.Release{InfoHash: strings.Repeat("a", 40), Title: "Dungeon Crawler Carl Books 1-8", Size: 20 << 30, Parsed: model.ParsedTitle{Format: "m4b"}},
		Files:   []model.File{{Index: 1, Path: "Pack/Dungeon Crawler Carl, Book 1.m4b", Size: 1 << 30}},
	}
	want := "S2 · M4B · 1.0 GB · from a series pack · ⚡ TB\nDungeon Crawler Carl Books 1-8"
	if got := releaseOverview(rv); got != want {
		t.Errorf("overview = %q, want %q", got, want)
	}
	if vs := buildVideos(model.Book{ASIN: "B002V0QK4C"}, []service.ReleaseView{{Season: 1, Collection: true}}); len(vs) != 0 {
		t.Errorf("a pack without files must not get an AutoFile video: %+v", vs)
	}
	rv.Collection = false
	if got := releaseOverview(rv); strings.Contains(got, "series pack") || !strings.Contains(got, "20.0 GB") {
		t.Errorf("plain release overview = %q", got)
	}
}
