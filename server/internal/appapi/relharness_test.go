package appapi

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/accounts"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

// This file is the in-memory harness of the release routes (no Postgres): fake provider accounts,
// a fake release store, a fake key store, and a logger whose output the tests search for secrets.

func h40(c byte) string { return strings.Repeat(string(c), 40) }

// ---- key store -----------------------------------------------------------------------------

type memKeys struct {
	mu   sync.Mutex
	recs map[[2]string]userkeys.Record
}

func (m *memKeys) PutKey(_ context.Context, r userkeys.Record) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.recs[[2]string{r.UserID, r.Provider}] = r
	return nil
}
func (m *memKeys) GetKey(_ context.Context, u, p string) (userkeys.Record, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.recs[[2]string{u, p}]
	return r, ok, nil
}
func (m *memKeys) ListKeys(_ context.Context, u string) ([]userkeys.Record, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []userkeys.Record
	for k, r := range m.recs {
		if k[0] == u {
			out = append(out, r)
		}
	}
	return out, nil
}
func (m *memKeys) DeleteKey(_ context.Context, u, p string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.recs, [2]string{u, p})
	return nil
}
func (m *memKeys) SetState(_ context.Context, u, p, state string, at *time.Time, msg string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.recs[[2]string{u, p}]
	if !ok {
		return false, nil
	}
	r.State, r.LastError = state, msg
	m.recs[[2]string{u, p}] = r
	return true, nil
}
func (m *memKeys) state(u, p string) string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.recs[[2]string{u, p}].State
}

// ---- release store ---------------------------------------------------------------------------

type memStore struct {
	mu          sync.Mutex
	releases    map[string]model.Release
	files       map[string][]model.File
	clientFiles map[string][]model.File
	shared      map[string]map[string]service.Availability // provider -> hash
	userAvail   map[string]bool                            // fingerprint|provider|hash
	saveFiles   int
}

func newMemStore() *memStore {
	return &memStore{releases: map[string]model.Release{}, files: map[string][]model.File{}, clientFiles: map[string][]model.File{},
		shared: map[string]map[string]service.Availability{}, userAvail: map[string]bool{}}
}

func (m *memStore) GetRelease(_ context.Context, h string) (*model.Release, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if r, ok := m.releases[h]; ok {
		return &r, nil
	}
	return nil, nil
}
func (m *memStore) InsertReleaseIfAbsent(_ context.Context, r model.Release) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.releases[r.InfoHash]; ok {
		return false, nil
	}
	m.releases[r.InfoHash] = r
	return true, nil
}
func (m *memStore) SaveClientFiles(_ context.Context, h string, fs []model.File) ([]model.File, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if cur, ok := m.clientFiles[h]; ok {
		return cur, false, nil
	}
	m.clientFiles[h] = fs
	return fs, true, nil
}
func (m *memStore) Files(_ context.Context, hs []string) (map[string][]model.File, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := map[string][]model.File{}
	for _, h := range hs {
		if f, ok := m.files[h]; ok {
			out[h] = f
		}
	}
	return out, nil
}
func (m *memStore) SaveFiles(_ context.Context, h string, fs []model.File) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.files[h] = fs
	m.saveFiles++
	return nil
}
func (m *memStore) SetAvailability(_ context.Context, p string, es []service.Availability) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.shared[p] == nil {
		m.shared[p] = map[string]service.Availability{}
	}
	for _, e := range es {
		m.shared[p][e.InfoHash] = e
	}
	return nil
}
func (m *memStore) SetUserAvailability(_ context.Context, fp, p, h string, cached bool, _ time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.userAvail[fp+"|"+p+"|"+h] = cached
	return nil
}
func (m *memStore) userAvailOf(fp, p, h string) (cached, ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	cached, ok = m.userAvail[fp+"|"+p+"|"+h]
	return
}

// ---- providers -------------------------------------------------------------------------------

// provBase is a fake debrid service with one account per API key.
type provBase struct {
	name string

	mu       sync.Mutex
	acct     map[string]map[string]bool // key -> hashes in the account
	adds     int
	resolves []string // "key|hash|index"
	keysSeen map[string]bool
	gate     chan struct{} // when set, Resolve waits for it (coalescing tests)
	validate error         // Validate answer
	// answer decides the Resolve result; added is true when this call added the torrent.
	answer func(key string, req debrid.ResolveRequest, added bool) (debrid.ResolveResult, error)
	files  []model.File
}

func newProvBase(name string) *provBase {
	return &provBase{name: name, acct: map[string]map[string]bool{}, keysSeen: map[string]bool{}}
}

