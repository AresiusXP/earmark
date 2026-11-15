package torbox

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

const testKey = "tb-SECRET-key-123"

const hashA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

type route func(w http.ResponseWriter, r *http.Request)

type fakeTorBox struct {
	t      *testing.T
	mu     sync.Mutex
	routes map[string]route // "METHOD /path"
	calls  map[string]int
	reqs   []*http.Request
	bodies []string
}

func newFake(t *testing.T, routes map[string]route) (*fakeTorBox, *Client) {
	t.Helper()
	f := &fakeTorBox{t: t, routes: routes, calls: map[string]int{}}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		k := r.Method + " " + r.URL.Path
		f.mu.Lock()
		f.calls[k]++
		f.reqs = append(f.reqs, r)
		f.bodies = append(f.bodies, string(body))
		h := f.routes[k]
		f.mu.Unlock()
		if got := r.Header.Get("Authorization"); got != "Bearer "+testKey {
			t.Errorf("%s: Authorization = %q", k, got)
		}
		if h == nil {
			t.Errorf("unexpected request %s", k)
			http.NotFound(w, r)
			return
		}
		r.Body = io.NopCloser(strings.NewReader(string(body)))
		h(w, r)
	}))
	t.Cleanup(srv.Close)
	return f, New(srv.URL+"/v1/", srv.Client())
}

func (f *fakeTorBox) count(k string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls[k]
}

func reply(status int, body string) route {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}
}

func okData(data string) route {
	return reply(200, `{"success":true,"error":null,"detail":"ok","data":`+data+`}`)
}

func TestValidate(t *testing.T) {
	tests := []struct {
		name string
		key  string
		resp route
		want error // nil = success
		reqs int
	}{
		{"ok", testKey, okData(`{"id":1}`), nil, 1},
		{"http 401", testKey, reply(401, `{"success":false,"error":"BAD_TOKEN","detail":"bad"}`), debrid.ErrBadKey, 1},
		{"http 403 non json", testKey, reply(403, `forbidden`), debrid.ErrBadKey, 1},
		{"200 but success false BAD_TOKEN", testKey, reply(200, `{"success":false,"error":"BAD_TOKEN","detail":"x"}`), debrid.ErrBadKey, 1},
		{"429", testKey, reply(429, `{"success":false,"error":"RATE_LIMIT_EXCEEDED","detail":"300 per 1 minute"}`), debrid.ErrRateLimited, 1},
		{"empty key", "", okData(`{}`), debrid.ErrBadKey, 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, c := newFake(t, map[string]route{"GET /v1/api/user/me": tt.resp})
			err := c.Validate(context.Background(), tt.key)
			if !errors.Is(err, tt.want) && !(tt.want == nil && err == nil) {
				t.Fatalf("err = %v, want %v", err, tt.want)
			}
			if got := f.count("GET /v1/api/user/me"); got != tt.reqs {
				t.Errorf("requests = %d, want %d", got, tt.reqs)
			}
		})
	}
}

