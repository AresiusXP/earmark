// Phase 0 throwaway prototype: a fake Stremio addon serving one public-domain
// audiobook (LibriVox "Alice's Adventures in Wonderland", hosted on archive.org)
// in several shapes, to find out how Stremio clients treat audio-only content.
//
// Usage: go run . [-addr :7000]   (set BASE_URL when behind a proxy/tunnel)
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"strconv"
	"strings"
)

const (
	ia      = "https://archive.org/download/alice_in_wonderland_librivox/"
	cover   = ia + "Alice_in_Wonderland.jpg"
	m4bFile = "AliceInWonderland_librivox.m4b"
	m4bSize = 87525736
	idRoot  = "abk-proto"
)

// MP3 release: 12 chapters, 64 kbps (name, size bytes, duration).
type chapter struct {
	file string
	size int64
	dur  string
}

var chapters = []chapter{
	{"wonderland_ch_01_64kb.mp3", 5124992, "10:40"},
	{"wonderland_ch_02_64kb.mp3", 5886455, "12:15"},
	{"wonderland_ch_03_64kb.mp3", 8512448, "17:44"},
	{"wonderland_ch_04_64kb.mp3", 9544016, "19:52"},
	{"wonderland_ch_05_64kb.mp3", 6473687, "13:29"},
	{"wonderland_ch_06_64kb.mp3", 6206820, "12:55"},
	{"wonderland_ch_07_64kb.mp3", 8371136, "17:26"},
	{"wonderland_ch_08_64kb.mp3", 6392576, "13:19"},
	{"wonderland_ch_09_64kb.mp3", 7264466, "15:08"},
	{"wonderland_ch_10_64kb.mp3", 10947200, "22:48"},
	{"wonderland_ch_11_64kb.mp3", 4959296, "10:19"},
	{"wonderland_ch_12_64kb.mp3", 6179840, "12:52"},
}

// variant = the Stremio content type the same book is exposed as.
var variants = []struct{ typ, catalog, name string }{
	{"audiobook", "proto-audiobook", "PROTO custom type 'audiobook' (releases = seasons)"},
	{"series", "proto-series", "PROTO type 'series' (releases = seasons)"},
	{"movie", "proto-movie", "PROTO type 'movie' (releases = streams)"},
}

func main() {
	addr := flag.String("addr", ":7000", "listen address")
	flag.Parse()
	h := newHandler(os.Getenv("BASE_URL"))
	log.Printf("phase0 addon listening on %s", *addr)
	log.Fatal(http.ListenAndServe(*addr, h))
}

type server struct{ baseURL string }

func newHandler(baseURL string) http.Handler {
	s := &server{baseURL: strings.TrimRight(baseURL, "/")}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /manifest.json", s.manifest)
	mux.HandleFunc("GET /catalog/{type}/{id}", s.catalog)
	mux.HandleFunc("GET /catalog/{type}/{id}/{extra}", s.catalog)
	mux.HandleFunc("GET /meta/{type}/{id}", s.meta)
	mux.HandleFunc("GET /stream/{type}/{id}", s.stream)
	mux.HandleFunc("GET /resolve/{file}", s.resolve)
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "Phase 0 prototype. Install: stremio://%s/manifest.json\n", r.Host)
	})
	return cors(logging(mux))
}

func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Headers", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func logging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("%s %s (UA %q)", r.Method, r.URL.Path, r.UserAgent())
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

func trimJSON(s string) string { return strings.TrimSuffix(s, ".json") }

func (s *server) base(r *http.Request) string {
	if s.baseURL != "" {
		return s.baseURL
	}
	proto := "http"
	if p := r.Header.Get("X-Forwarded-Proto"); p != "" {
		proto = p
	}
	return proto + "://" + r.Host
}

func (s *server) manifest(w http.ResponseWriter, _ *http.Request) {
	var cats []map[string]any
	var types []string
	for _, v := range variants {
		types = append(types, v.typ)
		cats = append(cats, map[string]any{"type": v.typ, "id": v.catalog, "name": v.name})
	}
	writeJSON(w, map[string]any{
		"id":          "xyz.aresius.audiobooks.phase0",
		"version":     "0.0.1",
		"name":        "Audiobooks PROTO",
		"description": "Phase 0 throwaway prototype: one public-domain audiobook in several shapes.",
		"logo":        cover,
		"types":       types,
		"catalogs":    cats,
		"resources": []any{
			"catalog",
			map[string]any{"name": "meta", "types": types, "idPrefixes": []string{idRoot}},
			map[string]any{"name": "stream", "types": types, "idPrefixes": []string{idRoot}},
		},
		"behaviorHints": map[string]any{"configurable": false},
	})
}

func itemID(typ string) string { return idRoot + ":" + typ }

