package realdebrid

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
	"time"

	"github.com/AresiusXP/earmark/server/internal/debrid"
	"github.com/AresiusXP/earmark/server/internal/model"
)

const testKey = "rd-SECRET-key-123"

const (
	hashA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	hashB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
)

// fakeRD is a small stateful Real-Debrid. Tests mutate torrents between calls to move a
// torrent through its statuses; routes overrides single endpoints.
type fakeRD struct {
	t        *testing.T
	mu       sync.Mutex
	torrents []*torrent
	nextID   int
	hidden   bool // GET /torrents does not list anything (eventual consistency)
	// addFn configures a freshly added torrent (files, status).
	addFn func(f *fakeRD, tr *torrent)
	// afterSelect sets the status after selectFiles; default "downloading".
	afterSelect func(f *fakeRD, tr *torrent)
	download    func(link string) string
	routes      map[string]route
	calls       map[string]int
	bodies      map[string][]string
}

type route func(w http.ResponseWriter, r *http.Request)

func jsonReply(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func reply(status int, body string) route {
	return func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = io.WriteString(w, body)
	}
}

func newFake(t *testing.T) (*fakeRD, *Client) {
	t.Helper()
	f := &fakeRD{
		t: t, calls: map[string]int{}, bodies: map[string][]string{}, routes: map[string]route{},
		download: func(link string) string {
			return "https://cdn.example/" + strings.TrimPrefix(link, "https://real-debrid.com/d/")
		},
		addFn: func(_ *fakeRD, tr *torrent) {
			tr.Status = "magnet_conversion"
		},
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/", f.serve)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	c := New(srv.URL+"/rest/1.0/", srv.Client())
	c.MinInterval = 0
	return f, c
}

func (f *fakeRD) count(k string) int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls[k]
}

func (f *fakeRD) body(k string, i int) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	if i >= len(f.bodies[k]) {
		return ""
	}
	return f.bodies[k][i]
}

func (f *fakeRD) find(id string) *torrent {
	for _, tr := range f.torrents {
		if tr.ID == id {
			return tr
		}
	}
	return nil
}

// with runs fn on the torrent under the lock.
func (f *fakeRD) with(id string, fn func(tr *torrent)) {
	f.mu.Lock()
	defer f.mu.Unlock()
	tr := f.find(id)
	if tr == nil {
		f.t.Fatalf("fake: no torrent %q", id)
	}
	fn(tr)
}

func (f *fakeRD) put(tr torrent) *torrent {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.nextID++
	if tr.ID == "" {
		tr.ID = fmt.Sprintf("T%d", f.nextID)
	}
	p := &tr
	f.torrents = append(f.torrents, p)
	return p
}

// finish marks the torrent downloaded with one link per selected file, like RD.
func finish(tr *torrent) {
	tr.Status, tr.Progress, tr.Links = "downloaded", 100, nil
	for _, sf := range tr.selected() {
		tr.Links = append(tr.Links, "https://real-debrid.com/d/"+tr.ID+"-"+strconv.Itoa(sf.ID))
	}
}

func audio(id int, name string, size int64) file {
	return file{ID: id, Path: "/" + name, Bytes: size}
}

func (f *fakeRD) serve(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	path := strings.TrimPrefix(r.URL.Path, "/rest/1.0")
	k := r.Method + " " + path
	f.mu.Lock()
	f.calls[k]++
	f.bodies[k] = append(f.bodies[k], string(body))
	h := f.routes[k]
	f.mu.Unlock()
	if got := r.Header.Get("Authorization"); got != "Bearer "+testKey {
		f.t.Errorf("%s: Authorization = %q", k, got)
	}
	if strings.Contains(r.URL.String(), testKey) {
		f.t.Errorf("%s: key leaked in the URL", k)
	}
	if h != nil {
		r.Body = io.NopCloser(strings.NewReader(string(body)))
		h(w, r)
		return
	}
	form, _ := url.ParseQuery(string(body))
	f.mu.Lock()
	defer f.mu.Unlock()
	switch {
	case k == "GET /user":
		jsonReply(w, 200, map[string]any{"id": 1, "username": "u"})
	case k == "GET /torrents":
		page, _ := strconv.Atoi(r.URL.Query().Get("page"))
		limit, _ := strconv.Atoi(r.URL.Query().Get("limit"))
		if page < 1 || limit < 1 {
			f.t.Errorf("list: bad page/limit %q", r.URL.RawQuery)
		}
		if f.hidden || len(f.torrents) == 0 || (page-1)*limit >= len(f.torrents) {
			w.WriteHeader(204)
			return
		}
		items := []torrent{}
		for _, tr := range f.torrents[(page-1)*limit : min(page*limit, len(f.torrents))] {
			it := *tr
			it.Files = nil
			items = append(items, it)
		}
		jsonReply(w, 200, items)
	case k == "POST /torrents/addMagnet":
		m := btihRe.FindStringSubmatch(form.Get("magnet"))
		if m == nil {
			jsonReply(w, 400, map[string]any{"error": "bad magnet", "error_code": 30})
			return
		}
		f.nextID++
		tr := &torrent{ID: fmt.Sprintf("T%d", f.nextID), Hash: strings.ToLower(m[1])}
		f.addFn(f, tr)
		f.torrents = append(f.torrents, tr)
		jsonReply(w, 201, map[string]any{"id": tr.ID, "uri": "https://api.real-debrid.com/rest/1.0/torrents/info/" + tr.ID})
	case strings.HasPrefix(path, "/torrents/info/") && r.Method == "GET":
		tr := f.find(strings.TrimPrefix(path, "/torrents/info/"))
		if tr == nil {
			jsonReply(w, 404, map[string]any{"error": "unknown_ressource", "error_code": 7})
			return
		}
		jsonReply(w, 200, tr)
	case strings.HasPrefix(path, "/torrents/selectFiles/") && r.Method == "POST":
		tr := f.find(strings.TrimPrefix(path, "/torrents/selectFiles/"))
		if tr == nil {
			jsonReply(w, 404, map[string]any{"error": "unknown_ressource", "error_code": 7})
			return
		}
		sel := map[int]bool{}
		if fs := form.Get("files"); fs == "all" {
			for _, fl := range tr.Files {
				sel[fl.ID] = true
			}
		} else {
			for _, p := range strings.Split(fs, ",") {
				id, _ := strconv.Atoi(p)
				sel[id] = true
			}
		}
		for i := range tr.Files {
			tr.Files[i].Selected = 0
			if sel[tr.Files[i].ID] {
				tr.Files[i].Selected = 1
			}
		}
		if f.afterSelect != nil {
			f.afterSelect(f, tr)
		} else {
			tr.Status, tr.Progress = "downloading", 0
		}
		w.WriteHeader(204)
	case path == "/unrestrict/link" && r.Method == "POST":
		link := form.Get("link")
		jsonReply(w, 200, map[string]any{"id": "X", "link": link, "download": f.download(link)})
	case strings.HasPrefix(path, "/torrents/delete/") && r.Method == "DELETE":
		id := strings.TrimPrefix(path, "/torrents/delete/")
		for i, tr := range f.torrents {
			if tr.ID == id {
				f.torrents = append(f.torrents[:i], f.torrents[i+1:]...)
				w.WriteHeader(204)
				return
			}
		}
		jsonReply(w, 404, map[string]any{"error": "unknown_ressource", "error_code": 7})
	default:
		f.t.Errorf("unexpected request %s", k)
		http.NotFound(w, r)
	}
}

