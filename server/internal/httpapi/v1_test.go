package httpapi

import (
	"net/http"
	"testing"
)

// /v1 is the app API: it must not inherit the Stremio wildcard CORS policy, and Stremio routes
// must keep it.
func TestV1IsOutsideTheStremioCORSPolicy(t *testing.T) {
	e := newEnv(t, "")
	v1 := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Seen-Path", r.URL.Path)
		w.WriteHeader(http.StatusNoContent)
	})
	h := New(Deps{API: e.api, Sealer: e.sealer, PublicURL: testPublic, V1: v1})

	rec := doOn(h, http.MethodGet, "/v1/me")
	if rec.Code != http.StatusNoContent || rec.Header().Get("X-Seen-Path") != "/v1/me" {
		t.Fatalf("/v1/me not routed to the v1 handler: %d", rec.Code)
	}
	for _, name := range []string{"Access-Control-Allow-Origin", "Access-Control-Allow-Headers", "Access-Control-Allow-Methods"} {
		if got := rec.Header().Get(name); got != "" {
			t.Errorf("/v1 sends %s = %q", name, got)
		}
	}
	if rec.Header().Get("Referrer-Policy") != "no-referrer" || rec.Header().Get("X-Content-Type-Options") != "nosniff" {
		t.Error("/v1 lost the security headers")
	}
	// a preflight on /v1 reaches the handler (no blanket 204 with CORS headers)
	if rec := doOn(h, http.MethodOptions, "/v1/me"); rec.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Error("OPTIONS /v1 answered with a CORS header")
	}
	// Stremio keeps its wildcard
	for _, path := range []string{"/manifest.json", "/healthz", "/nope"} {
		if got := doOn(h, http.MethodGet, path).Header().Get("Access-Control-Allow-Origin"); got != "*" {
			t.Errorf("%s: Allow-Origin = %q", path, got)
		}
	}
	// no v1 handler: 404, still without CORS
	h = New(Deps{API: e.api, Sealer: e.sealer, PublicURL: testPublic})
	if rec := doOn(h, http.MethodGet, "/v1/version"); rec.Code != 404 || rec.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Errorf("without a v1 handler: %d %v", rec.Code, rec.Header())
	}
	// "/v1" itself and lookalikes are Stremio paths
	if got := doOn(h, http.MethodGet, "/v1").Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Errorf("/v1 (no slash) Allow-Origin = %q", got)
	}
}