func (p *provBase) Name() string { return p.name }
func (p *provBase) Validate(context.Context, string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.validate
}
func (p *provBase) calls() int    { p.mu.Lock(); defer p.mu.Unlock(); return len(p.resolves) }
func (p *provBase) addCount() int { p.mu.Lock(); defer p.mu.Unlock(); return p.adds }
func (p *provBase) CheckCached(context.Context, string, []string) (map[string]debrid.Cached, error) {
	return nil, nil
}

func (p *provBase) Resolve(_ context.Context, key string, req debrid.ResolveRequest) (debrid.ResolveResult, error) {
	p.mu.Lock()
	if p.gate != nil {
		g := p.gate
		p.mu.Unlock()
		<-g
		p.mu.Lock()
	}
	defer p.mu.Unlock()
	p.keysSeen[key] = true
	p.resolves = append(p.resolves, fmt.Sprintf("%s|%s|%d", key, req.InfoHash, req.FileIndex))
	if p.acct[key] == nil {
		p.acct[key] = map[string]bool{}
	}
	added := !p.acct[key][req.InfoHash]
	if added {
		p.acct[key][req.InfoHash] = true
		p.adds++
	}
	if p.answer != nil {
		return p.answer(key, req, added)
	}
	return debrid.ResolveResult{URL: linkFor(p.name, key, req.FileIndex)}, nil
}

// linkFor builds a link on an allowed host; the token part must never appear in a log or a body.
func linkFor(provider, key string, idx int) string {
	if provider == "torbox" {
		return fmt.Sprintf("https://nexus-12.weur.tb-cdn.st/dl/LINKTOKEN-%s-%d?token=SIGNED", key, idx)
	}
	return fmt.Sprintf("https://22-4.download.real-debrid.com/d/LINKTOKEN-%s-%d", key, idx)
}

// rdFake lists files and deletes like Real-Debrid.
type rdFake struct {
	*provBase
	deleted []string
}

func (p *rdFake) Files(_ context.Context, key, hash string) ([]model.File, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if !p.acct[key][hash] {
		return nil, fmt.Errorf("fake: %w", debrid.ErrNotFound)
	}
	return p.files, nil
}
func (p *rdFake) Delete(_ context.Context, key, hash string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	delete(p.acct[key], hash)
	p.deleted = append(p.deleted, hash)
	return nil
}

// tbFake has a shared cache check and no file lister (like TorBox).
type tbFake struct {
	*provBase
	cached map[string]debrid.Cached
	err    error
	checks int
}

func (p *tbFake) CheckCached(_ context.Context, key string, hs []string) (map[string]debrid.Cached, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.checks++
	p.keysSeen[key] = true
	if p.err != nil {
		return nil, p.err
	}
	out := map[string]debrid.Cached{}
	for _, h := range hs {
		if c, ok := p.cached[h]; ok {
			out[h] = c
		}
	}
	return out, nil
}

// ---- the harness --------------------------------------------------------------------------------

type syncBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuf) Write(p []byte) (int, error) { s.mu.Lock(); defer s.mu.Unlock(); return s.b.Write(p) }
func (s *syncBuf) String() string              { s.mu.Lock(); defer s.mu.Unlock(); return s.b.String() }

type accStore struct {
	accounts.Store
	user accounts.User
}

func (a accStore) GetUser(context.Context, string) (accounts.User, error) { return a.user, nil }

type relEnv struct {
	t      *testing.T
	app    *app
	clk    *clock
	store  *memStore
	keys   *memKeys
	ksvc   *userkeys.Service
	rd     *rdFake
	tb     *tbFake
	logs   *syncBuf
	bodies []string // every response body and header value seen (for the secret scan)
	secret []string // strings that must never show up in a log or a body
}

