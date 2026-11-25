package appapi

import (
	"strings"
	"testing"
	"time"
)

func (e *env) googleToken(sub, email string) string {
	e.t.Helper()
	return e.g.Token(e.t, sub, email, nil)
}

func TestGoogleRoutesAnswer404WhenNotConfigured(t *testing.T) {
	e := newEnv(t)
	admin := e.bootstrap()
	routes := []struct{ method, path, token string }{
		{"GET", "/v1/auth/google/nonce", ""},
		{"POST", "/v1/auth/google", ""},
		{"POST", "/v1/auth/google/join", ""},
		{"POST", "/v1/me/google", admin.access},
		{"DELETE", "/v1/me/google", admin.access},
	}
	body := map[string]any{"id_token": "x", "invite_code": "y", "device_name": "d"}
	for _, rt := range routes {
		r := e.do(rt.method, rt.path, rt.token, body)
		r.errCode(t, 404, "google_disabled")
	}
	// the authenticated ones still demand a login first
	e.do("POST", "/v1/me/google", "", body).status(t, 401)
	if e.do("GET", "/v1/version", "", nil).json(t)["google_enabled"] != false {
		t.Fatal("google_enabled should be false")
	}
	// Me carries an unlinked google object either way
	g := e.do("GET", "/v1/me", admin.access, nil).json(t)["google"].(map[string]any)
	if g["linked"] != false || g["email"] != nil || len(g) != 1 {
		t.Fatalf("google = %v", g)
	}
}

func TestGoogleFlowOverHTTP(t *testing.T) {
	e := newEnvOpt(t, true)
	if e.do("GET", "/v1/version", "", nil).json(t)["google_enabled"] != true {
		t.Fatal("google_enabled should be true")
	}

	// nonce: stateless, fresh each call, five minutes
	n1 := e.do("GET", "/v1/auth/google/nonce", "", nil)
	n1.status(t, 200)
	m := n1.json(t)
	exp, err := time.Parse(time.RFC3339, m["expires_at"].(string))
	if err != nil || m["nonce"] == "" || exp.Sub(e.clk.Now()) < 4*time.Minute+50*time.Second || exp.Sub(e.clk.Now()) > 5*time.Minute+time.Second {
		t.Fatalf("nonce = %v (%v)", m, err)
	}
	if n2 := e.do("GET", "/v1/auth/google/nonce", "", nil).json(t); n2["nonce"] == m["nonce"] {
		t.Fatal("nonce repeats")
	}

	admin := e.bootstrap()
	// before linking: google_not_linked (401), not a plain unauthorized
	tok := e.googleToken("sub-admin", "admin@example.com")
	e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": tok, "device_name": "Tablet"}).errCode(t, 401, "google_not_linked")

	// link (needs a login, spends the token's nonce)
	link := map[string]any{"id_token": e.googleToken("sub-admin", "admin@example.com")}
	e.do("POST", "/v1/me/google", "", link).status(t, 401)
	e.do("POST", "/v1/me/google", admin.access, "not json").errCode(t, 400, "invalid_request")
	r := e.do("POST", "/v1/me/google", admin.access, link)
	r.status(t, 200)
	if g := r.json(t)["google"].(map[string]any); g["linked"] != true || g["email"] != "admin@example.com" {
		t.Fatalf("link answer: %s", r.Body.String())
	}
	if g := e.do("GET", "/v1/me", admin.access, nil).json(t)["google"].(map[string]any); g["linked"] != true || g["email"] != "admin@example.com" {
		t.Fatalf("me.google = %v", g)
	}

	// a second device signs in with Google
	s := e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": e.googleToken("sub-admin", "admin@example.com"), "device_name": "Tablet"})
	s.status(t, 200)
	sess := e.session(s)
	if sess.userID != admin.userID {
		t.Fatal("signed in as someone else")
	}
	if got := e.do("GET", "/v1/me/devices", sess.access, nil).json(t)["devices"].([]any); len(got) != 2 {
		t.Fatalf("devices = %d", len(got))
	}

	// replay (the very first token verified, so its nonce is spent) and garbage
	e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": tok, "device_name": "Tablet"}).errCode(t, 401, "unauthorized")
	for name, body := range map[string]any{
		"garbage token":  map[string]any{"id_token": "a.b.c", "device_name": "d"},
		"empty token":    map[string]any{"id_token": "", "device_name": "d"},
		"missing device": map[string]any{"id_token": e.googleToken("sub-admin", "admin@example.com")},
	} {
		r := e.do("POST", "/v1/auth/google", "", body)
		if name == "missing device" {
			r.errCode(t, 400, "invalid_request")
		} else {
			r.errCode(t, 401, "unauthorized")
		}
	}
	// wrong audience / unverified email are plain 401s
	bad := e.g.Token(t, "sub-admin", "admin@example.com", func(c map[string]any) { c["aud"] = "other.apps.googleusercontent.com" })
	rb := e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": bad, "device_name": "d"})
	rb.errCode(t, 401, "unauthorized")
	for _, part := range strings.Split(bad, ".") {
		if strings.Contains(rb.Body.String(), part) {
			t.Fatal("the error body echoes the ID token")
		}
	}
	unver := e.g.Token(t, "sub-admin", "admin@example.com", func(c map[string]any) { c["email_verified"] = false })
	e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": unver, "device_name": "d"}).errCode(t, 401, "unauthorized")

	// trailing JSON is refused here as everywhere
	e.do("POST", "/v1/auth/google", "", `{"id_token":"x","device_name":"d"}}`).errCode(t, 400, "invalid_request")

	// unlink: 204, idempotent, then google_not_linked again; existing devices keep working
	e.do("DELETE", "/v1/me/google", admin.access, nil).status(t, 204)
	e.do("DELETE", "/v1/me/google", admin.access, nil).status(t, 204)
	e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": e.googleToken("sub-admin", "admin@example.com"), "device_name": "d"}).errCode(t, 401, "google_not_linked")
	e.do("GET", "/v1/me", sess.access, nil).status(t, 200)
	if g := e.do("GET", "/v1/me", admin.access, nil).json(t)["google"].(map[string]any); g["linked"] != false {
		t.Fatalf("me.google after unlink = %v", g)
	}
}