func TestCheckCached(t *testing.T) {
	tests := []struct {
		name   string
		in     []string
		resp   string
		want   map[string]debrid.Cached
		wantEr error
	}{
		{
			name: "files, lowercase, unknown hash ignored",
			in:   []string{"AAAA", "bbbb"},
			resp: `{"success":true,"data":[
				{"hash":"aaaa","name":"x","files":[{"id":3,"name":"Book/ch1.mp3","size":10},{"id":4,"name":"Book/ch2.mp3","size":20}]},
				{"hash":"zzzz","files":[{"id":0,"name":"n","size":1}]}]}`,
			want: map[string]debrid.Cached{"aaaa": {Files: []model.File{
				{Index: 3, Path: "Book/ch1.mp3", Size: 10}, {Index: 4, Path: "Book/ch2.mp3", Size: 20}}}},
		},
		{name: "data null", in: []string{"aaaa"}, resp: `{"success":true,"data":null}`, want: map[string]debrid.Cached{}},
		{name: "data missing", in: []string{"aaaa"}, resp: `{"success":true}`, want: map[string]debrid.Cached{}},
		{name: "empty list", in: []string{"aaaa"}, resp: `{"success":true,"data":[]}`, want: map[string]debrid.Cached{}},
		{
			name: "files null and null name/size",
			in:   []string{"aaaa", "bbbb"},
			resp: `{"success":true,"data":[{"hash":"aaaa","files":null},{"hash":"bbbb","files":[{"id":1,"name":null,"size":null}]}]}`,
			want: map[string]debrid.Cached{
				"aaaa": {Files: []model.File{}},
				"bbbb": {Files: []model.File{{Index: 1}}},
			},
		},
		{
			name: "missing ids fall back to position",
			in:   []string{"aaaa"},
			resp: `{"success":true,"data":[{"hash":"aaaa","files":[{"name":"a","size":1},{"name":"b","size":2}]}]}`,
			want: map[string]debrid.Cached{"aaaa": {Files: []model.File{{Index: 0, Path: "a", Size: 1}, {Index: 1, Path: "b", Size: 2}}}},
		},
		{name: "bad key", in: []string{"aaaa"}, resp: "401:" + `{"success":false,"error":"BAD_TOKEN","detail":"d"}`, wantEr: debrid.ErrBadKey},
		{name: "rate limited", in: []string{"aaaa"}, resp: "429:" + `{"detail":"slow down"}`, wantEr: debrid.ErrRateLimited},
		{name: "garbage", in: []string{"aaaa"}, resp: `<html>`, wantEr: errAny},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			status, body := 200, tt.resp
			if i := strings.Index(body, ":"); i == 3 {
				status, _ = strconv.Atoi(body[:3])
				body = body[4:]
			}
			f, c := newFake(t, map[string]route{"POST /v1/api/torrents/checkcached": func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Query().Get("format") != "list" {
					t.Errorf("format = %q", r.URL.Query().Get("format"))
				}
				reply(status, body)(w, r)
			}})
			got, err := c.CheckCached(context.Background(), testKey, tt.in)
			if tt.wantEr != nil {
				if err == nil || (tt.wantEr != errAny && !errors.Is(err, tt.wantEr)) {
					t.Fatalf("err = %v, want %v", err, tt.wantEr)
				}
				if got != nil {
					t.Errorf("map must be nil on error")
				}
				if f.count("POST /v1/api/torrents/checkcached") != 1 {
					t.Errorf("must not retry")
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if fmt.Sprint(got) != fmt.Sprint(tt.want) {
				t.Errorf("got %v, want %v", got, tt.want)
			}
		})
	}
}

var errAny = errors.New("any error")

func TestCheckCachedBatching(t *testing.T) {
	var hashes []string
	for i := range 250 {
		hashes = append(hashes, fmt.Sprintf("%040X", i))
	}
	hashes = append(hashes, strings.ToLower(hashes[0]), "", "  ") // duplicate and blanks are dropped

	var sizes []int
	f, c := newFake(t, nil)
	f.routes = map[string]route{"POST /v1/api/torrents/checkcached": func(w http.ResponseWriter, r *http.Request) {
		var body struct{ Hashes []string }
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			t.Error(err)
		}
		f.mu.Lock()
		sizes = append(sizes, len(body.Hashes))
		f.mu.Unlock()
		var entries []string
		for _, h := range body.Hashes {
			if h != strings.ToLower(h) {
				t.Errorf("hash not lowercased: %s", h)
			}
			entries = append(entries, fmt.Sprintf(`{"hash":%q,"files":[{"id":0,"name":"f.mp3","size":1}]}`, h))
		}
		okData("["+strings.Join(entries, ",")+"]")(w, r)
	}}
	got, err := c.CheckCached(context.Background(), testKey, hashes)
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 250 {
		t.Errorf("results = %d, want 250", len(got))
	}
	if fmt.Sprint(sizes) != "[100 100 50]" {
		t.Errorf("batch sizes = %v, want [100 100 50]", sizes)
	}
}

func TestCheckCachedNoHashesNoRequest(t *testing.T) {
	f, c := newFake(t, map[string]route{})
	got, err := c.CheckCached(context.Background(), testKey, nil)
	if err != nil || len(got) != 0 || len(f.reqs) != 0 {
		t.Errorf("got %v, %v, %d requests", got, err, len(f.reqs))
	}
}

