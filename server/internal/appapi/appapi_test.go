package appapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/accounts"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/googleid/googleidtest"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/store"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/testpg"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

const secret = "0123456789abcdef0123456789abcdef"

type fakeProvider struct {
	mu  sync.Mutex
	err error
}

func (p *fakeProvider) Name() string { return "torbox" }
func (p *fakeProvider) Validate(context.Context, string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.err
}
func (p *fakeProvider) set(err error) { p.mu.Lock(); p.err = err; p.mu.Unlock() }
func (p *fakeProvider) CheckCached(context.Context, string, []string) (map[string]debrid.Cached, error) {
	return nil, nil
}
func (p *fakeProvider) Resolve(context.Context, string, debrid.ResolveRequest) (debrid.ResolveResult, error) {
	return debrid.ResolveResult{}, nil
}

type env struct {
	t    *testing.T
	api  *API
	prov *fakeProvider
	keys *userkeys.Service
	clk  *clock
	g    *googleidtest.Google // nil unless the env was built with Google on
}

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *clock) Now() time.Time { c.mu.Lock(); defer c.mu.Unlock(); return c.t }
func (c *clock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

func newEnv(t *testing.T) *env { return newEnvOpt(t, false) }

// newEnvOpt builds the API on a fresh schema; google switches Sign in with Google on (real
// verifier against a fake JWKS; env.g is the fake Google).
func newEnvOpt(t *testing.T, google bool) *env {
	t.Helper()
	db, err := store.Open(context.Background(), testpg.URL(t))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	clk := &clock{t: time.Now().UTC()}
	accCfg := accounts.Config{Secret: secret, InstancePassword: "pw", Store: db, Now: clk.Now}
	var g *googleidtest.Google
	if google {
		g = googleidtest.New(t, secret, clk.Now)
		accCfg.Google = g.V
	}
	acc, err := accounts.New(accCfg)
	if err != nil {
		t.Fatal(err)
	}
	prov := &fakeProvider{}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	keys, err := userkeys.New(userkeys.Config{Secret: secret, Store: db, Providers: debrid.NewRegistry(prov), Logger: log})
	if err != nil {
		t.Fatal(err)
	}
	return &env{t: t, api: New(Deps{Accounts: acc, Keys: keys, Version: "9.9.9", Logger: log}), prov: prov, keys: keys, clk: clk, g: g}
}

type resp struct {
	*httptest.ResponseRecorder
}

func (e *env) do(method, path, token string, body any) resp {
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
	req := httptest.NewRequest(method, path, rd)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	rec := httptest.NewRecorder()
	e.api.ServeHTTP(rec, req)
	if got := rec.Header().Get("Cache-Control"); got != "no-store" {
		e.t.Errorf("%s %s: Cache-Control = %q", method, path, got)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		e.t.Errorf("%s %s: sends Access-Control-Allow-Origin %q", method, path, got)
	}
	return resp{rec}
}

func (r resp) json(t *testing.T) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal(r.Body.Bytes(), &m); err != nil {
		t.Fatalf("body is not JSON: %v: %s", err, r.Body.String())
	}
	return m
}

func (r resp) status(t *testing.T, want int) {
	t.Helper()
	if r.Code != want {
		t.Fatalf("status = %d, want %d; body %s", r.Code, want, r.Body.String())
	}
}

// errCode checks the contract's error body and returns the code.
func (r resp) errCode(t *testing.T, status int, code string) {
	t.Helper()
	r.status(t, status)
	m := r.json(t)
	inner, ok := m["error"].(map[string]any)
	if !ok || inner["code"] != code || len(m) != 1 {
		t.Fatalf("error body = %s, want code %q", r.Body.String(), code)
	}
}

type session struct{ access, refresh, userID string }

func (e *env) session(r resp) session {
	e.t.Helper()
	r.status(e.t, 200)
	m := r.json(e.t)
	u := m["user"].(map[string]any)
	return session{access: m["access_token"].(string), refresh: m["refresh_token"].(string), userID: u["id"].(string)}
}

func (e *env) bootstrap() session {
	return e.session(e.do("POST", "/v1/auth/bootstrap", "", map[string]any{"instance_password": "pw", "display_name": "Admin", "device_name": "Admin phone"}))
}

func (e *env) invite(admin session) string {
	e.t.Helper()
	r := e.do("POST", "/v1/admin/invites", admin.access, map[string]any{"note": "n"})
	r.status(e.t, 200)
	return r.json(e.t)["code"].(string)
}

