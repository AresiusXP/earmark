// Package appapi serves the /v1 API of the Android app (api/openapi.yaml): auth, the caller's
// profile, devices and debrid keys, and the admin endpoints. Catalog, library, release and
// app-update routes are added by their own packages through Extend.
//
// Unlike the Stremio routes, /v1 never sends Access-Control-Allow-Origin: the app is not a
// browser. Every response is Cache-Control: no-store unless a handler overrides it, and no
// request path or body is ever logged (the access log of httpapi records route patterns only).
package appapi

import (
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"strconv"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/accounts"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

// APIVersion is the contract version (bumped on breaking changes).
const APIVersion = 1

const maxBody = 16 << 10

// Deps are the collaborators of the API.
type Deps struct {
	Accounts *accounts.Service
	Keys     *userkeys.Service
	Version  string // server version
	Logger   *slog.Logger
}

// API is the /v1 handler.
type API struct {
	acc *accounts.Service
	key *userkeys.Service
	ver string
	log *slog.Logger
	mux *http.ServeMux
}

// Router is what Extend hands to other packages to register their routes.
type Router struct {
	// Mux is the /v1 mux; register patterns like "GET /v1/search".
	Mux *http.ServeMux
	// Auth wraps a handler so it requires a valid access token (401 otherwise) and the
	// Principal is available through PrincipalFrom.
	Auth func(http.Handler) http.Handler
	// Admin is Auth plus role admin (403 otherwise).
	Admin func(http.Handler) http.Handler
}

// New builds the API with the account, device, debrid-key and admin routes.
func New(d Deps) *API {
	a := &API{acc: d.Accounts, key: d.Keys, ver: d.Version, log: d.Logger, mux: http.NewServeMux()}
	if a.log == nil {
		a.log = slog.Default()
	}
	auth, admin := a.acc.Middleware, a.requireAdmin
	m := a.mux

	m.HandleFunc("GET /v1/version", a.version)
	m.HandleFunc("POST /v1/auth/bootstrap", a.bootstrap)
	m.HandleFunc("POST /v1/auth/join", a.join)
	m.HandleFunc("POST /v1/auth/link", a.link)
	m.HandleFunc("POST /v1/auth/refresh", a.refresh)

	a.registerGoogle(m, auth)

	m.Handle("GET /v1/me", auth(http.HandlerFunc(a.getMe)))
	m.Handle("PATCH /v1/me", auth(http.HandlerFunc(a.patchMe)))
	m.Handle("GET /v1/me/devices", auth(http.HandlerFunc(a.listDevices)))
	m.Handle("POST /v1/me/devices/link-code", auth(http.HandlerFunc(a.createLinkCode)))
	m.Handle("DELETE /v1/me/devices/{deviceId}", auth(http.HandlerFunc(a.revokeDevice)))
	m.Handle("GET /v1/me/debrid", auth(http.HandlerFunc(a.listDebrid)))
	m.Handle("PUT /v1/me/debrid/{provider}", auth(http.HandlerFunc(a.putDebrid)))
	m.Handle("POST /v1/me/debrid/{provider}", auth(http.HandlerFunc(a.recheckDebrid)))
	m.Handle("DELETE /v1/me/debrid/{provider}", auth(http.HandlerFunc(a.deleteDebrid)))

	m.Handle("GET /v1/admin/users", admin(http.HandlerFunc(a.adminListUsers)))
	m.Handle("PATCH /v1/admin/users/{userId}", admin(http.HandlerFunc(a.adminUpdateUser)))
	m.Handle("GET /v1/admin/invites", admin(http.HandlerFunc(a.adminListInvites)))
	m.Handle("POST /v1/admin/invites", admin(http.HandlerFunc(a.adminCreateInvite)))
	m.Handle("DELETE /v1/admin/invites/{inviteId}", admin(http.HandlerFunc(a.adminRevokeInvite)))

	m.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		WriteError(w, http.StatusNotFound, "not_found", "no such route")
	})
	return a
}