func TestCheckCachedBatchErrorStops(t *testing.T) {
	var hashes []string
	for i := range 250 {
		hashes = append(hashes, fmt.Sprintf("%040x", i))
	}
	n := 0
	f, c := newFake(t, nil)
	f.routes = map[string]route{"POST /v1/api/torrents/checkcached": func(w http.ResponseWriter, r *http.Request) {
		n++
		if n == 2 {
			reply(429, `{}`)(w, r)
			return
		}
		okData(`[]`)(w, r)
	}}
	_, err := c.CheckCached(context.Background(), testKey, hashes)
	if !errors.Is(err, debrid.ErrRateLimited) {
		t.Fatalf("err = %v", err)
	}
	if got := f.count("POST /v1/api/torrents/checkcached"); got != 2 {
		t.Errorf("requests = %d, want 2 (no retry, no further batches)", got)
	}
}

func itemJSON(id int, hash, state string, present, finished bool, progress float64, files string) string {
	return fmt.Sprintf(`{"id":%d,"hash":%q,"download_state":%q,"download_present":%t,"download_finished":%t,"active":true,"progress":%g,"files":%s}`,
		id, hash, state, present, finished, progress, files)
}

const twoFiles = `[{"id":0,"name":"B/ch1.mp3","size":1},{"id":1,"name":"B/ch2.mp3","size":2}]`

