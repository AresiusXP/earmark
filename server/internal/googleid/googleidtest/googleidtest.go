// Package googleidtest is a fake Google for tests of packages that sit on top of googleid: a
// JWKS endpoint backed by a locally generated RSA key and a real googleid.Verifier pointed at it.
package googleidtest

import (
	"crypto"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/googleid"
)

// Aud is the audience the verifier accepts.
const Aud = "34828481793-g6n5cm2psogtrga580g41tmgab4h264p.apps.googleusercontent.com"

// Google is a fake Google plus a verifier that trusts it.
type Google struct {
	V   *googleid.Verifier
	key *rsa.PrivateKey
	now func() time.Time
}

// New starts the fake JWKS server (stopped with the test). secret is the CONFIG_SECRET the nonces
// are keyed with; now is the clock the verifier and the token timestamps use.
func New(t testing.TB, secret string, now func() time.Time) *Google {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Cache-Control", "public, max-age=3600")
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]string{{
			"kty": "RSA", "alg": "RS256", "use": "sig", "kid": "test-key",
			"n": base64.RawURLEncoding.EncodeToString(key.N.Bytes()),
			"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(key.E)).Bytes()),
		}}})
	}))
	t.Cleanup(srv.Close)
	v, err := googleid.New(googleid.Config{ClientIDs: []string{Aud}, Secret: secret, JWKSURL: srv.URL, HTTPClient: srv.Client(), Now: now})
	if err != nil {
		t.Fatal(err)
	}
	return &Google{V: v, key: key, now: now}
}

// Nonce returns a fresh server nonce.
func (g *Google) Nonce(t testing.TB) string {
	t.Helper()
	n, _, err := g.V.NewNonce()
	if err != nil {
		t.Fatal(err)
	}
	return n
}

// Token signs an ID token for (sub, email) with a fresh nonce. mutate may change the claims
// (for example to set a wrong audience).
func (g *Google) Token(t testing.TB, sub, email string, mutate func(claims map[string]any)) string {
	t.Helper()
	now := g.now().Unix()
	claims := map[string]any{
		"iss": "https://accounts.google.com", "aud": Aud, "sub": sub, "email": email, "email_verified": true,
		"given_name": "Gina", "name": "Gina Google", "iat": now, "exp": now + 3600, "nonce": g.Nonce(t),
	}
	if mutate != nil {
		mutate(claims)
	}
	enc := base64.RawURLEncoding
	hb, _ := json.Marshal(map[string]string{"alg": "RS256", "kid": "test-key", "typ": "JWT"})
	cb, _ := json.Marshal(claims)
	msg := enc.EncodeToString(hb) + "." + enc.EncodeToString(cb)
	d := sha256.Sum256([]byte(msg))
	sig, err := rsa.SignPKCS1v15(rand.Reader, g.key, crypto.SHA256, d[:])
	if err != nil {
		t.Fatal(err)
	}
	return msg + "." + enc.EncodeToString(sig)
}