func newRelEnv(t *testing.T, addsPerHour int) *relEnv {
	t.Helper()
	e := &relEnv{t: t, clk: &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}, store: newMemStore(), keys: &memKeys{recs: map[[2]string]userkeys.Record{}}, logs: &syncBuf{}}
	e.rd = &rdFake{provBase: newProvBase("realdebrid")}
	e.rd.files = []model.File{{Index: 2, Path: "Book/02 b.mp3", Size: 20}, {Index: 1, Path: "Book/01 a.mp3", Size: 10}, {Index: 3, Path: "Book/cover.jpg", Size: 1}}
	e.tb = &tbFake{provBase: newProvBase("torbox"), cached: map[string]debrid.Cached{}}
	log := slog.New(slog.NewTextHandler(e.logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	provs := debrid.NewRegistry(e.rd, e.tb)
	ksvc, err := userkeys.New(userkeys.Config{Secret: secret, Store: e.keys, Providers: provs, Logger: log, Now: e.clk.Now})
	if err != nil {
		t.Fatal(err)
	}
	e.ksvc = ksvc
	acc, err := accounts.New(accounts.Config{Secret: secret, InstancePassword: "pw", Store: accStore{user: accounts.User{ID: "u1", Settings: accounts.DefaultSettings()}}, Now: e.clk.Now})
	if err != nil {
		t.Fatal(err)
	}
	api := &API{acc: acc, key: ksvc, log: log, mux: http.NewServeMux()}
	e.app = &app{
		API: api, d: AppDeps{Store: e.store, Providers: provs, AddsPerHour: addsPerHour}, log: log, now: e.clk.Now,
		allow: NewLinkAllowlist(nil), links: newLinkCache(e.clk.Now), trk: newTracker(e.clk.Now), bud: newBudget(addsPerHour, e.clk.Now),
		appDL: make(chan struct{}, maxAppDownloads),
	}
	t.Cleanup(e.scan)
	return e
}

// setKey stores a working key for a user and remembers it as a secret.
func (e *relEnv) setKey(user, provider, apiKey string) {
	e.t.Helper()
	if _, err := e.ksvc.Put(context.Background(), user, provider, apiKey); err != nil {
		e.t.Fatal(err)
	}
	e.secret = append(e.secret, apiKey)
}

func (e *relEnv) fp(user, provider string) string {
	k, err := e.ksvc.GetForUse(context.Background(), user, provider)
	if err != nil {
		e.t.Fatal(err)
	}
	return k.Fingerprint
}

func (e *relEnv) addRelease(h string) {
	e.store.releases[h] = model.Release{InfoHash: h, Title: "T", Magnet: "magnet:?xt=urn:btih:" + h}
}

type reply struct{ *httptest.ResponseRecorder }

func (r reply) status(t *testing.T, want int) {
	t.Helper()
	if r.Code != want {
		t.Fatalf("status = %d, want %d; body %s", r.Code, want, r.Body.String())
	}
}
func (r reply) json(t *testing.T) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(r.Body.Bytes(), &m); err != nil {
		t.Fatalf("body %q: %v", r.Body.String(), err)
	}
	return m
}
func (r reply) errCode(t *testing.T, status int, code string) {
	t.Helper()
	r.status(t, status)
	if got, _ := r.json(t)["error"].(map[string]any)["code"].(string); got != code {
		t.Fatalf("error code = %q, want %q (%s)", got, code, r.Body.String())
	}
}

// call runs a handler as the user, with the mux's path values set by hand.
func (e *relEnv) call(h http.HandlerFunc, user, method, target string, body any, path map[string]string) reply {
	e.t.Helper()
	var rd io.Reader
	switch b := body.(type) {
	case nil:
	case string:
		rd = strings.NewReader(b)
	default:
		raw, _ := json.Marshal(b)
		rd = bytes.NewReader(raw)
	}
	req := httptest.NewRequest(method, target, rd)
	for k, v := range path {
		req.SetPathValue(k, v)
	}
	req = req.WithContext(accounts.WithPrincipal(req.Context(), accounts.Principal{UserID: user, DeviceID: "d", Role: accounts.RoleUser}))
	rec := httptest.NewRecorder()
	rec.Header().Set("Cache-Control", "no-store") // what API.ServeHTTP does for every /v1 response
	h(rec, req)
	if loc := rec.Header().Get("Location"); loc == "" {
		e.bodies = append(e.bodies, rec.Body.String())
	}
	return reply{rec}
}

func (e *relEnv) prepare(user, hash, provider string, extra map[string]any) reply {
	body := map[string]any{"provider": provider}
	for k, v := range extra {
		body[k] = v
	}
	return e.call(e.app.prepareRoute, user, "POST", "/v1/releases/"+hash+"/prepare", body, map[string]string{"hash": hash})
}

func (e *relEnv) download(user, method, hash string, idx int, provider string) reply {
	t := fmt.Sprintf("/v1/releases/%s/files/%d/download?provider=%s", hash, idx, provider)
	return e.call(e.app.download, user, method, t, nil, map[string]string{"hash": hash, "index": fmt.Sprint(idx)})
}

// scan fails the test when a key or a provider link token shows up in the logs or a response body.
func (e *relEnv) scan() {
	hay := e.logs.String() + "\n" + strings.Join(e.bodies, "\n")
	for _, s := range append(append([]string{}, e.secret...), "LINKTOKEN", "SIGNED") {
		if strings.Contains(hay, s) {
			e.t.Errorf("secret %q leaked into a log line or a response body:\n%s", s, hay)
		}
	}
}