func TestGoogleJoinOverHTTP(t *testing.T) {
	e := newEnvOpt(t, true)
	admin := e.bootstrap()
	code := e.invite(admin)

	// no invite, no account
	e.do("POST", "/v1/auth/google/join", "", map[string]any{"id_token": e.googleToken("sub-gina", "gina@example.com"), "invite_code": "NOPE-NOPE", "device_name": "Pixel"}).errCode(t, 401, "unauthorized")
	e.do("POST", "/v1/auth/google/join", "", map[string]any{"id_token": e.googleToken("sub-gina", "gina@example.com"), "device_name": "Pixel"}).errCode(t, 401, "unauthorized")
	if n := len(e.do("GET", "/v1/admin/users", admin.access, nil).json(t)["users"].([]any)); n != 1 {
		t.Fatalf("users = %d after refused joins", n)
	}

	r := e.do("POST", "/v1/auth/google/join", "", map[string]any{"id_token": e.googleToken("sub-gina", "gina@example.com"), "invite_code": code, "device_name": "Pixel"})
	r.status(t, 200)
	u := r.json(t)["user"].(map[string]any)
	if u["display_name"] != "Gina" || u["role"] != "user" || u["google"].(map[string]any)["email"] != "gina@example.com" {
		t.Fatalf("joined user = %v", u)
	}
	gina := e.session(r)

	// the invite is spent; a taken identity is 409 and keeps a fresh invite
	e.do("POST", "/v1/auth/google/join", "", map[string]any{"id_token": e.googleToken("sub-other", "o@example.com"), "invite_code": code, "device_name": "Pixel"}).errCode(t, 401, "unauthorized")
	code2 := e.invite(admin)
	e.do("POST", "/v1/auth/google/join", "", map[string]any{"id_token": e.googleToken("sub-gina", "gina@example.com"), "invite_code": code2, "device_name": "Pixel"}).errCode(t, 409, "conflict")
	e.do("POST", "/v1/auth/google/join", "", map[string]any{"id_token": e.googleToken("sub-other", "o@example.com"), "invite_code": code2, "display_name": strings.Repeat("n", 41), "device_name": "Pixel"}).errCode(t, 400, "invalid_request")
	e.do("POST", "/v1/auth/google/join", "", map[string]any{"id_token": e.googleToken("sub-other", "o@example.com"), "invite_code": code2, "display_name": "Otto", "device_name": "Pixel"}).status(t, 200)

	// linking an identity somebody else holds is 409; a second identity is 409
	e.do("POST", "/v1/me/google", admin.access, map[string]any{"id_token": e.googleToken("sub-gina", "gina@example.com")}).errCode(t, 409, "conflict")
	e.do("POST", "/v1/me/google", gina.access, map[string]any{"id_token": e.googleToken("sub-else", "else@example.com")}).errCode(t, 409, "conflict")

	// admin list: linked flag and email; Gina's own Me never shows anyone else's
	for _, au := range e.do("GET", "/v1/admin/users", admin.access, nil).json(t)["users"].([]any) {
		a := au.(map[string]any)
		if a["id"] == admin.userID {
			if a["google_linked"] != false || a["google_email"] != nil {
				t.Fatalf("admin row = %v", a)
			}
		} else if a["google_linked"] != true || a["google_email"] == nil {
			t.Fatalf("user row = %v", a)
		}
	}
}

