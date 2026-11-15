package httpapi

import (
	"bytes"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

func TestResolveRedirect(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	var got struct {
		provider, hash string
		idx            int
		cfg            userconfig.Config
	}
	e.api.resolve = func(cfg userconfig.Config, p, h string, i int) (debrid.ResolveResult, error) {
		got.provider, got.hash, got.idx, got.cfg = p, h, i, cfg
		return debrid.ResolveResult{URL: "https://cdn.example/dl/abc?token=x"}, nil
	}
	for _, tc := range []struct {
		method, path string
		idx          int
	}{
		{http.MethodGet, "/resolve/" + tok + "/torbox/" + testHash + "/4", 4},
		{http.MethodGet, "/resolve/" + tok + "/torbox/" + testHash + "/-1", -1},
		{http.MethodGet, "/resolve/" + tok + "/torbox/" + testHash + "/4/Dune%20Chapter%201.mp3", 4},
	} {
		rec := e.do(tc.method, tc.path, nil)
		mustStatus(t, rec, 302)
		if loc := rec.Header().Get("Location"); loc != "https://cdn.example/dl/abc?token=x" {
			t.Errorf("%s Location = %q", tc.path, loc)
		}
		if rec.Header().Get("Cache-Control") != "no-store" {
			t.Errorf("Cache-Control = %q", rec.Header().Get("Cache-Control"))
		}
		if got.provider != "torbox" || got.hash != testHash || got.idx != tc.idx || got.cfg.Debrid["torbox"] != testKey {
			t.Errorf("service got %+v", got)
		}
		if rec.Header().Get("Access-Control-Allow-Origin") != "*" {
			t.Error("CORS missing")
		}
	}
}

func TestResolveBadRequests(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	called := false
	e.api.resolve = func(userconfig.Config, string, string, int) (debrid.ResolveResult, error) {
		called = true
		return debrid.ResolveResult{URL: "https://x.example/"}, nil
	}
	for _, p := range []string{
		"/resolve/" + tok + "/TorBox/" + testHash + "/1",
		"/resolve/" + tok + "/torbox/" + strings.ToUpper(testHash) + "/1",
		"/resolve/" + tok + "/torbox/" + testHash + "/-2",
		"/resolve/" + tok + "/torbox/" + testHash + "/abc",
		"/resolve/" + tok + "/torbox/" + testHash,
		"/resolve/" + tok,
		"/resolve/",
	} {
		mustStatus(t, e.get(p), 404)
	}
	if called {
		t.Error("service called for malformed resolve path")
	}
	mustStatus(t, e.get("/resolve/AAAA/torbox/"+testHash+"/1"), 400)
	// a /resolve/ path must not collide with the catalog routes
	mustStatus(t, e.get("/resolve/catalog/audiobook/abk-top/x"), 404)
}

func TestResolveUnusableURL(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	for _, u := range []string{"", "javascript:alert(1)", "/relative", "ftp://x.example/a"} {
		e.api.resolve = func(userconfig.Config, string, string, int) (debrid.ResolveResult, error) {
			return debrid.ResolveResult{URL: u}, nil
		}
		mustStatus(t, e.get("/resolve/"+tok+"/torbox/"+testHash+"/1"), 502)
	}
}

func TestResolvePlaceholderAndRange(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	path := "/resolve/" + tok + "/torbox/" + testHash + "/1"
	cases := map[string]func(userconfig.Config, string, string, int) (debrid.ResolveResult, error){
		"pending": func(userconfig.Config, string, string, int) (debrid.ResolveResult, error) {
			return debrid.ResolveResult{Pending: true, Progress: 40}, nil
		},
		"bad key": func(userconfig.Config, string, string, int) (debrid.ResolveResult, error) {
			return debrid.ResolveResult{}, debrid.ErrBadKey
		},
	}
	for name, fn := range cases {
		t.Run(name, func(t *testing.T) {
			e.api.resolve = fn
			rec := e.get(path)
			mustStatus(t, rec, 200)
			h := rec.Header()
			if h.Get("Content-Type") != "audio/mpeg" || h.Get("Cache-Control") != "no-store" || h.Get("Accept-Ranges") != "bytes" {
				t.Errorf("headers = %v", h)
			}
			if rec.Body.Len() != len(placeholderMP3) || !bytes.Equal(rec.Body.Bytes(), placeholderMP3) {
				t.Errorf("body length %d", rec.Body.Len())
			}

			rec = e.do(http.MethodGet, path, nil, "Range", "bytes=104-207")
			mustStatus(t, rec, 206)
			if cr := rec.Header().Get("Content-Range"); cr != "bytes 104-207/"+itoa(len(placeholderMP3)) {
				t.Errorf("Content-Range = %q", cr)
			}
			if !bytes.Equal(rec.Body.Bytes(), placeholderMP3[104:208]) || rec.Header().Get("Cache-Control") != "no-store" {
				t.Error("range body/headers wrong")
			}

			rec = e.do(http.MethodGet, path, nil, "Range", "bytes=-100")
			mustStatus(t, rec, 206)
			if rec.Body.Len() != 100 {
				t.Errorf("suffix range len = %d", rec.Body.Len())
			}

			rec = e.do(http.MethodHead, path, nil)
			mustStatus(t, rec, 200)
			if rec.Body.Len() != 0 || rec.Header().Get("Content-Type") != "audio/mpeg" {
				t.Errorf("HEAD: body %d, CT %q", rec.Body.Len(), rec.Header().Get("Content-Type"))
			}
		})
	}
	if !strings.Contains(e.logs.String(), "key rejected") {
		t.Error("bad key must be logged at warn")
	}
	if strings.Contains(e.logs.String(), testKey) || strings.Contains(e.logs.String(), tok) {
		t.Error("secrets in logs")
	}
}

func TestPlaceholderMP3Structure(t *testing.T) {
	if len(placeholderMP3) != mp3Frames*mp3FrameLen {
		t.Fatalf("len = %d", len(placeholderMP3))
	}
	for i := 0; i < len(placeholderMP3); i += mp3FrameLen {
		f := placeholderMP3[i : i+4]
		// sync, MPEG1, Layer III, no CRC, 32 kbps, 44.1 kHz, no padding, mono
		if f[0] != 0xFF || f[1] != 0xFB || f[2] != 0x10 || f[3] != 0xC0 {
			t.Fatalf("frame %d header = %x", i/mp3FrameLen, f)
		}
	}
	if d := time.Duration(mp3Frames) * 1152 * time.Second / 44100; d < 9*time.Second || d > 11*time.Second {
		t.Errorf("duration = %v", d)
	}
}

func TestResolveErrors(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	path := "/resolve/" + tok + "/torbox/" + testHash + "/1"
	tests := []struct {
		name   string
		err    error
		status int
		retry  bool
	}{
		{"rate limited", debrid.ErrRateLimited, 429, true},
		{"wrapped rate limited", errors.Join(errors.New("x"), debrid.ErrRateLimited), 429, true},
		{"debrid not found", debrid.ErrNotFound, 404, false},
		{"service not found", service.ErrNotFound, 404, false},
		{"no provider", service.ErrNoProvider, 404, false},
		{"failed", debrid.ErrFailed, 502, false},
		{"other", errors.New("boom"), 502, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			e.api.resolve = func(userconfig.Config, string, string, int) (debrid.ResolveResult, error) {
				return debrid.ResolveResult{}, tc.err
			}
			rec := e.get(path)
			mustStatus(t, rec, tc.status)
			if (rec.Header().Get("Retry-After") != "") != tc.retry {
				t.Errorf("Retry-After = %q", rec.Header().Get("Retry-After"))
			}
			if rec.Header().Get("Cache-Control") != "no-store" || rec.Header().Get("Access-Control-Allow-Origin") != "*" {
				t.Errorf("headers = %v", rec.Header())
			}
		})
	}
}