func resolve(c *Client, hash string, idx int) (debrid.ResolveResult, error) {
	return c.Resolve(context.Background(), testKey, debrid.ResolveRequest{InfoHash: hash, FileIndex: idx})
}

func TestValidate(t *testing.T) {
	tests := []struct {
		name string
		key  string
		resp route
		want error
		reqs int
	}{
		{"ok", testKey, reply(200, `{"id":1}`), nil, 1},
		{"401 bad token", testKey, reply(401, `{"error":"bad_token","error_code":8}`), debrid.ErrBadKey, 1},
		{"403 permission denied", testKey, reply(403, `{"error":"permission_denied","error_code":9}`), debrid.ErrBadKey, 1},
		{"403 non json", testKey, reply(403, `forbidden`), debrid.ErrBadKey, 1},
		{"429", testKey, reply(429, `{"error":"too_many_requests","error_code":34}`), debrid.ErrRateLimited, 1},
		{"503", testKey, reply(503, `Service Unavailable`), errPlain, 1},
		{"empty key", "", reply(200, `{}`), debrid.ErrBadKey, 0},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, c := newFake(t)
			f.routes["GET /user"] = tt.resp
			err := c.Validate(context.Background(), tt.key)
			switch {
			case tt.want == nil && err != nil:
				t.Fatalf("err = %v", err)
			case tt.want == errPlain:
				if err == nil || errors.Is(err, debrid.ErrBadKey) || errors.Is(err, debrid.ErrRateLimited) || errors.Is(err, debrid.ErrNotFound) {
					t.Fatalf("err = %v, want a plain error", err)
				}
			case tt.want != nil && !errors.Is(err, tt.want):
				t.Fatalf("err = %v, want %v", err, tt.want)
			}
			if got := f.count("GET /user"); got != tt.reqs {
				t.Errorf("requests = %d, want %d", got, tt.reqs)
			}
		})
	}
}

var errPlain = errors.New("plain")

func TestCheckCachedNeverCallsTheNetwork(t *testing.T) {
	f, c := newFake(t)
	got, err := c.CheckCached(context.Background(), testKey, []string{hashA})
	if got != nil || err != nil {
		t.Fatalf("CheckCached = %v, %v; want nil, nil", got, err)
	}
	if len(f.calls) != 0 {
		t.Errorf("calls = %v", f.calls)
	}
	if c.Name() != "realdebrid" {
		t.Errorf("Name = %q", c.Name())
	}
}