// Extend lets another package register more /v1 routes. Call it before serving.
func (a *API) Extend(register func(Router)) {
	register(Router{Mux: a.mux, Auth: a.acc.Middleware, Admin: a.requireAdmin})
}

func (a *API) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	h.Set("Cache-Control", "no-store")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Referrer-Policy", "no-referrer")
	a.mux.ServeHTTP(w, r)
}

func (a *API) requireAdmin(next http.Handler) http.Handler {
	return a.acc.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if p, ok := accounts.PrincipalFrom(r.Context()); !ok || p.Role != accounts.RoleAdmin {
			WriteError(w, http.StatusForbidden, "forbidden", "admin only")
			return
		}
		next.ServeHTTP(w, r)
	}))
}

// PrincipalFrom returns the authenticated caller of a request behind Auth/Admin.
func PrincipalFrom(r *http.Request) (accounts.Principal, bool) {
	return accounts.PrincipalFrom(r.Context())
}

// DebridKey returns the plaintext key of the CALLER (never of anybody else) for provider. Use
// KeyError to answer a failure.
func (a *API) DebridKey(r *http.Request, provider string) (userkeys.Key, error) {
	p, ok := accounts.PrincipalFrom(r.Context())
	if !ok {
		return userkeys.Key{}, accounts.ErrUnauthorized
	}
	return a.key.GetForUse(r.Context(), p.UserID, provider)
}

// KeyError answers a DebridKey failure (403 no_debrid_key / bad_debrid_key, 400 for an unknown
// provider, 500 otherwise) and reports whether err was non-nil.
func (a *API) KeyError(w http.ResponseWriter, err error) bool {
	switch {
	case err == nil:
		return false
	case errors.Is(err, userkeys.ErrNoKey):
		WriteError(w, http.StatusForbidden, "no_debrid_key", "add your debrid key first")
	case errors.Is(err, userkeys.ErrBadKey), errors.Is(err, userkeys.ErrUnreadable):
		WriteError(w, http.StatusForbidden, "bad_debrid_key", "your debrid key was rejected, set it again")
	default:
		a.fail(w, err)
	}
	return true
}

// ---- helpers --------------------------------------------------------------------------------

// WriteJSON writes v as the JSON body with the status.
func WriteJSON(w http.ResponseWriter, status int, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		WriteError(w, http.StatusInternalServerError, "upstream_failed", "internal error")
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(append(b, '\n'))
}

type errorBody struct {
	Error errorDetail `json:"error"`
}

type errorDetail struct {
	Code       string `json:"code"`
	Message    string `json:"message,omitempty"`
	RetryAfter int    `json:"retry_after_s,omitempty"`
}

// WriteError writes the contract's error body.
func WriteError(w http.ResponseWriter, status int, code, msg string) {
	WriteJSON(w, status, errorBody{errorDetail{Code: code, Message: msg}})
}

func writeRateLimited(w http.ResponseWriter, after time.Duration) {
	secs := int(after.Round(time.Second) / time.Second)
	if secs < 1 {
		secs = 1
	}
	w.Header().Set("Retry-After", strconv.Itoa(secs))
	WriteJSON(w, http.StatusTooManyRequests, errorBody{errorDetail{Code: "rate_limited", Message: "too many attempts, try again later", RetryAfter: secs}})
}