func TestGoogleDisabledUserOverHTTP(t *testing.T) {
	e := newEnvOpt(t, true)
	admin := e.bootstrap()
	bob := e.join(admin, "Bob")
	e.do("POST", "/v1/me/google", bob.access, map[string]any{"id_token": e.googleToken("sub-bob", "bob@example.com")}).status(t, 200)
	e.do("PATCH", "/v1/admin/users/"+bob.userID, admin.access, map[string]any{"disabled": true}).status(t, 200)
	e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": e.googleToken("sub-bob", "bob@example.com"), "device_name": "d"}).errCode(t, 401, "unauthorized")
	e.do("PATCH", "/v1/admin/users/"+bob.userID, admin.access, map[string]any{"disabled": false}).status(t, 200)
	e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": e.googleToken("sub-bob", "bob@example.com"), "device_name": "d"}).status(t, 200)
}

// Garbage ID tokens never lock anybody out: only the global request-rate cap applies.
func TestGoogleGarbageFloodOnlyHitsTheRateCap(t *testing.T) {
	e := newEnvOpt(t, true)
	admin := e.bootstrap()
	e.do("POST", "/v1/me/google", admin.access, map[string]any{"id_token": e.googleToken("sub-admin", "admin@example.com")}).status(t, 200)
	var limited int
	for i := 0; i < 40; i++ { // the link above took 1 of the 20 burst tokens
		r := e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": "junk.junk.junk", "device_name": "d"})
		switch r.Code {
		case 401:
		case 429:
			limited++
			if r.Header().Get("Retry-After") == "" {
				t.Fatal("429 without Retry-After")
			}
		default:
			t.Fatalf("status %d", r.Code)
		}
	}
	if limited != 21 {
		t.Fatalf("429s = %d, want 21 (19 tokens left of the burst)", limited)
	}
	e.clk.Advance(time.Second)
	e.do("POST", "/v1/auth/google", "", map[string]any{"id_token": e.googleToken("sub-admin", "admin@example.com"), "device_name": "d"}).status(t, 200)
}

func TestGoogleNonceIsRateCapped(t *testing.T) {
	e := newEnvOpt(t, true)
	for i := 0; i < 20; i++ {
		e.do("GET", "/v1/auth/google/nonce", "", nil).status(t, 200)
	}
	r := e.do("GET", "/v1/auth/google/nonce", "", nil)
	r.errCode(t, 429, "rate_limited")
	if r.Header().Get("Retry-After") == "" {
		t.Fatal("429 without Retry-After")
	}
	e.clk.Advance(time.Second)
	e.do("GET", "/v1/auth/google/nonce", "", nil).status(t, 200)
}