func TestResolveColdAddSelectsAudioOnly(t *testing.T) {
	f, c := newFake(t)
	f.hidden = true // list never shows the torrent: the id from addMagnet must be used
	// 1: magnet conversion
	res, err := resolve(c, hashA, 3)
	if err != nil || !res.Pending || res.URL != "" {
		t.Fatalf("after add: %+v, %v", res, err)
	}
	if f.count("POST /torrents/addMagnet") != 1 || f.count("GET /torrents") != 1 {
		t.Fatalf("calls = %v", f.calls)
	}
	if got := f.body("POST /torrents/addMagnet", 0); got != "magnet="+url.QueryEscape("magnet:?xt=urn:btih:"+hashA) {
		t.Errorf("addMagnet body = %q", got)
	}

	// 2: the files are known, nothing selected yet
	f.with("T1", func(tr *torrent) {
		tr.Status = "waiting_files_selection"
		tr.Files = []file{audio(1, "Book/cover.jpg", 5), audio(2, "Book/01.mp3", 100), audio(3, "Book/02.mp3", 200), audio(4, "Book/info.txt", 1)}
	})
	res, err = resolve(c, hashA, 3)
	if err != nil || !res.Pending || res.Progress != 0 {
		t.Fatalf("after select: %+v, %v", res, err)
	}
	if got := f.body("POST /torrents/selectFiles/T1", 0); got != "files="+url.QueryEscape("2,3") {
		t.Errorf("selectFiles body = %q, want audio files only", got)
	}

	// 3: downloading with progress
	f.with("T1", func(tr *torrent) { tr.Status, tr.Progress = "downloading", 42 })
	res, err = resolve(c, hashA, 3)
	if err != nil || !res.Pending || res.Progress != 42 {
		t.Fatalf("downloading: %+v, %v", res, err)
	}

	// 4: downloaded; file 3 is the 2nd selected file, so the 2nd link
	f.with("T1", func(tr *torrent) { finish(tr) })
	res, err = resolve(c, hashA, 3)
	if err != nil || res.Pending || res.URL != "https://cdn.example/T1-3" {
		t.Fatalf("downloaded: %+v, %v", res, err)
	}
	if got := f.body("POST /unrestrict/link", 0); got != "link="+url.QueryEscape("https://real-debrid.com/d/T1-3") {
		t.Errorf("unrestrict body = %q", got)
	}
	res, err = resolve(c, hashA, 2)
	if err != nil || res.URL != "https://cdn.example/T1-2" {
		t.Fatalf("file 2: %+v, %v", res, err)
	}
	if n := f.count("POST /torrents/addMagnet"); n != 1 {
		t.Errorf("addMagnet calls = %d, want 1 (no duplicate adds)", n)
	}
	if n := f.count("POST /torrents/selectFiles/T1"); n != 1 {
		t.Errorf("selectFiles calls = %d, want 1", n)
	}
}

func TestResolveCachedTorrentIsImmediatelyDownloaded(t *testing.T) {
	f, c := newFake(t)
	f.addFn = func(_ *fakeRD, tr *torrent) {
		tr.Status = "waiting_files_selection"
		tr.Files = []file{audio(1, "a/small.mp3", 10), audio(2, "a/big.m4b", 900), audio(3, "a/cover.png", 5000)}
	}
	f.afterSelect = func(_ *fakeRD, tr *torrent) { finish(tr) }
	res, err := resolve(c, hashA, debrid.AutoFile)
	if err != nil || res.Pending || res.URL != "https://cdn.example/T1-2" {
		t.Fatalf("Resolve = %+v, %v; want the largest audio file", res, err)
	}
	if got := f.body("POST /torrents/selectFiles/T1", 0); got != "files="+url.QueryEscape("1,2") {
		t.Errorf("selectFiles body = %q", got)
	}
	// a second resolve (hidden list or not) reuses the torrent
	res, err = resolve(c, hashA, 1)
	if err != nil || res.URL != "https://cdn.example/T1-1" {
		t.Fatalf("second Resolve = %+v, %v", res, err)
	}
	if f.count("POST /torrents/addMagnet") != 1 {
		t.Errorf("duplicate add: %v", f.calls)
	}
}

func TestResolveAddedAlreadyDownloaded(t *testing.T) {
	f, c := newFake(t)
	f.addFn = func(_ *fakeRD, tr *torrent) {
		tr.Files = []file{{ID: 1, Path: "/x.mp3", Bytes: 7, Selected: 1}}
		finish(tr)
	}
	res, err := resolve(c, hashA, debrid.AutoFile)
	if err != nil || res.URL != "https://cdn.example/T1-1" {
		t.Fatalf("Resolve = %+v, %v", res, err)
	}
	if f.count("POST /torrents/selectFiles/T1") != 0 {
		t.Error("selectFiles called for an already selected torrent")
	}
}

func TestResolvePackSelection(t *testing.T) {
	pack := func(f *fakeRD, tr *torrent) {
		tr.Status = "waiting_files_selection"
		tr.Files = []file{
			audio(1, "Series/Book 1/01.mp3", 10), audio(2, "Series/Book 1/02.mp3", 10),
			audio(3, "Series/Book 2/01.mp3", 10), audio(4, "Series/Book 2/02.mp3", 10),
			audio(5, "Series/Book 2/booklet.pdf", 3), audio(6, "Series/folder.jpg", 3),
		}
	}
	tests := []struct {
		name      string
		idx       int
		wantSel   string // "" = no selection must happen
		wantError error
	}{
		{"audio file of book 2", 3, "1,2,3,4", nil},
		{"auto", debrid.AutoFile, "1,2,3,4", nil},
		{"requested non audio file is included", 5, "1,2,3,4,5", nil},
		{"file not in torrent", 99, "", debrid.ErrNotFound},
		{"file zero", 0, "", debrid.ErrNotFound},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, c := newFake(t)
			f.addFn = pack
			_, err := resolve(c, hashA, tt.idx)
			if tt.wantError != nil {
				if !errors.Is(err, tt.wantError) {
					t.Fatalf("err = %v, want %v", err, tt.wantError)
				}
			} else if err != nil {
				t.Fatalf("err = %v", err)
			}
			n := f.count("POST /torrents/selectFiles/T1")
			if tt.wantSel == "" {
				if n != 0 {
					t.Errorf("selectFiles calls = %d, want 0", n)
				}
				return
			}
			if got := f.body("POST /torrents/selectFiles/T1", 0); got != "files="+url.QueryEscape(tt.wantSel) {
				t.Errorf("selectFiles body = %q, want %q", got, tt.wantSel)
			}
		})
	}
}

func TestResolveNoAudioFiles(t *testing.T) {
	f, c := newFake(t)
	f.addFn = func(_ *fakeRD, tr *torrent) {
		tr.Status = "waiting_files_selection"
		tr.Files = []file{audio(1, "x.pdf", 1)}
	}
	if _, err := resolve(c, hashA, debrid.AutoFile); !errors.Is(err, debrid.ErrNotFound) {
		t.Fatalf("err = %v, want ErrNotFound", err)
	}
	if f.count("POST /torrents/selectFiles/T1") != 0 {
		t.Error("selected something")
	}
}