func TestResolveMetrics(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	e.api.resolve = func(_ userconfig.Config, p string, _ string, _ int) (debrid.ResolveResult, error) {
		if p == "torbox" {
			return debrid.ResolveResult{URL: "https://cdn.example/x"}, nil
		}
		return debrid.ResolveResult{Pending: true}, nil
	}
	e.get("/resolve/" + tok + "/torbox/" + testHash + "/1")
	e.get("/resolve/" + tok + "/realdebrid/" + testHash + "/1")
	e.get("/resolve/" + tok + "/madeup/" + testHash + "/1")
	body := e.get("/metrics").Body.String()
	for _, want := range []string{
		`abk_resolve_total{outcome="redirect",provider="torbox"} 1`,
		`abk_resolve_total{outcome="pending",provider="realdebrid"} 1`,
		`abk_resolve_total{outcome="pending",provider="unknown"} 1`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("metrics missing %q", want)
		}
	}
}

// ---- configure ----------------------------------------------------------------------------

var nonceRe = regexp.MustCompile(`'nonce-([A-Za-z0-9+/]+)'`)

func TestConfigurePage(t *testing.T) {
	e := newEnv(t, "")
	rec := e.get("/configure")
	mustStatus(t, rec, 200)
	csp := rec.Header().Get("Content-Security-Policy")
	for _, want := range []string{"default-src 'none'", "script-src 'nonce-", "style-src 'nonce-", "connect-src 'self'", "frame-ancestors 'none'", "base-uri 'none'"} {
		if !strings.Contains(csp, want) {
			t.Errorf("CSP %q lacks %q", csp, want)
		}
	}
	m := nonceRe.FindStringSubmatch(csp)
	if m == nil {
		t.Fatal("no nonce in CSP")
	}
	body := rec.Body.String()
	if strings.Count(body, `nonce="`+m[1]+`"`) != 2 {
		t.Errorf("nonce must be on the style and script tags")
	}
	if strings.Contains(body, "{{") {
		t.Error("unreplaced placeholder")
	}
	for _, bad := range []string{"http://", "https://", "<link", "src=", "onclick", "onsubmit"} {
		if strings.Contains(body, bad) {
			t.Errorf("page contains %q (external resource or inline handler)", bad)
		}
	}
	if rec.Header().Get("Content-Type") != "text/html; charset=utf-8" || rec.Header().Get("Cache-Control") != "no-store" {
		t.Errorf("headers = %v", rec.Header())
	}
	rec2 := e.get("/configure")
	if nonceRe.FindStringSubmatch(rec2.Header().Get("Content-Security-Policy"))[1] == m[1] {
		t.Error("nonce must change per request")
	}
	boot := bootOf(t, body)
	if boot.PasswordRequired || boot.Config != nil || boot.Current != "" || len(boot.Providers) != 2 {
		t.Errorf("boot = %+v", boot)
	}
	for _, id := range []string{`value="us"`, `value="uk"`, `value="de"`, `value="fr"`, `value="it"`, `value="es"`, `value="ca"`, `value="au"`, `value="in"`, `value="nl"`, "English", "Nederlands", `type="password"`, "pwbox"} {
		if !strings.Contains(body, id) {
			t.Errorf("page lacks %q", id)
		}
	}
}