// fail maps a service error to the contract's error codes.
func (a *API) fail(w http.ResponseWriter, err error) {
	var (
		ve *accounts.ValidationError
		le *accounts.LimitError
	)
	switch {
	case errors.As(err, &ve):
		WriteError(w, http.StatusBadRequest, "invalid_request", ve.Msg)
	case errors.As(err, &le):
		writeRateLimited(w, le.RetryAfter)
	case errors.Is(err, accounts.ErrBadPassword):
		WriteError(w, http.StatusUnauthorized, "unauthorized", "wrong instance password")
	case errors.Is(err, accounts.ErrInvalidCode):
		WriteError(w, http.StatusUnauthorized, "unauthorized", "unknown, used or expired code")
	case errors.Is(err, accounts.ErrBadRefresh), errors.Is(err, accounts.ErrUnauthorized), errors.Is(err, accounts.ErrInvalidToken):
		WriteError(w, http.StatusUnauthorized, "unauthorized", "invalid token")
	case errors.Is(err, accounts.ErrAlreadyBootstrapped):
		WriteError(w, http.StatusConflict, "conflict", "the instance already has users")
	case errors.Is(err, accounts.ErrCannotDisableSelf):
		WriteError(w, http.StatusConflict, "conflict", "you cannot disable yourself")
	case errors.Is(err, accounts.ErrLastAdmin):
		WriteError(w, http.StatusConflict, "conflict", "you cannot disable the last admin")
	case errors.Is(err, accounts.ErrGoogleDisabled):
		WriteError(w, http.StatusNotFound, "google_disabled", "Google sign-in is not enabled on this server")
	case errors.Is(err, accounts.ErrGoogleToken):
		WriteError(w, http.StatusUnauthorized, "unauthorized", "invalid Google ID token")
	case errors.Is(err, accounts.ErrGoogleNotLinked):
		WriteError(w, http.StatusUnauthorized, "google_not_linked", "this Google account is not linked to a user, join with an invite code")
	case errors.Is(err, accounts.ErrGoogleUnavailable):
		WriteError(w, http.StatusBadGateway, "upstream_failed", "Google could not be reached, try again")
	case errors.Is(err, accounts.ErrGoogleTaken):
		WriteError(w, http.StatusConflict, "conflict", "this Google account is already linked to another user")
	case errors.Is(err, accounts.ErrGoogleAlreadyLinked):
		WriteError(w, http.StatusConflict, "conflict", "another Google account is already linked, unlink it first")
	case errors.Is(err, accounts.ErrForbidden):
		WriteError(w, http.StatusForbidden, "forbidden", "admin only")
	case errors.Is(err, accounts.ErrNotFound):
		WriteError(w, http.StatusNotFound, "not_found", "not found")
	case errors.Is(err, userkeys.ErrUnknownProvider):
		WriteError(w, http.StatusBadRequest, "invalid_request", "unknown provider")
	case errors.Is(err, userkeys.ErrInvalidKey):
		WriteError(w, http.StatusBadRequest, "invalid_request", "api_key must be 8-256 characters without spaces")
	case errors.Is(err, userkeys.ErrBadKey):
		WriteError(w, http.StatusBadRequest, "bad_debrid_key", "the provider rejected this api key")
	case errors.Is(err, userkeys.ErrUnavailable):
		WriteError(w, http.StatusBadGateway, "debrid_unavailable", "the debrid service could not be reached, try again")
	default:
		a.log.Error("appapi: request failed", "err", err)
		WriteError(w, http.StatusInternalServerError, "upstream_failed", "internal error")
	}
}

// readJSON decodes exactly one JSON value from r (at most 16 KiB) and rejects anything after
// it, including a stray `}` or `]` that json.Decoder.More would let through. With optional, an
// empty body is accepted and leaves v untouched.
func readJSON(w http.ResponseWriter, r *http.Request, v any, optional bool) error {
	r.Body = http.MaxBytesReader(w, r.Body, maxBody)
	dec := json.NewDecoder(r.Body)
	if err := dec.Decode(v); err != nil {
		if optional && errors.Is(err, io.EOF) {
			return nil
		}
		return err
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		return errors.New("trailing data after the JSON value")
	}
	return nil
}

// decode reads a JSON object body (at most 16 KiB, nothing after it). It answers 400 and
// returns false on failure.
func decode(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := readJSON(w, r, v, false); err != nil {
		WriteError(w, http.StatusBadRequest, "invalid_request", "invalid JSON body")
		return false
	}
	return true
}

// decodeOptional is decode for bodies that may be empty.
func decodeOptional(w http.ResponseWriter, r *http.Request, v any) bool {
	if err := readJSON(w, r, v, true); err != nil {
		WriteError(w, http.StatusBadRequest, "invalid_request", "invalid JSON body")
		return false
	}
	return true
}