func TestResolveDuplicateAddAvoided(t *testing.T) {
	t.Run("found on a later list page", func(t *testing.T) {
		f, c := newFake(t)
		for i := range listPageSize {
			f.put(torrent{Hash: fmt.Sprintf("%040x", i+1000), Status: "downloaded"})
		}
		f.put(torrent{ID: "MINE", Hash: strings.ToUpper(hashA), Status: "downloaded",
			Files: []file{{ID: 1, Path: "/a.mp3", Bytes: 1, Selected: 1}}, Links: []string{"https://real-debrid.com/d/MINE-1"}})
		res, err := resolve(c, hashA, 1)
		if err != nil || res.URL != "https://cdn.example/MINE-1" {
			t.Fatalf("Resolve = %+v, %v", res, err)
		}
		if f.count("POST /torrents/addMagnet") != 0 || f.count("GET /torrents") != 2 {
			t.Errorf("calls = %v", f.calls)
		}
	})
	t.Run("prefers the usable duplicate", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "DEAD", Hash: hashA, Status: "dead"})
		f.put(torrent{ID: "GOOD", Hash: hashA, Status: "downloaded",
			Files: []file{{ID: 2, Path: "/a.mp3", Bytes: 1, Selected: 1}}, Links: []string{"https://real-debrid.com/d/GOOD-2"}})
		res, err := resolve(c, hashA, debrid.AutoFile)
		if err != nil || res.URL != "https://cdn.example/GOOD-2" {
			t.Fatalf("Resolve = %+v, %v", res, err)
		}
	})
	t.Run("other hashes do not match", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "OTHER", Hash: hashB, Status: "downloaded"})
		if _, err := resolve(c, hashA, 1); err != nil {
			t.Fatal(err)
		}
		if f.count("POST /torrents/addMagnet") != 1 {
			t.Errorf("calls = %v", f.calls)
		}
	})
	t.Run("stale remembered id falls back to the list", func(t *testing.T) {
		f, c := newFake(t)
		c.remember(testKey, hashA, "GONE")
		f.put(torrent{ID: "REAL", Hash: hashA, Status: "downloading", Progress: 7})
		res, err := resolve(c, hashA, 1)
		if err != nil || !res.Pending || res.Progress != 7 {
			t.Fatalf("Resolve = %+v, %v", res, err)
		}
		if f.count("POST /torrents/addMagnet") != 0 {
			t.Errorf("calls = %v", f.calls)
		}
	})
	t.Run("remembered ids are per key", func(t *testing.T) {
		_, c := newFake(t)
		c.remember(testKey, hashA, "T9")
		if _, ok := c.recent("other-key", hashA); ok {
			t.Error("id leaked to another key")
		}
		if id, ok := c.recent(testKey, hashA); !ok || id != "T9" {
			t.Errorf("recent = %q, %v", id, ok)
		}
	})
}

func TestResolveStatuses(t *testing.T) {
	tests := []struct {
		status      string
		progress    int
		wantPending bool
		wantErr     error
	}{
		{"magnet_conversion", 0, true, nil},
		{"queued", 0, true, nil},
		{"downloading", 55, true, nil},
		{"compressing", 90, true, nil},
		{"uploading", 99, true, nil},
		{"downloading", 250, true, nil}, // clamped
		{"magnet_error", 0, false, debrid.ErrFailed},
		{"error", 0, false, debrid.ErrFailed},
		{"virus", 0, false, debrid.ErrFailed},
		{"dead", 0, false, debrid.ErrFailed},
		{"something_new", 0, false, debrid.ErrNotFound},
	}
	for _, tt := range tests {
		t.Run(tt.status, func(t *testing.T) {
			f, c := newFake(t)
			f.put(torrent{ID: "T", Hash: hashA, Status: tt.status, Progress: tt.progress})
			res, err := resolve(c, hashA, 1)
			if tt.wantErr != nil {
				if !errors.Is(err, tt.wantErr) {
					t.Fatalf("err = %v, want %v", err, tt.wantErr)
				}
				return
			}
			if err != nil || res.Pending != tt.wantPending || res.URL != "" {
				t.Fatalf("Resolve = %+v, %v", res, err)
			}
			if want := max(0, min(100, tt.progress)); res.Progress != want {
				t.Errorf("Progress = %d, want %d", res.Progress, want)
			}
		})
	}
}

func TestResolveRejectsNonHTTPSLink(t *testing.T) {
	for _, dl := range []string{"http://cdn.example/f.mp3", "ftp://cdn.example/f", "//cdn.example/f", "https:///nohost", "", "javascript:alert(1)"} {
		t.Run(dl, func(t *testing.T) {
			f, c := newFake(t)
			f.download = func(string) string { return dl }
			f.put(torrent{ID: "T", Hash: hashA, Status: "downloaded",
				Files: []file{{ID: 1, Path: "/a.mp3", Bytes: 1, Selected: 1}}, Links: []string{"https://real-debrid.com/d/x"}})
			res, err := resolve(c, hashA, 1)
			if err == nil || res.URL != "" {
				t.Fatalf("Resolve = %+v, %v; want an error and no URL", res, err)
			}
		})
	}
}