func bootOf(t *testing.T, html string) bootData {
	t.Helper()
	re := regexp.MustCompile(`(?s)<script type="application/json" id="boot">(.*?)</script>`)
	m := re.FindStringSubmatch(html)
	if m == nil {
		t.Fatal("no boot data")
	}
	var b bootData
	if err := json.Unmarshal([]byte(m[1]), &b); err != nil {
		t.Fatal(err)
	}
	return b
}

func TestConfigurePagePrefillDoesNotEchoKeys(t *testing.T) {
	e := newEnv(t, "hunter2")
	tok := e.token(t, userconfig.Config{
		Debrid: map[string]string{"torbox": testKey}, Region: "de", Languages: []string{"nl"}, PreferFormat: "mp3",
		ShowUncached: true, MaxReleases: 8,
	})
	rec := e.get("/" + tok + "/configure")
	mustStatus(t, rec, 200)
	body := rec.Body.String()
	if strings.Contains(body, testKey) || strings.Contains(body, "hunter2") {
		t.Fatal("page leaks a key or the instance password")
	}
	b := bootOf(t, body)
	if !b.PasswordRequired || b.Current != tok || b.Config == nil {
		t.Fatalf("boot = %+v", b)
	}
	if b.Config.Region != "de" || b.Config.PreferFormat != "mp3" || !b.Config.ShowUncached || b.Config.MaxReleases != 8 || len(b.Config.Languages) != 1 || b.Config.Languages[0] != "nl" {
		t.Errorf("config = %+v", b.Config)
	}
	has := map[string]bool{}
	for _, p := range b.Providers {
		has[p.Name] = p.HasKey
	}
	if !has["torbox"] || has["realdebrid"] {
		t.Errorf("hasKey = %v", has)
	}
	mustStatus(t, e.get("/AAAA/configure"), 400)
}

