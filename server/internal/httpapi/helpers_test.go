package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"

	"github.com/prometheus/client_golang/prometheus"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

const (
	testPublic = "https://audiobooks.example.org"
	testHash   = "0123456789abcdef0123456789abcdef01234567"
	testKey    = "SECRET-TORBOX-KEY-123"
)

type fakeAPI struct {
	mu        sync.Mutex
	search    func(cfg userconfig.Config, q string) ([]model.Book, error)
	discover  func(cfg userconfig.Config, page int) ([]model.Book, error)
	book      func(cfg userconfig.Config, asin string) (*service.BookPage, error)
	streams   func(cfg userconfig.Config, ref service.VideoRef) ([]service.Stream, error)
	resolve   func(cfg userconfig.Config, provider, hash string, idx int) (debrid.ResolveResult, error)
	validate  func(keys map[string]string) map[string]error
	providers []string

	gotCfg      userconfig.Config
	gotQuery    string
	gotPage     int
	gotRef      service.VideoRef
	gotValidate map[string]string
}

func (f *fakeAPI) Search(_ context.Context, cfg userconfig.Config, q string) ([]model.Book, error) {
	f.mu.Lock()
	f.gotCfg, f.gotQuery = cfg, q
	f.mu.Unlock()
	if f.search == nil {
		return nil, nil
	}
	return f.search(cfg, q)
}

func (f *fakeAPI) Discover(_ context.Context, cfg userconfig.Config, page int) ([]model.Book, error) {
	f.mu.Lock()
	f.gotCfg, f.gotPage = cfg, page
	f.mu.Unlock()
	if f.discover == nil {
		return nil, nil
	}
	return f.discover(cfg, page)
}

func (f *fakeAPI) Book(_ context.Context, cfg userconfig.Config, asin string) (*service.BookPage, error) {
	if f.book == nil {
		return nil, service.ErrNotFound
	}
	return f.book(cfg, asin)
}

func (f *fakeAPI) Streams(_ context.Context, cfg userconfig.Config, ref service.VideoRef) ([]service.Stream, error) {
	f.mu.Lock()
	f.gotRef = ref
	f.mu.Unlock()
	if f.streams == nil {
		return nil, nil
	}
	return f.streams(cfg, ref)
}

func (f *fakeAPI) Resolve(_ context.Context, cfg userconfig.Config, p, h string, i int) (debrid.ResolveResult, error) {
	if f.resolve == nil {
		return debrid.ResolveResult{}, debrid.ErrNotFound
	}
	return f.resolve(cfg, p, h, i)
}

func (f *fakeAPI) ValidateKeys(_ context.Context, keys map[string]string) map[string]error {
	f.mu.Lock()
	f.gotValidate = keys
	f.mu.Unlock()
	if f.validate == nil {
		return map[string]error{}
	}
	return f.validate(keys)
}

func (f *fakeAPI) Providers() []string {
	if f.providers == nil {
		return []string{"torbox", "realdebrid"}
	}
	return f.providers
}

type env struct {
	h      http.Handler
	api    *fakeAPI
	sealer *userconfig.Sealer
	logs   *bytes.Buffer
	reg    *prometheus.Registry
}

func newEnv(t *testing.T, password string) *env {
	t.Helper()
	sealer, err := userconfig.NewSealer(strings.Repeat("s3cret", 8))
	if err != nil {
		t.Fatal(err)
	}
	api := &fakeAPI{}
	logs := &bytes.Buffer{}
	reg := prometheus.NewRegistry()
	h := New(Deps{
		API: api, Sealer: sealer, PublicURL: testPublic + "/", InstancePassword: password,
		Logger:  slog.New(slog.NewTextHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug})),
		Version: "1.2.3", Registry: reg,
	})
	return &env{h: h, api: api, sealer: sealer, logs: logs, reg: reg}
}

func (e *env) token(t *testing.T, cfg userconfig.Config) string {
	t.Helper()
	tok, err := e.sealer.Seal(cfg)
	if err != nil {
		t.Fatal(err)
	}
	return tok
}

func (e *env) defaultToken(t *testing.T) string {
	return e.token(t, userconfig.Config{Debrid: map[string]string{"torbox": testKey}, Region: "uk", Languages: []string{"en", "nl"}})
}

func (e *env) do(method, path string, body io.Reader, hdr ...string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, path, body)
	for i := 0; i+1 < len(hdr); i += 2 {
		req.Header.Set(hdr[i], hdr[i+1])
	}
	rec := httptest.NewRecorder()
	e.h.ServeHTTP(rec, req)
	return rec
}

func (e *env) get(path string) *httptest.ResponseRecorder { return e.do(http.MethodGet, path, nil) }

func decode(t *testing.T, rec *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(rec.Body.Bytes(), &m); err != nil {
		t.Fatalf("body is not a JSON object: %v\n%s", err, rec.Body.String())
	}
	return m
}

func mustStatus(t *testing.T, rec *httptest.ResponseRecorder, want int) {
	t.Helper()
	if rec.Code != want {
		t.Fatalf("status = %d, want %d; body: %s", rec.Code, want, rec.Body.String())
	}
}

func sampleBook() model.Book {
	return model.Book{
		ASIN: "B002V0QK4C", Title: "Dune", Authors: []string{"Frank Herbert"}, Narrators: []string{"Scott Brick", "Orlagh Cassidy"},
		Series: "Dune Chronicles", SeriesPosition: "1", RuntimeMin: 1275, CoverURL: "https://img.example/dune.jpg",
		Description: "Desert planet.", ReleaseYear: 2007, Genres: []string{"Sci-Fi"},
	}
}

func doOn(h http.Handler, method, path string) *httptest.ResponseRecorder {
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, httptest.NewRequest(method, path, nil))
	return rec
}