func TestResolveIndexMapping(t *testing.T) {
	// 5 files, ids 1..5; RD selected 2, 4 and 5 (links are in that order).
	newTorrent := func() torrent {
		return torrent{ID: "T", Hash: hashA, Status: "downloaded",
			Files: []file{
				{ID: 1, Path: "/cover.jpg", Bytes: 9},
				{ID: 2, Path: "/a.mp3", Bytes: 10, Selected: 1},
				{ID: 3, Path: "/skip.txt", Bytes: 1},
				{ID: 4, Path: "/b.mp3", Bytes: 30, Selected: 1},
				{ID: 5, Path: "/c.mp3", Bytes: 20, Selected: 1},
			},
			Links: []string{"https://real-debrid.com/d/L2", "https://real-debrid.com/d/L4", "https://real-debrid.com/d/L5"}}
	}
	tests := []struct {
		name    string
		idx     int
		want    string
		wantErr error
	}{
		{"first selected", 2, "https://cdn.example/L2", nil},
		{"middle selected", 4, "https://cdn.example/L4", nil},
		{"last selected", 5, "https://cdn.example/L5", nil},
		{"auto = largest selected audio", debrid.AutoFile, "https://cdn.example/L4", nil},
		{"not selected", 3, "", debrid.ErrNotFound},
		{"unselected cover", 1, "", debrid.ErrNotFound},
		{"unknown", 42, "", debrid.ErrNotFound},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, c := newFake(t)
			f.put(newTorrent())
			res, err := resolve(c, hashA, tt.idx)
			if tt.wantErr != nil {
				if !errors.Is(err, tt.wantErr) {
					t.Fatalf("err = %v, want %v", err, tt.wantErr)
				}
				return
			}
			if err != nil || res.URL != tt.want {
				t.Fatalf("Resolve = %+v, %v; want %s", res, err, tt.want)
			}
		})
	}

	t.Run("link count mismatch is an error", func(t *testing.T) {
		f, c := newFake(t)
		tr := newTorrent()
		tr.Links = tr.Links[:1] // e.g. RD packed the files into one archive link
		f.put(tr)
		if _, err := resolve(c, hashA, 4); err == nil {
			t.Fatal("want an error")
		}
		if f.count("POST /unrestrict/link") != 0 {
			t.Error("unrestricted a guessed link")
		}
	})
	t.Run("downloaded without links fails", func(t *testing.T) {
		f, c := newFake(t)
		tr := newTorrent()
		tr.Links = nil
		f.put(tr)
		if _, err := resolve(c, hashA, 4); !errors.Is(err, debrid.ErrFailed) {
			t.Fatalf("err = %v", err)
		}
	})
	t.Run("no selected flags assumes every file", func(t *testing.T) {
		f, c := newFake(t)
		tr := newTorrent()
		for i := range tr.Files {
			tr.Files[i].Selected = 0
		}
		tr.Links = []string{"https://real-debrid.com/d/1", "https://real-debrid.com/d/2", "https://real-debrid.com/d/3", "https://real-debrid.com/d/4", "https://real-debrid.com/d/5"}
		f.put(tr)
		res, err := resolve(c, hashA, 4)
		if err != nil || res.URL != "https://cdn.example/4" {
			t.Fatalf("Resolve = %+v, %v", res, err)
		}
	})
}

func TestFiles(t *testing.T) {
	t.Run("selected files after selection, ids as index", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "T", Hash: hashA, Status: "downloaded", Files: []file{
			{ID: 1, Path: "/Book/cover.jpg", Bytes: 9},
			{ID: 2, Path: "/Book/01.mp3", Bytes: 10, Selected: 1},
			{ID: 3, Path: "/Book/02.mp3", Bytes: 20, Selected: 1},
		}, Links: []string{"a", "b"}})
		got, err := c.Files(context.Background(), testKey, strings.ToUpper(hashA))
		want := []model.File{{Index: 2, Path: "Book/01.mp3", Size: 10}, {Index: 3, Path: "Book/02.mp3", Size: 20}}
		if err != nil || !equalFiles(got, want) {
			t.Fatalf("Files = %v, %v; want %v", got, err, want)
		}
		// Files and Resolve agree on the index
		f.with("T", func(tr *torrent) { finish(tr) })
		for _, mf := range got {
			res, err := resolve(c, hashA, mf.Index)
			if err != nil || res.URL != fmt.Sprintf("https://cdn.example/T-%d", mf.Index) {
				t.Errorf("Resolve(%d) = %+v, %v", mf.Index, res, err)
			}
		}
	})
	t.Run("all files before the selection", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "T", Hash: hashA, Status: "waiting_files_selection", Files: []file{audio(1, "x.jpg", 1), audio(2, "y.mp3", 2)}})
		got, err := c.Files(context.Background(), testKey, hashA)
		if err != nil || len(got) != 2 || got[0].Index != 1 || got[1].Path != "y.mp3" {
			t.Fatalf("Files = %v, %v", got, err)
		}
	})
	t.Run("converting magnet gives an empty non-nil list", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "T", Hash: hashA, Status: "magnet_conversion"})
		got, err := c.Files(context.Background(), testKey, hashA)
		if err != nil || got == nil || len(got) != 0 {
			t.Fatalf("Files = %#v, %v", got, err)
		}
	})
	t.Run("not in account never adds", func(t *testing.T) {
		f, c := newFake(t)
		if _, err := c.Files(context.Background(), testKey, hashA); !errors.Is(err, debrid.ErrNotFound) {
			t.Fatalf("err = %v", err)
		}
		if f.count("POST /torrents/addMagnet") != 0 {
			t.Error("Files added a torrent")
		}
	})
	t.Run("failed torrent and bad input", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "T", Hash: hashA, Status: "dead"})
		if _, err := c.Files(context.Background(), testKey, hashA); !errors.Is(err, debrid.ErrFailed) {
			t.Fatalf("err = %v", err)
		}
		if _, err := c.Files(context.Background(), testKey, "xyz"); !errors.Is(err, debrid.ErrNotFound) {
			t.Fatalf("err = %v", err)
		}
		if _, err := c.Files(context.Background(), "", hashA); !errors.Is(err, debrid.ErrBadKey) {
			t.Fatalf("err = %v", err)
		}
	})
}