func TestConfigureBootDataCannotBreakOutOfScript(t *testing.T) {
	e := newEnv(t, "")
	e.api.providers = []string{"x</script><script>alert(1)</script>"}
	body := e.get("/configure").Body.String()
	if strings.Contains(body, "</script><script>alert") {
		t.Error("boot data not escaped")
	}
}

func postConfig(e *env, v any, hdr ...string) *httptest.ResponseRecorder {
	b, _ := json.Marshal(v)
	return e.do(http.MethodPost, "/api/config", bytes.NewReader(b), append([]string{"Content-Type", "application/json"}, hdr...)...)
}

func TestConfigAPISuccess(t *testing.T) {
	e := newEnv(t, "")
	rec := postConfig(e, map[string]any{
		"keys": map[string]string{"torbox": " " + testKey + " ", "realdebrid": ""}, "region": "de",
		"languages": []string{"en", "nl"}, "preferFormat": "mp3", "showUncached": true, "maxReleases": 99,
	})
	mustStatus(t, rec, 200)
	if rec.Header().Get("Cache-Control") != "no-store" || rec.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Errorf("headers = %v", rec.Header())
	}
	var out configResponse
	if err := json.Unmarshal(rec.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	if out.ManifestURL != testPublic+"/"+out.Token+"/manifest.json" || out.InstallURL != "stremio://audiobooks.example.org/"+out.Token+"/manifest.json" {
		t.Errorf("urls = %+v", out)
	}
	if strings.Contains(rec.Body.String(), testKey) {
		t.Error("response echoes the key")
	}
	cfg, err := e.sealer.Open(out.Token)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Debrid["torbox"] != testKey || len(cfg.Debrid) != 1 || cfg.Region != "de" || cfg.PreferFormat != "mp3" || !cfg.ShowUncached || cfg.MaxReleases != 20 {
		t.Errorf("cfg = %+v", cfg)
	}
	if e.api.gotValidate["torbox"] != testKey || len(e.api.gotValidate) != 1 {
		t.Errorf("validated = %v", e.api.gotValidate)
	}
	// the manifest of the new token works
	mustStatus(t, e.get("/"+out.Token+"/manifest.json"), 200)
}