func (s *server) catalog(w http.ResponseWriter, r *http.Request) {
	typ := r.PathValue("type")
	writeJSON(w, map[string]any{"metas": []any{map[string]any{
		"id": itemID(typ), "type": typ, "name": "Alice's Adventures in Wonderland",
		"poster": cover, "posterShape": "square",
	}}})
}

func (s *server) meta(w http.ResponseWriter, r *http.Request) {
	typ := r.PathValue("type")
	id := trimJSON(r.PathValue("id"))
	if id != itemID(typ) {
		http.NotFound(w, r)
		return
	}
	m := map[string]any{
		"id": id, "type": typ, "name": "Alice's Adventures in Wonderland",
		"poster": cover, "posterShape": "square", "background": cover,
		"description": "Lewis Carroll, read by LibriVox volunteers. Public domain test book for the audiobooks addon.",
		"releaseInfo": "1865", "genres": []string{"Fantasy", "Children"}, "runtime": "2h 41m",
		"links": []any{
			map[string]any{"name": "Lewis Carroll", "category": "author", "url": "stremio:///search?search=Lewis%20Carroll"},
		},
	}
	if typ != "movie" {
		var videos []any
		for i, c := range chapters {
			videos = append(videos, map[string]any{
				"id": fmt.Sprintf("%s:1:%d", id, i+1), "title": fmt.Sprintf("Chapter %d (%s)", i+1, c.dur),
				"season": 1, "episode": i + 1, "released": fmt.Sprintf("2000-01-%02dT00:00:00.000Z", i+1),
				"thumbnail": cover,
			})
		}
		videos = append(videos, map[string]any{
			"id": id + ":2:1", "title": "Full book (single M4B)", "season": 2, "episode": 1,
			"released": "2000-02-01T00:00:00.000Z", "thumbnail": cover,
		})
		m["videos"] = videos
	}
	writeJSON(w, map[string]any{"meta": m})
}

func (s *server) stream(w http.ResponseWriter, r *http.Request) {
	typ := r.PathValue("type")
	id := trimJSON(r.PathValue("id"))
	root := itemID(typ)
	base := s.base(r)
	streams := []any{}
	switch {
	case id == root: // movie: no video id
		streams = m4bStreams(base, "")
	case strings.HasPrefix(id, root+":"):
		parts := strings.Split(strings.TrimPrefix(id, root+":"), ":")
		if len(parts) == 2 {
			season, _ := strconv.Atoi(parts[0])
			ep, _ := strconv.Atoi(parts[1])
			switch {
			case season == 1 && ep >= 1 && ep <= len(chapters):
				streams = mp3Streams(base, ep)
			case season == 2 && ep == 1:
				streams = m4bStreams(base, "")
			}
		}
	}
	writeJSON(w, map[string]any{"streams": streams})
}

func mp3Streams(base string, ep int) []any {
	c := chapters[ep-1]
	hints := func(extra map[string]any) map[string]any {
		h := map[string]any{"bingeGroup": "abk-proto-mp3", "filename": c.file, "videoSize": c.size}
		for k, v := range extra {
			h[k] = v
		}
		return h
	}
	return []any{
		map[string]any{"name": "ABK", "title": "MP3 64k direct\n" + c.file, "url": ia + c.file, "behaviorHints": hints(nil)},
		map[string]any{"name": "ABK", "title": "MP3 64k via /resolve (302)\n" + c.file, "url": base + "/resolve/" + c.file, "behaviorHints": hints(nil)},
	}
}

func m4bStreams(base, _ string) []any {
	hints := func(extra map[string]any) map[string]any {
		h := map[string]any{"bingeGroup": "abk-proto-m4b", "filename": m4bFile, "videoSize": m4bSize}
		for k, v := range extra {
			h[k] = v
		}
		return h
	}
	return []any{
		map[string]any{"name": "ABK", "title": "M4B direct (default hints)", "url": ia + m4bFile, "behaviorHints": hints(nil)},
		map[string]any{"name": "ABK", "title": "M4B direct (notWebReady)", "url": ia + m4bFile, "behaviorHints": hints(map[string]any{"notWebReady": true})},
		map[string]any{"name": "ABK", "title": "M4B via /resolve (302)", "url": base + "/resolve/" + m4bFile, "behaviorHints": hints(nil)},
	}
}

func (s *server) resolve(w http.ResponseWriter, r *http.Request) {
	file := r.PathValue("file")
	if strings.ContainsAny(file, "/\\") || strings.Contains(file, "..") {
		http.NotFound(w, r)
		return
	}
	known := file == m4bFile
	for _, c := range chapters {
		known = known || c.file == file
	}
	if !known {
		http.NotFound(w, r)
		return
	}
	http.Redirect(w, r, ia+file, http.StatusFound)
}