func TestStatusIsReadOnly(t *testing.T) {
	sideEffects := func(f *fakeRD) int {
		return f.count("POST /torrents/addMagnet") + f.count("POST /torrents/selectFiles/T") + f.count("POST /unrestrict/link")
	}
	ctx := context.Background()
	t.Run("not in the account", func(t *testing.T) {
		f, c := newFake(t)
		if _, err := c.Status(ctx, testKey, hashA); !errors.Is(err, debrid.ErrNotFound) {
			t.Fatalf("err = %v", err)
		}
		if sideEffects(f) != 0 {
			t.Fatal("Status added a torrent")
		}
	})
	t.Run("waiting for the selection stays pending and is not selected", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "T", Hash: hashA, Status: "waiting_files_selection", Files: []file{audio(1, "a.mp3", 1)}})
		st, err := c.Status(ctx, testKey, hashA)
		if err != nil || st.State != debrid.StatePending {
			t.Fatalf("Status = %+v, %v", st, err)
		}
		if sideEffects(f) != 0 || f.count("POST /torrents/selectFiles/T") != 0 {
			t.Fatal("Status selected files")
		}
	})
	t.Run("downloading reports progress", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "T", Hash: hashA, Status: "downloading", Progress: 42})
		st, err := c.Status(ctx, testKey, hashA)
		if err != nil || st.State != debrid.StatePending || st.Progress != 42 {
			t.Fatalf("Status = %+v, %v", st, err)
		}
	})
	t.Run("downloaded is ready with the selected files and requests no link", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "T", Hash: hashA, Status: "downloaded", Files: []file{
			{ID: 1, Path: "/B/cover.jpg", Bytes: 9},
			{ID: 2, Path: "/B/01.mp3", Bytes: 10, Selected: 1},
		}, Links: []string{"https://real-debrid.com/d/x"}})
		st, err := c.Status(ctx, testKey, strings.ToUpper(hashA))
		if err != nil || st.State != debrid.StateReady || !equalFiles(st.Files, []model.File{{Index: 2, Path: "B/01.mp3", Size: 10}}) {
			t.Fatalf("Status = %+v, %v", st, err)
		}
		if sideEffects(f) != 0 {
			t.Fatal("Status unrestricted a link")
		}
	})
	t.Run("failed, bad key, bad hash", func(t *testing.T) {
		f, c := newFake(t)
		f.put(torrent{ID: "T", Hash: hashA, Status: "magnet_error"})
		if _, err := c.Status(ctx, testKey, hashA); !errors.Is(err, debrid.ErrFailed) {
			t.Fatalf("err = %v", err)
		}
		if _, err := c.Status(ctx, "", hashA); !errors.Is(err, debrid.ErrBadKey) {
			t.Fatalf("err = %v", err)
		}
		if _, err := c.Status(ctx, testKey, "xyz"); !errors.Is(err, debrid.ErrNotFound) {
			t.Fatalf("err = %v", err)
		}
	})
}

func equalFiles(a, b []model.File) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestDelete(t *testing.T) {
	f, c := newFake(t)
	f.put(torrent{ID: "A1", Hash: hashA, Status: "dead"})
	f.put(torrent{ID: "A2", Hash: hashA, Status: "downloaded"})
	f.put(torrent{ID: "B1", Hash: hashB, Status: "downloaded"})
	if err := c.Delete(context.Background(), testKey, hashA); err != nil {
		t.Fatal(err)
	}
	if len(f.torrents) != 1 || f.torrents[0].ID != "B1" {
		t.Errorf("torrents left = %v", f.torrents)
	}
	if err := c.Delete(context.Background(), testKey, hashA); err != nil {
		t.Errorf("deleting an absent torrent: %v", err)
	}
	if err := c.Delete(context.Background(), "", hashA); !errors.Is(err, debrid.ErrBadKey) {
		t.Errorf("err = %v", err)
	}
}

func TestResolveInputValidation(t *testing.T) {
	f, c := newFake(t)
	tests := []struct {
		name string
		key  string
		req  debrid.ResolveRequest
		want error
	}{
		{"empty key", "", debrid.ResolveRequest{InfoHash: hashA}, debrid.ErrBadKey},
		{"bad hash", testKey, debrid.ResolveRequest{InfoHash: "nope"}, debrid.ErrNotFound},
		{"no hash no magnet", testKey, debrid.ResolveRequest{}, debrid.ErrNotFound},
		{"magnet of another hash", testKey, debrid.ResolveRequest{InfoHash: hashA, Magnet: "magnet:?xt=urn:btih:" + hashB}, debrid.ErrNotFound},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if _, err := c.Resolve(context.Background(), tt.key, tt.req); !errors.Is(err, tt.want) {
				t.Fatalf("err = %v, want %v", err, tt.want)
			}
		})
	}
	if len(f.calls) != 0 {
		t.Errorf("calls = %v", f.calls)
	}
	t.Run("hash taken from the magnet, which is sent as is", func(t *testing.T) {
		f, c := newFake(t)
		magnet := "magnet:?xt=urn:btih:" + strings.ToUpper(hashA) + "&dn=Book&tr=udp%3A%2F%2Ft.example%3A1"
		if _, err := c.Resolve(context.Background(), testKey, debrid.ResolveRequest{Magnet: magnet, FileIndex: debrid.AutoFile}); err != nil {
			t.Fatal(err)
		}
		if got := f.body("POST /torrents/addMagnet", 0); got != "magnet="+url.QueryEscape(magnet) {
			t.Errorf("body = %q", got)
		}
	})
}