func TestResolve(t *testing.T) {
	const dl = "https://cdn.example/signed/abc"
	dlOK := func(t *testing.T) route {
		return func(w http.ResponseWriter, r *http.Request) {
			q := r.URL.Query()
			want := map[string]string{"token": testKey, "torrent_id": "7", "file_id": "1", "redirect": "false"}
			for k, v := range want {
				if q.Get(k) != v {
					t.Errorf("requestdl %s = %q, want %q", k, q.Get(k), v)
				}
			}
			okData(strconv.Quote(dl))(w, r)
		}
	}
	list := func(items ...string) route { return okData("[" + strings.Join(items, ",") + "]") }

	type routes = map[string]route
	tests := []struct {
		name      string
		hash      string
		magnet    string
		routes    func(t *testing.T) routes
		want      debrid.ResolveResult
		wantErr   error
		wantCalls map[string]int
	}{
		{
			name: "in account and ready",
			hash: strings.ToUpper(hashA),
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":    list(itemJSON(7, hashA, "cached", true, true, 1, twoFiles)),
					"GET /v1/api/torrents/requestdl": dlOK(t),
				}
			},
			want:      debrid.ResolveResult{URL: dl},
			wantCalls: map[string]int{"POST /v1/api/torrents/createtorrent": 0},
		},
		{
			name: "in account, finished only, null files",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":    list(itemJSON(7, hashA, "completed", false, true, 1, `null`)),
					"GET /v1/api/torrents/requestdl": dlOK(t),
				}
			},
			want: debrid.ResolveResult{URL: dl},
		},
		{
			name: "found on second page",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist": func(w http.ResponseWriter, r *http.Request) {
						if r.URL.Query().Get("offset") == "0" {
							var items []string
							for i := 0; i < 100; i++ {
								items = append(items, itemJSON(1000+i, fmt.Sprintf("%040d", i), "cached", true, true, 1, `[]`))
							}
							list(items...)(w, r)
							return
						}
						list(itemJSON(7, hashA, "cached", true, true, 1, twoFiles))(w, r)
					},
					"GET /v1/api/torrents/requestdl": dlOK(t),
				}
			},
			want:      debrid.ResolveResult{URL: dl},
			wantCalls: map[string]int{"GET /v1/api/torrents/mylist": 2},
		},
		{
			name: "not in account: created, cached, ready",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist": func(w http.ResponseWriter, r *http.Request) {
						if r.URL.Query().Get("id") == "7" {
							okData(itemJSON(7, hashA, "cached", true, true, 1, twoFiles))(w, r) // single object
							return
						}
						okData(`null`)(w, r)
					},
					"POST /v1/api/torrents/createtorrent": func(w http.ResponseWriter, r *http.Request) {
						if err := r.ParseForm(); err != nil {
							t.Error(err)
						}
						if got := r.PostForm.Get("magnet"); got != "magnet:?xt=urn:btih:"+hashA {
							t.Errorf("magnet = %q", got)
						}
						if got := r.PostForm.Get("allow_zip"); got != "false" {
							t.Errorf("allow_zip = %q", got)
						}
						okData(`{"torrent_id":7,"hash":"`+hashA+`"}`)(w, r)
					},
					"GET /v1/api/torrents/requestdl": dlOK(t),
				}
			},
			want:      debrid.ResolveResult{URL: dl},
			wantCalls: map[string]int{"POST /v1/api/torrents/createtorrent": 1},
		},
		{
			name:   "magnet only, hash derived",
			hash:   "",
			magnet: "magnet:?xt=urn:btih:" + strings.ToUpper(hashA) + "&dn=x",
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":    list(itemJSON(7, hashA, "cached", true, true, 1, twoFiles)),
					"GET /v1/api/torrents/requestdl": dlOK(t),
				}
			},
			want: debrid.ResolveResult{URL: dl},
		},
		{
			name: "downloading is pending with progress",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{"GET /v1/api/torrents/mylist": list(itemJSON(7, hashA, "downloading", false, false, 0.426, twoFiles))}
			},
			want:      debrid.ResolveResult{Pending: true, Progress: 43},
			wantCalls: map[string]int{"GET /v1/api/torrents/requestdl": 0},
		},
		{
			name: "created but not cached: pending",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist": func(w http.ResponseWriter, r *http.Request) {
						if r.URL.Query().Get("id") != "" {
							okData(`[`+itemJSON(7, hashA, "metaDL", false, false, 0, `null`)+`]`)(w, r)
							return
						}
						list()(w, r)
					},
					"POST /v1/api/torrents/createtorrent": okData(`{"torrent_id":7}`),
				}
			},
			want: debrid.ResolveResult{Pending: true},
		},
		{
			name: "queued by torbox: pending",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":         list(),
					"POST /v1/api/torrents/createtorrent": okData(`{"queued_id":99}`),
				}
			},
			want: debrid.ResolveResult{Pending: true},
		},
		{
			name: "created id not visible yet: pending",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":         list(),
					"POST /v1/api/torrents/createtorrent": okData(`{"torrent_id":7}`),
				}
			},
			want: debrid.ResolveResult{Pending: true},
		},
		{
			name: "already queued error: pending",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":         list(),
					"POST /v1/api/torrents/createtorrent": reply(400, `{"success":false,"error":"DIFF_ISSUE","detail":"This torrent is already queued."}`),
				}
			},
			want: debrid.ResolveResult{Pending: true},
		},
		{
			name: "already exists error: uses account item",
			hash: hashA,
			routes: func(t *testing.T) routes {
				n := 0
				return routes{
					"GET /v1/api/torrents/mylist": func(w http.ResponseWriter, r *http.Request) {
						n++
						if n == 1 {
							list()(w, r)
							return
						}
						list(itemJSON(7, hashA, "cached", true, true, 1, twoFiles))(w, r)
					},
					"POST /v1/api/torrents/createtorrent": reply(400, `{"success":false,"error":"DIFF_ISSUE","detail":"Torrent already exists."}`),
					"GET /v1/api/torrents/requestdl":      dlOK(t),
				}
			},
			want: debrid.ResolveResult{URL: dl},
		},
		{
			name: "active limit",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":         list(),
					"POST /v1/api/torrents/createtorrent": reply(200, `{"success":false,"error":"ACTIVE_LIMIT","detail":"too many"}`),
				}
			},
			wantErr: debrid.ErrRateLimited,
		},
		{
			name: "too large",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":         list(),
					"POST /v1/api/torrents/createtorrent": reply(400, `{"success":false,"error":"DOWNLOAD_TOO_LARGE","detail":"big"}`),
				}
			},
			wantErr: debrid.ErrFailed,
		},
		{
			name: "download state error",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{"GET /v1/api/torrents/mylist": list(itemJSON(7, hashA, "error", false, false, 0, `null`))}
			},
			wantErr: debrid.ErrFailed,
		},
		{
			name: "file index not in torrent",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{"GET /v1/api/torrents/mylist": list(itemJSON(7, hashA, "cached", true, true, 1, `[{"id":5,"name":"x","size":1}]`))}
			},
			wantErr:   debrid.ErrNotFound,
			wantCalls: map[string]int{"GET /v1/api/torrents/requestdl": 0},
		},
		{
			name: "requestdl 404",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":    list(itemJSON(7, hashA, "cached", true, true, 1, twoFiles)),
					"GET /v1/api/torrents/requestdl": reply(404, `{"success":false,"error":"ITEM_NOT_FOUND","detail":"gone"}`),
				}
			},
			wantErr: debrid.ErrNotFound,
		},
		{
			name: "requestdl returns no link",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":    list(itemJSON(7, hashA, "cached", true, true, 1, twoFiles)),
					"GET /v1/api/torrents/requestdl": okData(`null`),
				}
			},
			wantErr: errAny,
		},
		{
			name: "429 on list, no retry",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{"GET /v1/api/torrents/mylist": reply(429, `{"success":false,"error":"RATE_LIMIT_EXCEEDED","detail":"300 per 1 minute"}`)}
			},
			wantErr:   debrid.ErrRateLimited,
			wantCalls: map[string]int{"GET /v1/api/torrents/mylist": 1},
		},
		{
			name: "429 on requestdl, no retry",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{
					"GET /v1/api/torrents/mylist":    list(itemJSON(7, hashA, "cached", true, true, 1, twoFiles)),
					"GET /v1/api/torrents/requestdl": reply(429, ``),
				}
			},
			wantErr:   debrid.ErrRateLimited,
			wantCalls: map[string]int{"GET /v1/api/torrents/requestdl": 1},
		},
		{
			name: "bad key",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{"GET /v1/api/torrents/mylist": reply(401, `{"success":false,"error":"BAD_TOKEN","detail":"Invalid token `+testKey+`"}`)}
			},
			wantErr: debrid.ErrBadKey,
		},
		{
			name: "plan restricted",
			hash: hashA,
			routes: func(t *testing.T) routes {
				return routes{"GET /v1/api/torrents/mylist": reply(403, `{"success":false,"error":"PLAN_RESTRICTED_FEATURE","detail":"upgrade"}`)}
			},
			wantErr: debrid.ErrBadKey,
		},
		{
			name:    "no hash at all",
			hash:    "",
			routes:  func(t *testing.T) routes { return routes{} },
			wantErr: debrid.ErrNotFound,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, c := newFake(t, tt.routes(t))
			got, err := c.Resolve(context.Background(), testKey, debrid.ResolveRequest{InfoHash: tt.hash, Magnet: tt.magnet, FileIndex: 1})
			if tt.wantErr != nil {
				if err == nil || (tt.wantErr != errAny && !errors.Is(err, tt.wantErr)) {
					t.Fatalf("err = %v, want %v", err, tt.wantErr)
				}
				if strings.Contains(err.Error(), testKey) {
					t.Errorf("error leaks key: %v", err)
				}
			} else if err != nil {
				t.Fatal(err)
			}
			if got != tt.want {
				t.Errorf("result = %+v, want %+v", got, tt.want)
			}
			for k, n := range tt.wantCalls {
				if f.count(k) != n {
					t.Errorf("calls %s = %d, want %d", k, f.count(k), n)
				}
			}
		})
	}
}

