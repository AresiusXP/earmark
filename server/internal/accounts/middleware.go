package accounts

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"strings"
)

// maxTokenLen bounds the bearer value we are willing to decode (real tokens are ~230 bytes).
const maxTokenLen = 2048

// Middleware requires a valid `Authorization: Bearer` access token of a live device of an
// enabled user and puts the Principal in the request context. Failures answer
// {"error":{"code":"unauthorized"}}; a store failure answers 503 so clients retry.
func (s *Service) Middleware(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok {
			unauthorized(w)
			return
		}
		tok = strings.TrimSpace(tok)
		if tok == "" || len(tok) > maxTokenLen {
			unauthorized(w)
			return
		}
		p, err := s.Authenticate(r.Context(), tok)
		if err != nil {
			if errors.Is(err, ErrInvalidToken) || errors.Is(err, ErrUnauthorized) {
				unauthorized(w)
				return
			}
			slog.Default().Error("accounts: authenticate", "err", err)
			w.Header().Set("Cache-Control", "no-store")
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Retry-After", "2")
			w.WriteHeader(http.StatusServiceUnavailable)
			_, _ = w.Write([]byte(`{"error":{"code":"upstream_failed","message":"temporarily unavailable"}}` + "\n"))
			return
		}
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), ctxKey{}, p)))
	})
}

func unauthorized(w http.ResponseWriter) {
	w.Header().Set("WWW-Authenticate", `Bearer realm="audiobooks"`)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusUnauthorized)
	_, _ = w.Write([]byte(`{"error":{"code":"unauthorized"}}` + "\n"))
}