func TestSelectFilesAlreadyDoneIsTolerated(t *testing.T) {
	f, c := newFake(t)
	f.addFn = func(_ *fakeRD, tr *torrent) {
		tr.Status = "waiting_files_selection"
		tr.Files = []file{audio(1, "a.mp3", 1)}
	}
	f.routes["POST /torrents/selectFiles/T1"] = func(w http.ResponseWriter, _ *http.Request) {
		f.mu.Lock()
		tr := f.find("T1")
		tr.Files[0].Selected = 1
		finish(tr)
		f.mu.Unlock()
		jsonReply(w, 202, map[string]any{"error": "action_already_done", "error_code": 31})
	}
	res, err := resolve(c, hashA, 1)
	if err != nil || res.URL != "https://cdn.example/T1-1" {
		t.Fatalf("Resolve = %+v, %v", res, err)
	}
}

func TestErrorMapping(t *testing.T) {
	tests := []struct {
		name   string
		status int
		body   string
		want   error // nil = plain error
	}{
		{"401", 401, `{"error":"bad_token","error_code":8}`, debrid.ErrBadKey},
		{"401 without body", 401, ``, debrid.ErrBadKey},
		{"account locked", 403, `{"error":"account_locked","error_code":14}`, debrid.ErrBadKey},
		{"too many active downloads", 403, `{"error":"too_many_active_downloads","error_code":21}`, debrid.ErrRateLimited},
		{"fair usage", 403, `{"error":"fair_usage_limit","error_code":36}`, debrid.ErrRateLimited},
		{"slow down", 429, `{"error":"slow_down","error_code":5}`, debrid.ErrRateLimited},
		{"429 non json", 429, `slow`, debrid.ErrRateLimited},
		{"unknown resource", 404, `{"error":"unknown_ressource","error_code":7}`, debrid.ErrNotFound},
		{"unavailable file", 503, `{"error":"file_unavailable","error_code":24}`, debrid.ErrFailed},
		{"infringing", 451, `{"error":"infringing_file","error_code":35}`, debrid.ErrFailed},
		{"torrent too big", 400, `{"error":"torrent_too_big","error_code":29}`, debrid.ErrFailed},
		{"service unavailable", 503, `{"error":"service_unavailable","error_code":25}`, nil},
		{"500", 500, `oops`, nil},
		{"403 other code", 403, `{"error":"ip_not_allowed","error_code":22}`, nil},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, c := newFake(t)
			f.routes["GET /user"] = reply(tt.status, tt.body)
			err := c.Validate(context.Background(), testKey)
			if err == nil {
				t.Fatal("want error")
			}
			for _, s := range []error{debrid.ErrBadKey, debrid.ErrRateLimited, debrid.ErrNotFound, debrid.ErrFailed} {
				if got, want := errors.Is(err, s), s == tt.want; got != want {
					t.Errorf("errors.Is(err, %v) = %v, want %v (err = %v)", s, got, want, err)
				}
			}
		})
	}
}

func TestRateLimitBlocksTheKey(t *testing.T) {
	f, c := newFake(t)
	clock := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	c.now = func() time.Time { return clock }
	f.routes["GET /user"] = func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Retry-After", "30")
		jsonReply(w, 429, map[string]any{"error": "too_many_requests", "error_code": 34})
	}
	err := c.Validate(context.Background(), testKey)
	var rl *RateLimitError
	if !errors.Is(err, debrid.ErrRateLimited) || !errors.As(err, &rl) || rl.RetryAfter != 30*time.Second {
		t.Fatalf("err = %v (%+v)", err, rl)
	}
	// blocked: fails fast, no request
	clock = clock.Add(10 * time.Second)
	err = c.Validate(context.Background(), testKey)
	if !errors.Is(err, debrid.ErrRateLimited) || !errors.As(err, &rl) || rl.RetryAfter != 20*time.Second {
		t.Fatalf("blocked err = %v (%+v)", err, rl)
	}
	if _, err := resolve(c, hashA, 1); !errors.Is(err, debrid.ErrRateLimited) {
		t.Fatalf("Resolve while blocked: %v", err)
	}
	if n := f.count("GET /user"); n != 1 {
		t.Errorf("requests while blocked = %d, want 1", n)
	}
	// other keys are not affected: the fake rejects them with a wrong-key error only if reached
	if err := c.throttle(context.Background(), "someone else"); err != nil {
		t.Errorf("other key blocked: %v", err)
	}
	// after the block the key may call again
	clock = clock.Add(21 * time.Second)
	f.routes["GET /user"] = reply(200, `{}`)
	if err := c.Validate(context.Background(), testKey); err != nil {
		t.Fatalf("after block: %v", err)
	}
}

func TestRetryAfterParsing(t *testing.T) {
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	tests := []struct {
		in   string
		want time.Duration
	}{
		{"", defaultRetryAfter},
		{"abc", defaultRetryAfter},
		{"7", 7 * time.Second},
		{"0", time.Second},
		{"-3", defaultRetryAfter},
		{"100000", maxRetryAfter},
		{now.Add(45 * time.Second).UTC().Format(http.TimeFormat), 45 * time.Second},
		{now.Add(-time.Hour).UTC().Format(http.TimeFormat), time.Second},
	}
	for _, tt := range tests {
		if got := retryAfter(tt.in, now); got != tt.want {
			t.Errorf("retryAfter(%q) = %v, want %v", tt.in, got, tt.want)
		}
	}
}