func TestConfigAPIKeepsKeysFromCurrent(t *testing.T) {
	e := newEnv(t, "")
	cur := e.token(t, userconfig.Config{Debrid: map[string]string{"torbox": testKey}, Region: "us"})
	rec := postConfig(e, map[string]any{
		"current": cur, "keys": map[string]string{"torbox": "", "realdebrid": "RD-NEW"}, "region": "fr",
	})
	mustStatus(t, rec, 200)
	var out configResponse
	_ = json.Unmarshal(rec.Body.Bytes(), &out)
	cfg, _ := e.sealer.Open(out.Token)
	if cfg.Debrid["torbox"] != testKey || cfg.Debrid["realdebrid"] != "RD-NEW" || cfg.Region != "fr" {
		t.Errorf("cfg = %+v", cfg)
	}
	if len(e.api.gotValidate) != 1 || e.api.gotValidate["realdebrid"] != "RD-NEW" {
		t.Errorf("only new keys are validated: %v", e.api.gotValidate)
	}

	// settings-only change: nothing to validate
	e.api.gotValidate = nil
	mustStatus(t, postConfig(e, map[string]any{"current": cur, "region": "uk"}), 200)
	if e.api.gotValidate != nil {
		t.Errorf("validated %v", e.api.gotValidate)
	}

	mustStatus(t, postConfig(e, map[string]any{"current": "garbage", "keys": map[string]string{"torbox": "k"}}), 400)
}

func TestConfigAPIPassword(t *testing.T) {
	e := newEnv(t, "hunter2")
	body := map[string]any{"keys": map[string]string{"torbox": "k"}}
	rec := postConfig(e, body)
	mustStatus(t, rec, 401)
	body["password"] = "hunter3"
	mustStatus(t, postConfig(e, body), 401)
	body["password"] = "hunter2"
	rec = postConfig(e, body)
	mustStatus(t, rec, 200)
	if strings.Contains(rec.Body.String(), "hunter2") {
		t.Error("password echoed")
	}
	if !passwordOK("a", "a") || passwordOK("a", "ab") || passwordOK("", "a") {
		t.Error("passwordOK")
	}
	// no password configured: field ignored
	open := newEnv(t, "")
	mustStatus(t, postConfig(open, map[string]any{"password": "whatever", "keys": map[string]string{"torbox": "k"}}), 200)
}

func TestConfigAPIValidation(t *testing.T) {
	e := newEnv(t, "")
	e.api.validate = func(keys map[string]string) map[string]error {
		out := map[string]error{}
		if keys["torbox"] == "bad" {
			out["torbox"] = debrid.ErrBadKey
		}
		if keys["realdebrid"] == "down" {
			out["realdebrid"] = errors.New("GET https://api.example/?apikey=SECRET: timeout")
		}
		return out
	}
	rec := postConfig(e, map[string]any{"keys": map[string]string{"torbox": "bad", "realdebrid": "down"}})
	mustStatus(t, rec, 422)
	m := decode(t, rec)
	errs := m["errors"].(map[string]any)
	if errs["torbox"] != "key rejected by the provider" || errs["realdebrid"] != "could not verify the key, try again" {
		t.Errorf("errors = %v", errs)
	}
	if strings.Contains(rec.Body.String(), "SECRET") || strings.Contains(rec.Body.String(), "bad\"") {
		t.Error("error detail or key leaked")
	}

	rec = postConfig(e, map[string]any{"keys": map[string]string{"nope": "k"}})
	mustStatus(t, rec, 422)
	if decode(t, rec)["errors"].(map[string]any)["nope"] != "unknown provider" {
		t.Errorf("body = %s", rec.Body.String())
	}

	rec = postConfig(e, map[string]any{"keys": map[string]string{}})
	mustStatus(t, rec, 422)
	if _, ok := decode(t, rec)["errors"].(map[string]any); !ok {
		t.Errorf("body = %s", rec.Body.String())
	}
	mustStatus(t, postConfig(e, map[string]any{"keys": map[string]string{"torbox": strings.Repeat("k", 600)}}), 422)

	for name, body := range map[string]any{
		"bad region":    map[string]any{"keys": map[string]string{"torbox": "k"}, "region": "zz"},
		"bad language":  map[string]any{"keys": map[string]string{"torbox": "k"}, "languages": []string{"xx"}},
		"bad format":    map[string]any{"keys": map[string]string{"torbox": "k"}, "preferFormat": "wav"},
		"unknown field": map[string]any{"keys": map[string]string{"torbox": "k"}, "admin": true},
	} {
		t.Run(name, func(t *testing.T) { mustStatus(t, postConfig(e, body), 400) })
	}
	rec = e.do(http.MethodPost, "/api/config", strings.NewReader("{not json"))
	mustStatus(t, rec, 400)
	if strings.TrimSpace(rec.Body.String()) != `{"error":"invalid request"}` {
		t.Errorf("body = %s", rec.Body.String())
	}
}