func TestResolveFileIndexZeroAndMissingIDs(t *testing.T) {
	// files without ids are addressed by position, matching CheckCached
	_, c := newFake(t, map[string]route{
		"GET /v1/api/torrents/mylist": okData(`[` + itemJSON(7, hashA, "cached", true, true, 1, `[{"name":"a","size":1},{"name":"b","size":2}]`) + `]`),
		"GET /v1/api/torrents/requestdl": func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Query().Get("file_id") != "0" {
				t.Errorf("file_id = %q", r.URL.Query().Get("file_id"))
			}
			okData(`"https://cdn/x"`)(w, r)
		},
	})
	res, err := c.Resolve(context.Background(), testKey, debrid.ResolveRequest{InfoHash: hashA, FileIndex: 0})
	if err != nil || res.URL != "https://cdn/x" {
		t.Fatalf("%+v, %v", res, err)
	}
}

type roundTripFunc func(*http.Request) (*http.Response, error)

func (f roundTripFunc) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestKeyNeverInErrors(t *testing.T) {
	ctx := context.Background()
	req := debrid.ResolveRequest{InfoHash: hashA, FileIndex: 0}

	// 1. transport failure on requestdl: net/http's *url.Error would embed ?token=<key>.
	failDL := New("https://torbox.invalid/v1", &http.Client{Transport: roundTripFunc(func(r *http.Request) (*http.Response, error) {
		if strings.HasSuffix(r.URL.Path, "/requestdl") {
			return nil, errors.New("connection reset")
		}
		body := `{"success":true,"data":[` + itemJSON(7, hashA, "cached", true, true, 1, `[{"id":0,"name":"a","size":1}]`) + `]}`
		return &http.Response{StatusCode: 200, Body: io.NopCloser(strings.NewReader(body)), Header: http.Header{}}, nil
	})})
	_, err := failDL.Resolve(ctx, testKey, req)
	if err == nil || strings.Contains(err.Error(), testKey) || strings.Contains(err.Error(), "token=") {
		t.Errorf("transport error leaks: %v", err)
	}
	var ue *url.Error
	if errors.As(err, &ue) {
		t.Errorf("url.Error must not be reachable from the chain")
	}

	// 2. real refused connection.
	srv := httptest.NewServer(http.NotFoundHandler())
	dead := New(srv.URL, srv.Client())
	srv.Close()
	for name, call := range map[string]func() error{
		"resolve":  func() error { _, err := dead.Resolve(ctx, testKey, req); return err },
		"cached":   func() error { _, err := dead.CheckCached(ctx, testKey, []string{"aa"}); return err },
		"validate": func() error { return dead.Validate(ctx, testKey) },
	} {
		if err := call(); err == nil || strings.Contains(err.Error(), testKey) {
			t.Errorf("%s: err = %v", name, err)
		}
	}

	// 3. server echoes the key in error bodies (plain text and JSON).
	for _, body := range []string{"bad token " + testKey, `{"success":false,"error":"X","detail":"key ` + testKey + ` rejected"}`} {
		for _, status := range []int{200, 400, 500, 429, 401} {
			_, c := newFake(t, map[string]route{"GET /v1/api/torrents/mylist": reply(status, body), "GET /v1/api/user/me": reply(status, body)})
			_, err := c.Resolve(ctx, testKey, req)
			if err == nil && status != 200 {
				t.Errorf("status %d: expected an error", status)
			}
			if err != nil && strings.Contains(err.Error(), testKey) {
				t.Errorf("status %d body %q leaks: %v", status, body, err)
			}
			if err := c.Validate(ctx, testKey); err != nil && strings.Contains(err.Error(), testKey) {
				t.Errorf("validate leaks: %v", err)
			}
		}
	}
}