func TestThrottleSpacesRequestsPerKey(t *testing.T) {
	f, c := newFake(t)
	c.MinInterval = 40 * time.Millisecond
	start := time.Now()
	for range 4 {
		if err := c.Validate(context.Background(), testKey); err != nil {
			t.Fatal(err)
		}
	}
	if el := time.Since(start); el < 110*time.Millisecond {
		t.Errorf("4 requests took %v, want >= 3 intervals", el)
	}
	// another key starts at once
	start = time.Now()
	if err := c.throttle(context.Background(), "another key"); err != nil || time.Since(start) > 30*time.Millisecond {
		t.Errorf("other key waited %v, %v", time.Since(start), err)
	}
	if f.count("GET /user") != 4 {
		t.Errorf("calls = %v", f.calls)
	}
}

func TestThrottleHonoursContext(t *testing.T) {
	_, c := newFake(t)
	c.MinInterval = time.Hour
	if err := c.throttle(context.Background(), testKey); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if err := c.throttle(ctx, testKey); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v", err)
	}
}

func TestStateKeyedByFingerprintNotByKey(t *testing.T) {
	_, c := newFake(t)
	c.MinInterval = time.Millisecond
	c.remember(testKey, hashA, "T1")
	_ = c.throttle(context.Background(), testKey)
	c.block(testKey, time.Minute)
	c.mu.Lock()
	defer c.mu.Unlock()
	for _, m := range []map[string]time.Time{c.next, c.hold} {
		for k := range m {
			if strings.Contains(k, testKey) {
				t.Errorf("plain key used as map key: %q", k)
			}
		}
	}
	for k := range c.adds {
		if strings.Contains(k, testKey) {
			t.Errorf("plain key used as map key: %q", k)
		}
	}
}

func TestErrorsNeverContainTheKey(t *testing.T) {
	f, c := newFake(t)
	echo := func(w http.ResponseWriter, r *http.Request) {
		jsonReply(w, 500, map[string]any{"error": "boom " + testKey + " " + url.QueryEscape(testKey), "error_code": 25})
	}
	f.routes["GET /user"] = echo
	f.routes["GET /torrents"] = echo
	if err := c.Validate(context.Background(), testKey); err == nil || strings.Contains(err.Error(), testKey) {
		t.Errorf("Validate err = %v", err)
	}
	if _, err := resolve(c, hashA, 1); err == nil || strings.Contains(err.Error(), testKey) {
		t.Errorf("Resolve err = %v", err)
	}
	// transport errors carry the URL: also stripped
	c2 := New("http://127.0.0.1:1/"+testKey, nil)
	c2.MinInterval = 0
	if err := c2.Validate(context.Background(), testKey); err == nil || strings.Contains(err.Error(), testKey) {
		t.Errorf("transport err = %v", err)
	}
}

func TestInvalidResponses(t *testing.T) {
	tests := []struct {
		name   string
		routes map[string]route
	}{
		{"addMagnet without id", map[string]route{"POST /torrents/addMagnet": reply(201, `{}`)}},
		{"addMagnet garbage", map[string]route{"POST /torrents/addMagnet": reply(201, `<html>`)}},
		{"list garbage", map[string]route{"GET /torrents": reply(200, `<html>`)}},
		{"info for another hash", map[string]route{"GET /torrents/info/T1": reply(200, `{"id":"T1","hash":"`+hashB+`","status":"downloaded"}`)}},
		{"info garbage", map[string]route{"GET /torrents/info/T1": reply(200, `nope`)}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, c := newFake(t)
			for k, v := range tt.routes {
				f.routes[k] = v
			}
			if k := "POST /torrents/addMagnet"; tt.routes[k] == nil && tt.name != "list garbage" {
				f.routes[k] = reply(201, `{"id":"T1"}`)
			}
			res, err := resolve(c, hashA, 1)
			if err == nil {
				t.Fatalf("Resolve = %+v, want error", res)
			}
		})
	}
}

func TestResolveConcurrent(t *testing.T) {
	f, c := newFake(t)
	f.addFn = func(_ *fakeRD, tr *torrent) {
		tr.Files = []file{{ID: 1, Path: "/a.mp3", Bytes: 1, Selected: 1}, {ID: 2, Path: "/b.mp3", Bytes: 2, Selected: 1}}
		finish(tr)
	}
	var wg sync.WaitGroup
	for i := range 8 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := resolve(c, hashA, i%2+1); err != nil {
				t.Errorf("Resolve: %v", err)
			}
		}()
	}
	wg.Wait()
}

func TestDefaults(t *testing.T) {
	c := New("", nil)
	if c.base() != DefaultBaseURL || c.HTTP == nil || c.HTTP.Timeout <= 0 || c.MinInterval != DefaultMinInterval {
		t.Errorf("defaults = %+v", c)
	}
	if DefaultMinInterval < 250*time.Millisecond {
		t.Errorf("DefaultMinInterval = %v, want >= 250ms (<= 4 req/s)", DefaultMinInterval)
	}
	if got := New("https://x.example/rest/1.0///", nil).base(); got != "https://x.example/rest/1.0" {
		t.Errorf("base = %q", got)
	}
}

func TestRegisteredByName(t *testing.T) {
	r := debrid.NewRegistry(New("", nil))
	p, ok := r.Get("realdebrid")
	if !ok || p.Name() != "realdebrid" {
		t.Fatalf("Get = %v, %v", p, ok)
	}
	if _, ok := p.(debrid.FileLister); !ok {
		t.Error("provider is not a debrid.FileLister")
	}
}
