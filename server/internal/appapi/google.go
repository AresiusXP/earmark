package appapi

import (
	"net/http"
	"time"
)

// Sign in with Google (api/openapi.yaml, docs/DESIGN.md). The app gets an ID token from Android
// Credential Manager and sends it here; the server only verifies who the caller is. Every route
// answers 404 {"error":{"code":"google_disabled"}} while GOOGLE_CLIENT_ID is unset. Tokens are
// never logged and never echoed.

type googleDTO struct {
	Linked bool    `json:"linked"`
	Email  *string `json:"email,omitempty"`
}

func (a *API) registerGoogle(m *http.ServeMux, auth func(http.Handler) http.Handler) {
	m.HandleFunc("GET /v1/auth/google/nonce", a.googleNonce)
	m.HandleFunc("POST /v1/auth/google", a.googleSignIn)
	m.HandleFunc("POST /v1/auth/google/join", a.googleJoin)
	m.Handle("POST /v1/me/google", auth(http.HandlerFunc(a.googleLink)))
	m.Handle("DELETE /v1/me/google", auth(http.HandlerFunc(a.googleUnlink)))
}

func (a *API) googleNonce(w http.ResponseWriter, _ *http.Request) {
	n, exp, err := a.acc.GoogleNonce()
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, map[string]any{"nonce": n, "expires_at": exp.UTC().Format(time.RFC3339)})
}

func (a *API) googleSignIn(w http.ResponseWriter, r *http.Request) {
	var in struct {
		IDToken    string `json:"id_token"`
		DeviceName string `json:"device_name"`
	}
	if !decode(w, r, &in) {
		return
	}
	s, err := a.acc.GoogleSignIn(r.Context(), in.IDToken, in.DeviceName)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toSession(s))
}

func (a *API) googleJoin(w http.ResponseWriter, r *http.Request) {
	var in struct {
		IDToken     string `json:"id_token"`
		InviteCode  string `json:"invite_code"`
		DisplayName string `json:"display_name"`
		DeviceName  string `json:"device_name"`
	}
	if !decode(w, r, &in) {
		return
	}
	s, err := a.acc.GoogleJoin(r.Context(), in.IDToken, in.InviteCode, in.DisplayName, in.DeviceName)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toSession(s))
}

func (a *API) googleLink(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	var in struct {
		IDToken string `json:"id_token"`
	}
	if !decode(w, r, &in) {
		return
	}
	u, err := a.acc.GoogleLink(r.Context(), p, in.IDToken)
	if err != nil {
		a.fail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toMe(u))
}

func (a *API) googleUnlink(w http.ResponseWriter, r *http.Request) {
	p, ok := a.principal(w, r)
	if !ok {
		return
	}
	if _, err := a.acc.GoogleUnlink(r.Context(), p); err != nil {
		a.fail(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func toGoogle(email *string) googleDTO {
	if email == nil {
		return googleDTO{}
	}
	return googleDTO{Linked: true, Email: email}
}