func TestContextCancelled(t *testing.T) {
	_, c := newFake(t, map[string]route{})
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	err := c.Validate(ctx, testKey)
	if !errors.Is(err, context.Canceled) {
		t.Errorf("err = %v, want context.Canceled in chain", err)
	}
	if strings.Contains(fmt.Sprint(err), testKey) {
		t.Errorf("leak: %v", err)
	}
}

func TestDefaults(t *testing.T) {
	c := New("", nil)
	if c.base() != DefaultBaseURL || c.HTTP == nil || c.Name() != "torbox" {
		t.Errorf("defaults: %+v", c)
	}
	var _ debrid.Provider = c
}

func TestResolveRejectsMismatchedInput(t *testing.T) {
	ctx := context.Background()
	other := strings.Repeat("b", 40)
	tests := []struct {
		name string
		req  debrid.ResolveRequest
	}{
		{"short hash", debrid.ResolveRequest{InfoHash: "abc"}},
		{"non-hex hash", debrid.ResolveRequest{InfoHash: strings.Repeat("z", 40)}},
		{"magnet of another torrent", debrid.ResolveRequest{InfoHash: hashA, Magnet: "magnet:?xt=urn:btih:" + other}},
		{"magnet without btih", debrid.ResolveRequest{InfoHash: hashA, Magnet: "magnet:?dn=x"}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			f, c := newFake(t, map[string]route{})
			_, err := c.Resolve(ctx, testKey, tc.req)
			if !errors.Is(err, debrid.ErrNotFound) {
				t.Fatalf("err = %v, want ErrNotFound", err)
			}
			if len(f.reqs) != 0 {
				t.Fatalf("made %d requests", len(f.reqs))
			}
		})
	}
}

func TestResolveRejectsTorrentWithOtherHash(t *testing.T) {
	// createtorrent answers with an id whose item carries a different hash.
	other := strings.Repeat("b", 40)
	_, c := newFake(t, map[string]route{
		"GET /v1/api/torrents/mylist": func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Query().Get("id") == "9" {
				okData(itemJSON(9, other, "cached", true, true, 1, `[{"id":1,"name":"a","size":1}]`))(w, r)
				return
			}
			okData(`[]`)(w, r)
		},
		"POST /v1/api/torrents/createtorrent": okData(`{"torrent_id":9}`),
	})
	_, err := c.Resolve(context.Background(), testKey, debrid.ResolveRequest{InfoHash: hashA})
	if err == nil || !strings.Contains(err.Error(), "does not match") {
		t.Fatalf("err = %v, want hash mismatch", err)
	}
}
