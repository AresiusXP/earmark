package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func get(t *testing.T, h http.Handler, path string) (int, map[string]any, http.Header) {
	t.Helper()
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
	var body map[string]any
	if rec.Code == http.StatusOK {
		if err := json.Unmarshal(rec.Body.Bytes(), &body); err != nil {
			t.Fatalf("%s: invalid JSON: %v", path, err)
		}
	}
	if rec.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Fatalf("%s: missing CORS header", path)
	}
	return rec.Code, body, rec.Header()
}

func TestEndpoints(t *testing.T) {
	h := newHandler("http://x.test")
	if code, m, hd := get(t, h, "/manifest.json"); code != 200 || m["name"] == nil || hd.Get("Access-Control-Allow-Origin") != "*" {
		t.Fatalf("manifest %d %v %v", code, m, hd)
	}
	_, c, _ := get(t, h, "/catalog/series/proto-series.json")
	if len(c["metas"].([]any)) != 1 {
		t.Fatalf("catalog %v", c)
	}
	_, meta, _ := get(t, h, "/meta/audiobook/abk-proto:audiobook.json")
	if n := len(meta["meta"].(map[string]any)["videos"].([]any)); n != 13 {
		t.Fatalf("videos = %d", n)
	}
	if _, meta, _ := get(t, h, "/meta/movie/abk-proto:movie.json"); meta["meta"].(map[string]any)["videos"] != nil {
		t.Fatal("movie must have no videos")
	}
	for path, want := range map[string]int{
		"/stream/series/abk-proto:series:1:3.json":       2,
		"/stream/series/abk-proto:series:2:1.json":       3,
		"/stream/movie/abk-proto:movie.json":             3,
		"/stream/series/abk-proto:series:1:99.json":      0,
		"/stream/audiobook/abk-proto:audiobook:2:1.json": 3,
	} {
		_, s, _ := get(t, h, path)
		if got := len(s["streams"].([]any)); got != want {
			t.Errorf("%s: %d streams, want %d", path, got, want)
		}
	}
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/resolve/"+m4bFile, nil))
	if rec.Code != http.StatusFound || rec.Header().Get("Location") != ia+m4bFile {
		t.Fatalf("resolve %d %s", rec.Code, rec.Header().Get("Location"))
	}
	rec = httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/resolve/..%2Fetc", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("traversal %d", rec.Code)
	}
}