func TestConfigAPIBodyLimit(t *testing.T) {
	e := newEnv(t, "")
	big := `{"keys":{"torbox":"` + strings.Repeat("k", 9000) + `"}}`
	rec := e.do(http.MethodPost, "/api/config", strings.NewReader(big))
	mustStatus(t, rec, 413)
	if rec.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Error("CORS missing")
	}
	// just under the limit with padding is accepted
	ok := `{"keys":{"torbox":"k"},"password":"` + strings.Repeat("p", 7000) + `"}`
	mustStatus(t, e.do(http.MethodPost, "/api/config", strings.NewReader(ok)), 200)
}

func TestConfigAPIRateLimit(t *testing.T) {
	e := newEnv(t, "hunter2")
	now := time.Unix(1_000_000, 0)
	s := e.h.(*server)
	s.limiter.now = func() time.Time { return now }
	for i := 0; i < 30; i++ {
		if rec := postConfig(e, map[string]any{}); rec.Code != 401 {
			t.Fatalf("request %d: status %d", i, rec.Code)
		}
	}
	rec := postConfig(e, map[string]any{})
	mustStatus(t, rec, 429)
	if rec.Header().Get("Retry-After") == "" || rec.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Errorf("headers = %v", rec.Header())
	}
	now = now.Add(2 * time.Second) // 30/min = 0.5 token/s -> 1 token
	mustStatus(t, postConfig(e, map[string]any{}), 401)
	mustStatus(t, postConfig(e, map[string]any{}), 429)
	now = now.Add(time.Hour)
	mustStatus(t, postConfig(e, map[string]any{}), 401)
}

func TestInstallURLWithPathPrefix(t *testing.T) {
	e := newEnv(t, "")
	s := New(Deps{API: e.api, Sealer: e.sealer, PublicURL: "http://localhost:8080/abk/"}).(*server)
	if got := s.installURL("T"); got != "stremio://localhost:8080/abk/T/manifest.json" {
		t.Errorf("installURL = %q", got)
	}
}

func TestRootRedirectsToConfigure(t *testing.T) {
	e := newEnv(t, "")
	rec := e.get("/")
	mustStatus(t, rec, 302)
	if rec.Header().Get("Location") != "/configure" {
		t.Errorf("Location = %q", rec.Header().Get("Location"))
	}
}

// A HEAD probe must never reach the service: resolving can add a torrent to the user's account.
func TestResolveHeadHasNoSideEffects(t *testing.T) {
	e := newEnv(t, "")
	tok := e.defaultToken(t)
	calls := 0
	e.api.resolve = func(userconfig.Config, string, string, int) (debrid.ResolveResult, error) {
		calls++
		return debrid.ResolveResult{URL: "https://cdn.example/x"}, nil
	}
	rec := e.do(http.MethodHead, "/resolve/"+tok+"/torbox/"+testHash+"/4", nil)
	mustStatus(t, rec, 200)
	if calls != 0 || rec.Header().Get("Location") != "" {
		t.Fatalf("HEAD resolved: calls=%d location=%q", calls, rec.Header().Get("Location"))
	}
}