func (e *env) join(admin session, name string) session {
	return e.session(e.do("POST", "/v1/auth/join", "", map[string]any{"invite_code": e.invite(admin), "display_name": name, "device_name": name + " phone"}))
}

func TestCodeFormatAndTypedCodes(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	shape := regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){5}-[0-9A-HJKMNP-TV-Z]{2}$`) // 26 characters, a dash every 4
	// Every spelling a person may type or paste joins: lower case, no dashes, spaces, line breaks.
	spell := []func(string) string{
		strings.ToLower,
		func(c string) string { return strings.ReplaceAll(c, "-", "") },
		func(c string) string { return strings.ReplaceAll(c, "-", " ") },
		func(c string) string { return "  " + strings.ToLower(strings.ReplaceAll(c, "-", "\t")) + "\r\n" },
		func(c string) string { return strings.ReplaceAll(c, "-", "- ") },
	}
	for i, f := range spell {
		code := e.invite(admin)
		if !shape.MatchString(code) {
			t.Fatalf("invite code %q is not 26 base32 characters with a dash every 4", code)
		}
		e.do("POST", "/v1/auth/join", "", map[string]any{"invite_code": f(code), "display_name": "U", "device_name": "p"}).status(t, 200)
		if i == 0 { // single use, whatever the spelling
			e.do("POST", "/v1/auth/join", "", map[string]any{"invite_code": code, "display_name": "U", "device_name": "p"}).errCode(t, 401, "unauthorized")
		}
	}
	for _, f := range spell {
		lc := e.do("POST", "/v1/me/devices/link-code", admin.access, nil)
		lc.status(t, 200)
		code := lc.json(t)["link_code"].(string)
		if !shape.MatchString(code) {
			t.Fatalf("link code %q has the wrong shape", code)
		}
		e.do("POST", "/v1/auth/link", "", map[string]any{"link_code": f(code), "device_name": "d"}).status(t, 200)
	}
}

func TestSignOutOfTheCurrentDevice(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	phone := e.session(e.do("POST", "/v1/auth/link", "", map[string]any{"link_code": e.do("POST", "/v1/me/devices/link-code", admin.access, nil).json(t)["link_code"], "device_name": "Phone"}))
	var current string
	for _, d := range e.do("GET", "/v1/me/devices", phone.access, nil).json(t)["devices"].([]any) {
		if d := d.(map[string]any); d["current"] == true {
			current = d["id"].(string)
		}
	}
	if current == "" {
		t.Fatal("no current device")
	}
	// A device may sign itself out: 204, and its tokens stop working at once.
	e.do("DELETE", "/v1/me/devices/"+current, phone.access, nil).status(t, 204)
	e.do("GET", "/v1/me", phone.access, nil).errCode(t, 401, "unauthorized")
	e.do("POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": phone.refresh}).errCode(t, 401, "unauthorized")
	// Repeating it with the same (now dead) token is 401: a client treats 204 and 401 as "signed out".
	e.do("DELETE", "/v1/me/devices/"+current, phone.access, nil).errCode(t, 401, "unauthorized")
	// The user's other devices are unaffected.
	e.do("GET", "/v1/me", admin.access, nil).status(t, 200)
}

func TestVersionAndBootstrap(t *testing.T) {
	e := newEnv(t)
	m := e.do("GET", "/v1/version", "", nil).json(t)
	if m["version"] != "9.9.9" || m["api_version"] != float64(1) || m["bootstrapped"] != false || m["google_enabled"] != false {
		t.Fatalf("version = %v", m)
	}

	bad := []struct {
		name string
		body any
		code int
		err  string
	}{
		{"wrong password", map[string]any{"instance_password": "nope", "display_name": "A", "device_name": "d"}, 401, "unauthorized"},
		{"no display name", map[string]any{"instance_password": "pw", "device_name": "d"}, 400, "invalid_request"},
		{"long device", map[string]any{"instance_password": "pw", "display_name": "A", "device_name": strings.Repeat("d", 81)}, 400, "invalid_request"},
		{"not json", "{", 400, "invalid_request"},
		{"trailing data", `{"instance_password":"pw","display_name":"A","device_name":"d"}{}`, 400, "invalid_request"},
		{"wrong type", `{"instance_password":1}`, 400, "invalid_request"},
	}
	for _, tc := range bad {
		t.Run(tc.name, func(t *testing.T) { e.do("POST", "/v1/auth/bootstrap", "", tc.body).errCode(t, tc.code, tc.err) })
	}
	if e.do("GET", "/v1/version", "", nil).json(t)["bootstrapped"] != false {
		t.Fatal("rejected bootstraps created a user")
	}

	r := e.do("POST", "/v1/auth/bootstrap", "", map[string]any{"instance_password": "pw", "display_name": "Admin", "device_name": "Pixel"})
	r.status(t, 200)
	m = r.json(t)
	user := m["user"].(map[string]any)
	settings := user["settings"].(map[string]any)
	if m["token_type"] != "Bearer" || m["expires_in"] != float64(3600) || m["access_token"] == "" || m["refresh_token"] == "" ||
		user["role"] != "admin" || user["display_name"] != "Admin" || user["id"] == "" ||
		settings["region"] != "us" || settings["source_mode"] != "auto" || settings["p2p_enabled"] != false ||
		settings["prefer_format"] != "" || settings["preferred_provider"] != nil || len(settings["languages"].([]any)) != 1 {
		t.Fatalf("session = %s", r.Body.String())
	}
	if e.do("GET", "/v1/version", "", nil).json(t)["bootstrapped"] != true {
		t.Fatal("not bootstrapped")
	}
	e.do("POST", "/v1/auth/bootstrap", "", map[string]any{"instance_password": "pw", "display_name": "Two", "device_name": "d"}).errCode(t, 409, "conflict")
}

func TestBootstrapRateLimit(t *testing.T) {
	e := newEnv(t)
	body := map[string]any{"instance_password": "nope", "display_name": "A", "device_name": "d"}
	for i := 0; i < 5; i++ {
		e.do("POST", "/v1/auth/bootstrap", "", body).errCode(t, 401, "unauthorized")
	}
	r := e.do("POST", "/v1/auth/bootstrap", "", body)
	r.errCode(t, 429, "rate_limited")
	if r.Header().Get("Retry-After") == "" || r.json(t)["error"].(map[string]any)["retry_after_s"] == nil {
		t.Fatalf("429 without Retry-After: %v %s", r.Header(), r.Body.String())
	}
	// the correct password is also refused while limited
	e.do("POST", "/v1/auth/bootstrap", "", map[string]any{"instance_password": "pw", "display_name": "A", "device_name": "d"}).errCode(t, 429, "rate_limited")
	e.clk.Advance(time.Minute + time.Second)
	e.do("POST", "/v1/auth/bootstrap", "", map[string]any{"instance_password": "pw", "display_name": "A", "device_name": "d"}).status(t, 200)
}

func TestAuthRequiredEverywhere(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	routes := []struct{ method, path string }{
		{"GET", "/v1/me"}, {"PATCH", "/v1/me"}, {"GET", "/v1/me/devices"}, {"POST", "/v1/me/devices/link-code"},
		{"DELETE", "/v1/me/devices/00000000-0000-4000-8000-000000000000"}, {"GET", "/v1/me/debrid"},
		{"PUT", "/v1/me/debrid/torbox"}, {"POST", "/v1/me/debrid/torbox"}, {"DELETE", "/v1/me/debrid/torbox"},
		{"GET", "/v1/admin/users"}, {"PATCH", "/v1/admin/users/x"}, {"GET", "/v1/admin/invites"}, {"POST", "/v1/admin/invites"},
		{"DELETE", "/v1/admin/invites/x"}, {"POST", "/v1/me/google"}, {"DELETE", "/v1/me/google"},
	}
	for _, rt := range routes {
		t.Run(rt.method+" "+rt.path, func(t *testing.T) {
			r := e.do(rt.method, rt.path, "", nil)
			r.status(t, 401)
			if got := strings.TrimSpace(r.Body.String()); got != `{"error":{"code":"unauthorized"}}` {
				t.Fatalf("body = %s", got)
			}
			e.do(rt.method, rt.path, "garbage", nil).status(t, 401)
		})
	}
	_ = admin
	e.do("GET", "/v1/nope", admin.access, nil).errCode(t, 404, "not_found")
	e.do("GET", "/v1/nope", "", nil).errCode(t, 404, "not_found")
}

func TestAdminRoutesAreAdminOnly(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	bob := e.join(admin, "Bob")
	for _, rt := range []struct {
		method, path string
		body         any
	}{
		{"GET", "/v1/admin/users", nil},
		{"PATCH", "/v1/admin/users/" + admin.userID, map[string]any{"disabled": true}},
		{"GET", "/v1/admin/invites", nil},
		{"POST", "/v1/admin/invites", map[string]any{}},
		{"DELETE", "/v1/admin/invites/00000000-0000-4000-8000-000000000000", nil},
	} {
		t.Run(rt.method+" "+rt.path, func(t *testing.T) {
			e.do(rt.method, rt.path, bob.access, rt.body).errCode(t, 403, "forbidden")
		})
	}
	// and the admin was not disabled by Bob's attempt
	e.do("GET", "/v1/me", admin.access, nil).status(t, 200)
}

func TestJoinLinkRefreshFlow(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()

	inv := e.do("POST", "/v1/admin/invites", admin.access, map[string]any{"note": "for Bob", "expires_in_hours": 24})
	inv.status(t, 200)
	im := inv.json(t)
	code := im["code"].(string)
	if im["id"] == "" || im["note"] != "for Bob" || code == "" {
		t.Fatalf("invite = %v", im)
	}
	list := e.do("GET", "/v1/admin/invites", admin.access, nil).json(t)["invites"].([]any)
	if len(list) != 1 || list[0].(map[string]any)["id"] != im["id"] || list[0].(map[string]any)["code"] != nil {
		t.Fatalf("invites = %v", list)
	}

	e.do("POST", "/v1/auth/join", "", map[string]any{"invite_code": "WRONG-CODE", "display_name": "Bob", "device_name": "p"}).errCode(t, 401, "unauthorized")
	join := map[string]any{"invite_code": strings.ToLower(code), "display_name": "Bob", "device_name": "Pixel"}
	bob := e.session(e.do("POST", "/v1/auth/join", "", join))
	e.do("POST", "/v1/auth/join", "", join).errCode(t, 401, "unauthorized")
	if n := len(e.do("GET", "/v1/admin/invites", admin.access, nil).json(t)["invites"].([]any)); n != 0 {
		t.Fatalf("%d open invites after use", n)
	}

	me := e.do("GET", "/v1/me", bob.access, nil).json(t)
	if me["role"] != "user" || me["display_name"] != "Bob" || me["id"] != bob.userID {
		t.Fatalf("me = %v", me)
	}

	// link a second device
	lc := e.do("POST", "/v1/me/devices/link-code", bob.access, nil)
	lc.status(t, 200)
	lcm := lc.json(t)
	if _, err := time.Parse(time.RFC3339, lcm["expires_at"].(string)); err != nil {
		t.Fatalf("expires_at: %v", err)
	}
	e.do("POST", "/v1/auth/link", "", map[string]any{"link_code": "nope", "device_name": "Tab"}).errCode(t, 401, "unauthorized")
	tab := e.session(e.do("POST", "/v1/auth/link", "", map[string]any{"link_code": lcm["link_code"], "device_name": "Tablet"}))
	if tab.userID != bob.userID {
		t.Fatal("linked device belongs to someone else")
	}
	e.do("POST", "/v1/auth/link", "", map[string]any{"link_code": lcm["link_code"], "device_name": "Again"}).errCode(t, 401, "unauthorized")

	devs := e.do("GET", "/v1/me/devices", bob.access, nil).json(t)["devices"].([]any)
	if len(devs) != 2 {
		t.Fatalf("devices = %v", devs)
	}
	current := 0
	var tabID string
	for _, d := range devs {
		dm := d.(map[string]any)
		if dm["current"] == true {
			current++
		}
		if dm["name"] == "Tablet" {
			tabID = dm["id"].(string)
		}
	}
	if current != 1 || tabID == "" {
		t.Fatalf("devices = %v", devs)
	}

	// refresh rotates
	r1 := e.session(e.do("POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": tab.refresh}))
	e.do("POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": tab.refresh}).errCode(t, 401, "unauthorized")
	e.do("POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": "x"}).errCode(t, 401, "unauthorized")
	e.do("GET", "/v1/me", r1.access, nil).status(t, 200)

	// revoke the tablet from the phone: its tokens stop at once; revoking again is a 204
	e.do("DELETE", "/v1/me/devices/"+tabID, bob.access, nil).status(t, 204)
	e.do("DELETE", "/v1/me/devices/"+tabID, bob.access, nil).status(t, 204)
	e.do("GET", "/v1/me", r1.access, nil).errCode(t, 401, "unauthorized")
	e.do("POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": r1.refresh}).errCode(t, 401, "unauthorized")
	e.do("DELETE", "/v1/me/devices/not-a-uuid", bob.access, nil).errCode(t, 404, "not_found")
	// the admin cannot be touched through Bob's device routes and vice versa
	e.do("DELETE", "/v1/me/devices/"+tabID, admin.access, nil).errCode(t, 404, "not_found")

	// invite revocation
	inv2 := e.do("POST", "/v1/admin/invites", admin.access, nil)
	inv2.status(t, 200)
	e.do("DELETE", "/v1/admin/invites/"+inv2.json(t)["id"].(string), admin.access, nil).status(t, 204)
	e.do("POST", "/v1/auth/join", "", map[string]any{"invite_code": inv2.json(t)["code"], "display_name": "X", "device_name": "p"}).errCode(t, 401, "unauthorized")

	// invite validation
	e.do("POST", "/v1/admin/invites", admin.access, map[string]any{"expires_in_hours": 0}).errCode(t, 400, "invalid_request")
	e.do("POST", "/v1/admin/invites", admin.access, map[string]any{"expires_in_hours": 721}).errCode(t, 400, "invalid_request")
	e.do("POST", "/v1/admin/invites", admin.access, map[string]any{"note": strings.Repeat("n", 81)}).errCode(t, 400, "invalid_request")
}

func TestInviteCreationIsRateLimited(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	for i := 0; i < 10; i++ {
		e.do("POST", "/v1/admin/invites", admin.access, nil).status(t, 200)
	}
	e.do("POST", "/v1/admin/invites", admin.access, nil).errCode(t, 429, "rate_limited")
}

func TestMeSettings(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()

	r := e.do("PATCH", "/v1/me", admin.access, map[string]any{
		"display_name": "Boss",
		"settings": map[string]any{
			"region": "nl", "languages": []string{"nl", "en"}, "prefer_format": "mp3", "preferred_provider": "torbox",
			"source_mode": "p2p", "p2p_enabled": true,
		},
	})
	r.status(t, 200)
	m := r.json(t)
	s := m["settings"].(map[string]any)
	if m["display_name"] != "Boss" || s["region"] != "nl" || s["prefer_format"] != "mp3" || s["preferred_provider"] != "torbox" ||
		s["source_mode"] != "p2p" || s["p2p_enabled"] != true || s["languages"].([]any)[0] != "nl" {
		t.Fatalf("me = %s", r.Body.String())
	}
	// partial update: absent fields stay; null clears the provider
	r = e.do("PATCH", "/v1/me", admin.access, `{"settings":{"p2p_enabled":false,"preferred_provider":null}}`)
	r.status(t, 200)
	s = r.json(t)["settings"].(map[string]any)
	if s["p2p_enabled"] != false || s["preferred_provider"] != nil || s["region"] != "nl" || s["source_mode"] != "p2p" {
		t.Fatalf("settings = %v", s)
	}
	// an empty PATCH is allowed and changes nothing
	e.do("PATCH", "/v1/me", admin.access, map[string]any{}).status(t, 200)

	for name, body := range map[string]any{
		"bad region":        map[string]any{"settings": map[string]any{"region": "zz"}},
		"bad language":      map[string]any{"settings": map[string]any{"languages": []string{"fr"}}},
		"empty languages":   map[string]any{"settings": map[string]any{"languages": []string{}}},
		"bad format":        map[string]any{"settings": map[string]any{"prefer_format": "ogg"}},
		"bad provider":      map[string]any{"settings": map[string]any{"preferred_provider": "alldebrid"}},
		"bad mode":          map[string]any{"settings": map[string]any{"source_mode": "magic"}},
		"p2p wrong type":    `{"settings":{"p2p_enabled":"yes"}}`,
		"empty name":        map[string]any{"display_name": "  "},
		"long name":         map[string]any{"display_name": strings.Repeat("n", 41)},
		"valid part + bad":  map[string]any{"display_name": "Ghost", "settings": map[string]any{"region": "zz"}},
		"languages as text": `{"settings":{"languages":"en"}}`,
	} {
		t.Run(name, func(t *testing.T) {
			e.do("PATCH", "/v1/me", admin.access, body).errCode(t, 400, "invalid_request")
		})
	}
	if got := e.do("GET", "/v1/me", admin.access, nil).json(t)["display_name"]; got != "Boss" {
		t.Fatalf("a rejected patch changed the name to %v", got)
	}
}

func TestDebridKeys(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	bob := e.join(admin, "Bob")
	const key = "tb-very-secret-key-123456"
	has := func(r resp, s string) bool { return strings.Contains(r.Body.String(), s) }

	list := e.do("GET", "/v1/me/debrid", bob.access, nil)
	list.status(t, 200)
	provs := list.json(t)["providers"].([]any)
	if len(provs) != 1 || provs[0].(map[string]any)["provider"] != "torbox" || provs[0].(map[string]any)["configured"] != false {
		t.Fatalf("providers = %s", list.Body.String())
	}

	e.do("PUT", "/v1/me/debrid/torbox", bob.access, map[string]any{"api_key": "short"}).errCode(t, 400, "invalid_request")
	e.do("PUT", "/v1/me/debrid/alldebrid", bob.access, map[string]any{"api_key": key}).errCode(t, 400, "invalid_request")
	e.do("PUT", "/v1/me/debrid/torbox", bob.access, `{"api_key":5}`).errCode(t, 400, "invalid_request")

	e.prov.set(debrid.ErrBadKey)
	r := e.do("PUT", "/v1/me/debrid/torbox", bob.access, map[string]any{"api_key": key})
	r.errCode(t, 400, "bad_debrid_key")
	if has(r, key) {
		t.Fatal("error echoes the key")
	}
	e.prov.set(errors.New("dial tcp 1.2.3.4: i/o timeout"))
	r = e.do("PUT", "/v1/me/debrid/torbox", bob.access, map[string]any{"api_key": key})
	r.errCode(t, 502, "debrid_unavailable")
	if has(r, "1.2.3.4") || has(r, key) {
		t.Fatalf("502 leaks details: %s", r.Body.String())
	}
	if p := e.do("GET", "/v1/me/debrid", bob.access, nil).json(t)["providers"].([]any)[0].(map[string]any); p["configured"] != false {
		t.Fatalf("a rejected key was stored: %v", p)
	}

	e.prov.set(nil)
	r = e.do("PUT", "/v1/me/debrid/torbox", bob.access, map[string]any{"api_key": key})
	r.status(t, 200)
	st := r.json(t)
	if st["provider"] != "torbox" || st["configured"] != true || st["status"] != "ok" || st["validated_at"] == nil || st["last_error"] != nil || st["shared_with_others"] != false {
		t.Fatalf("status = %s", r.Body.String())
	}
	if has(r, key) {
		t.Fatal("PUT response contains the key")
	}
	for _, rt := range []struct{ method, path, tok string }{
		{"GET", "/v1/me/debrid", bob.access}, {"GET", "/v1/me", bob.access}, {"GET", "/v1/admin/users", admin.access},
	} {
		if has(e.do(rt.method, rt.path, rt.tok, nil), key) {
			t.Fatalf("%s %s returns the key", rt.method, rt.path)
		}
	}

	// Alice (admin) has no key; the admin list shows who does
	users := e.do("GET", "/v1/admin/users", admin.access, nil).json(t)["users"].([]any)
	for _, u := range users {
		um := u.(map[string]any)
		want := um["id"] == bob.userID
		if um["debrid"].(map[string]any)["torbox"] != want {
			t.Fatalf("admin list debrid = %v for %v", um["debrid"], um["display_name"])
		}
	}
	e.do("POST", "/v1/me/debrid/torbox", admin.access, nil).errCode(t, 404, "not_found")

	// recheck
	e.prov.set(debrid.ErrBadKey)
	st = e.do("POST", "/v1/me/debrid/torbox", bob.access, nil).json(t)
	if st["status"] != "invalid" || st["last_error"] == nil {
		t.Fatalf("recheck = %v", st)
	}
	e.prov.set(nil)
	if st = e.do("POST", "/v1/me/debrid/torbox", bob.access, nil).json(t); st["status"] != "ok" || st["last_error"] != nil {
		t.Fatalf("recheck = %v", st)
	}

	// the server side reads exactly the caller's key
	k, err := e.keys.GetForUse(context.Background(), bob.userID, "torbox")
	if err != nil || k.APIKey() != key {
		t.Fatalf("GetForUse(bob): %v", err)
	}
	if _, err := e.keys.GetForUse(context.Background(), admin.userID, "torbox"); !errors.Is(err, userkeys.ErrNoKey) {
		t.Fatalf("GetForUse(admin) = %v", err)
	}

	e.do("DELETE", "/v1/me/debrid/torbox", admin.access, nil).status(t, 204) // not Bob's key
	e.do("GET", "/v1/me/debrid", bob.access, nil).json(t)                    // still there
	if p := e.do("GET", "/v1/me/debrid", bob.access, nil).json(t)["providers"].([]any)[0].(map[string]any); p["configured"] != true {
		t.Fatal("the admin's DELETE removed Bob's key")
	}
	e.do("DELETE", "/v1/me/debrid/torbox", bob.access, nil).status(t, 204)
	e.do("DELETE", "/v1/me/debrid/torbox", bob.access, nil).status(t, 204)
	e.do("DELETE", "/v1/me/debrid/alldebrid", bob.access, nil).errCode(t, 400, "invalid_request")
	if p := e.do("GET", "/v1/me/debrid", bob.access, nil).json(t)["providers"].([]any)[0].(map[string]any); p["configured"] != false {
		t.Fatal("key not deleted")
	}
}

func TestDebridKeyHelpers(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	var gotUser string
	var gotKey userkeys.Key
	var gotErr error
	e.api.Extend(func(r Router) {
		r.Mux.Handle("GET /v1/test/key", r.Auth(http.HandlerFunc(func(w http.ResponseWriter, req *http.Request) {
			p, _ := PrincipalFrom(req)
			gotUser = p.UserID
			gotKey, gotErr = e.api.DebridKey(req, "torbox")
			if e.api.KeyError(w, gotErr) {
				return
			}
			WriteJSON(w, 200, map[string]any{"ok": true})
		})))
		r.Mux.Handle("GET /v1/test/admin", r.Admin(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { WriteJSON(w, 200, map[string]any{}) })))
	})
	e.do("GET", "/v1/test/key", "", nil).errCode(t, 401, "unauthorized")
	e.do("GET", "/v1/test/key", admin.access, nil).errCode(t, 403, "no_debrid_key")
	if gotUser != admin.userID {
		t.Fatalf("principal = %q", gotUser)
	}
	e.do("PUT", "/v1/me/debrid/torbox", admin.access, map[string]any{"api_key": "admin-key-12345678"}).status(t, 200)
	e.do("GET", "/v1/test/key", admin.access, nil).status(t, 200)
	if gotKey.APIKey() != "admin-key-12345678" {
		t.Fatal("DebridKey did not return the caller's key")
	}
	if err := e.keys.MarkInvalid(context.Background(), admin.userID, "torbox"); err != nil {
		t.Fatal(err)
	}
	e.do("GET", "/v1/test/key", admin.access, nil).errCode(t, 403, "bad_debrid_key")
	bob := e.join(admin, "Bob")
	e.do("GET", "/v1/test/key", bob.access, nil).errCode(t, 403, "no_debrid_key")
	e.do("GET", "/v1/test/admin", bob.access, nil).errCode(t, 403, "forbidden")
	e.do("GET", "/v1/test/admin", admin.access, nil).status(t, 200)
}

func TestAdminUsers(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	bob := e.join(admin, "Bob")

	users := e.do("GET", "/v1/admin/users", admin.access, nil).json(t)["users"].([]any)
	if len(users) != 2 {
		t.Fatalf("users = %v", users)
	}
	var first map[string]any
	for _, u := range users {
		if u.(map[string]any)["id"] == admin.userID {
			first = u.(map[string]any)
		}
	}
	if first["role"] != "admin" || first["disabled"] != false || first["device_count"] != float64(1) || first["created_at"] == nil {
		t.Fatalf("admin user = %v", first)
	}

	e.do("PATCH", "/v1/admin/users/"+admin.userID, admin.access, map[string]any{"disabled": true}).errCode(t, 409, "conflict")
	e.do("PATCH", "/v1/admin/users/"+bob.userID, admin.access, map[string]any{}).errCode(t, 400, "invalid_request")
	e.do("PATCH", "/v1/admin/users/nope", admin.access, map[string]any{"disabled": true}).errCode(t, 404, "not_found")

	r := e.do("PATCH", "/v1/admin/users/"+bob.userID, admin.access, map[string]any{"disabled": true})
	r.status(t, 200)
	if r.json(t)["disabled"] != true {
		t.Fatalf("user = %s", r.Body.String())
	}
	e.do("GET", "/v1/me", bob.access, nil).errCode(t, 401, "unauthorized")
	e.do("POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": bob.refresh}).errCode(t, 401, "unauthorized")
	e.do("PATCH", "/v1/admin/users/"+bob.userID, admin.access, map[string]any{"disabled": false}).status(t, 200)
	e.do("GET", "/v1/me", bob.access, nil).status(t, 200)
}

func TestNoSecretsInLogs(t *testing.T) {
	var logs bytes.Buffer
	db, err := store.Open(context.Background(), testpg.URL(t))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	log := slog.New(slog.NewTextHandler(&logs, &slog.HandlerOptions{Level: slog.LevelDebug}))
	slog.SetDefault(log)
	defer slog.SetDefault(slog.New(slog.NewTextHandler(io.Discard, nil)))
	acc, _ := accounts.New(accounts.Config{Secret: secret, InstancePassword: "pw", Store: db})
	prov := &fakeProvider{err: errors.New("provider said key=tb-leaky-key-123456 is bad")}
	keys, _ := userkeys.New(userkeys.Config{Secret: secret, Store: db, Providers: debrid.NewRegistry(prov), Logger: log})
	api := New(Deps{Accounts: acc, Keys: keys, Logger: log})
	e := &env{t: t, api: api, prov: prov, keys: keys}

	admin := e.bootstrap()
	e.do("PUT", "/v1/me/debrid/torbox", admin.access, map[string]any{"api_key": "tb-leaky-key-123456"}).status(t, 502)
	e.do("POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": admin.refresh})
	for _, s := range []string{"tb-leaky-key-123456", admin.access, admin.refresh} {
		if strings.Contains(logs.String(), s) {
			t.Fatalf("logs contain a secret (%q...): %s", s[:2], logs.String())
		}
	}
}

// Refresh tokens carry 256 bits, so garbage is not counted against anybody: a flood only hits the
// global request-rate cap (429 + Retry-After) and a valid refresh works as soon as the bucket
// has a token again.
func TestRefreshFloodDoesNotLockOutRealUsers(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	garbage := map[string]any{"refresh_token": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"}
	var unauthorized, limited int
	for i := 0; i < 150; i++ {
		r := e.do("POST", "/v1/auth/refresh", "", garbage)
		switch r.Code {
		case 401:
			unauthorized++
		case 429:
			limited++
			if r.Header().Get("Retry-After") == "" {
				t.Fatal("429 without Retry-After")
			}
		default:
			t.Fatalf("status %d", r.Code)
		}
	}
	if unauthorized != 100 || limited != 50 {
		t.Fatalf("401s=%d 429s=%d, want 100/50 (the bucket's burst)", unauthorized, limited)
	}
	e.clk.Advance(time.Second) // the cap refills 20 tokens a second
	e.do("POST", "/v1/auth/refresh", "", map[string]any{"refresh_token": admin.refresh}).status(t, 200)
}

func TestJoinGarbageDoesNotLockOutValidInvites(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	code := e.invite(admin)
	for i := 0; i < 20; i++ {
		e.do("POST", "/v1/auth/join", "", map[string]any{"invite_code": "NOPE-NOPE", "display_name": "x", "device_name": "d"}).errCode(t, 401, "unauthorized")
	}
	e.do("POST", "/v1/auth/join", "", map[string]any{"invite_code": code, "display_name": "Bob", "device_name": "d"}).errCode(t, 429, "rate_limited")
	e.clk.Advance(time.Second)
	e.do("POST", "/v1/auth/join", "", map[string]any{"invite_code": code, "display_name": "Bob", "device_name": "d"}).status(t, 200)
}

func TestTrailingJSONIsRejected(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	routes := []struct{ method, path, token, body string }{
		{"POST", "/v1/auth/bootstrap", "", `{"instance_password":"pw","display_name":"A","device_name":"d"}`},
		{"POST", "/v1/auth/join", "", `{"invite_code":"x","display_name":"A","device_name":"d"}`},
		{"POST", "/v1/auth/link", "", `{"link_code":"x","device_name":"d"}`},
		{"POST", "/v1/auth/refresh", "", `{"refresh_token":"x"}`},
		{"PATCH", "/v1/me", admin.access, `{"display_name":"B"}`},
		{"PUT", "/v1/me/debrid/torbox", admin.access, `{"api_key":"abcdefgh1234"}`},
		{"PATCH", "/v1/admin/users/" + admin.userID, admin.access, `{"disabled":false}`},
		{"POST", "/v1/admin/invites", admin.access, `{"note":"n"}`},
	}
	for _, rt := range routes {
		for _, trail := range []string{"}", "]", `{"a":1}`, "x", " 1", "\n{}"} {
			r := e.do(rt.method, rt.path, rt.token, rt.body+trail)
			r.errCode(t, 400, "invalid_request")
		}
	}
	// trailing whitespace is fine, and the invite body may be absent or empty
	e.do("PATCH", "/v1/me", admin.access, `{"display_name":"B"}`+" \n\t").status(t, 200)
	e.do("POST", "/v1/admin/invites", admin.access, "").status(t, 200)
	e.do("POST", "/v1/admin/invites", admin.access, nil).status(t, 200)
}
