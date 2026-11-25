// Package googleid verifies Google ID tokens (Sign in with Google through Android Credential
// Manager). It only establishes WHO the caller is: no OAuth redirect flow, no access to any
// Google API. Standard library only.
//
// A token is accepted when its RS256 signature matches a key of Google's JWKS (cached by kid,
// honouring Cache-Control max-age), iss is Google, aud is one of the configured client IDs, it
// is neither expired nor issued in the future (2 minutes of leeway), the email is verified, sub
// is set and its `nonce` claim is a nonce this server minted, not expired and not used before.
//
// Tokens are never logged and never appear in errors.
package googleid

import (
	"context"
	"crypto"
	"crypto/hkdf"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"slices"
	"strings"
	"time"
)

const (
	// DefaultJWKSURL is where Google publishes its ID-token signing keys.
	DefaultJWKSURL = "https://www.googleapis.com/oauth2/v3/certs"

	// Leeway is the clock skew tolerated for exp and iat.
	Leeway = 2 * time.Minute

	maxTokenLen = 4096
	nonceLabel  = "audiobooks-google-nonce-v1"
)

var (
	// ErrInvalidToken means the token or its nonce was rejected. Use errors.As with *TokenError
	// for the (safe to log) reason.
	ErrInvalidToken = errors.New("googleid: invalid id token")
	// ErrUnavailable means Google's keys could not be fetched (not the caller's fault).
	ErrUnavailable = errors.New("googleid: key set unavailable")
	// ErrBusy means too many nonces are in use; retry later.
	ErrBusy = errors.New("googleid: too many nonces in use")
)

// TokenError is a rejected token. Reason never contains token content.
type TokenError struct{ Reason string }

func (e *TokenError) Error() string { return "googleid: invalid id token: " + e.Reason }
func (e *TokenError) Unwrap() error { return ErrInvalidToken }

func reject(reason string) error { return &TokenError{Reason: reason} }

// Identity is the verified Google account behind a token.
type Identity struct {
	Sub       string // stable account id
	Email     string // verified
	GivenName string
	Name      string
}

// Config configures a Verifier.
type Config struct {
	// ClientIDs are the accepted audiences (the web OAuth client ID; public). At least one.
	ClientIDs []string
	// Secret (CONFIG_SECRET) keys the HMAC of the stateless nonces, through HKDF with its own label.
	Secret string
	// JWKSURL defaults to DefaultJWKSURL (tests inject their own).
	JWKSURL string
	// HTTPClient fetches the key set; default has a 10 s timeout.
	HTTPClient *http.Client
	// Now is the clock (tests).
	Now func() time.Time
}

// Verifier verifies ID tokens and mints/consumes nonces. Safe for concurrent use.
type Verifier struct {
	aud   []string
	keys  *keySet
	nonce *nonces
	now   func() time.Time
}

// New builds a Verifier.
func New(c Config) (*Verifier, error) {
	var aud []string
	for _, id := range c.ClientIDs {
		id = strings.TrimSpace(id)
		if id != "" && !slices.Contains(aud, id) {
			aud = append(aud, id)
		}
	}
	if len(aud) == 0 {
		return nil, errors.New("googleid: at least one client ID is required")
	}
	if len(c.Secret) < 32 {
		return nil, errors.New("googleid: secret must be at least 32 bytes")
	}
	key, err := hkdf.Key(sha256.New, []byte(c.Secret), nil, nonceLabel, 32)
	if err != nil {
		return nil, fmt.Errorf("googleid: derive nonce key: %w", err)
	}
	now := c.Now
	if now == nil {
		now = time.Now
	}
	url := c.JWKSURL
	if url == "" {
		url = DefaultJWKSURL
	}
	hc := c.HTTPClient
	if hc == nil {
		hc = &http.Client{Timeout: 10 * time.Second}
	}
	return &Verifier{aud: aud, keys: newKeySet(url, hc, now), nonce: newNonces(key, now), now: now}, nil
}

// NewNonce returns a fresh single-use nonce (valid NonceTTL) for the app to pass to Google.
func (v *Verifier) NewNonce() (nonce string, expires time.Time, err error) { return v.nonce.mint() }

var b64 = base64.RawURLEncoding

type header struct {
	Alg string `json:"alg"`
	Kid string `json:"kid"`
}

type claims struct {
	Iss           string   `json:"iss"`
	Aud           string   `json:"aud"`
	Sub           string   `json:"sub"`
	Exp           *float64 `json:"exp"`
	Iat           *float64 `json:"iat"`
	Nonce         string   `json:"nonce"`
	Email         string   `json:"email"`
	EmailVerified boolish  `json:"email_verified"`
	GivenName     string   `json:"given_name"`
	Name          string   `json:"name"`
}

// boolish accepts true and "true" (Google has used both forms).
type boolish bool

func (b *boolish) UnmarshalJSON(raw []byte) error {
	switch string(raw) {
	case "true", `"true"`:
		*b = true
	default:
		*b = false
	}
	return nil
}

// Verify checks the ID token and, last of all, spends its nonce: a token that fails any other
// check never uses up a nonce, so an unauthenticated caller cannot burn nonces.
func (v *Verifier) Verify(ctx context.Context, idToken string) (Identity, error) {
	if len(idToken) == 0 || len(idToken) > maxTokenLen {
		return Identity{}, reject("malformed token")
	}
	parts := strings.Split(idToken, ".")
	if len(parts) != 3 {
		return Identity{}, reject("malformed token")
	}
	var h header
	if raw, err := b64.DecodeString(parts[0]); err != nil || json.Unmarshal(raw, &h) != nil {
		return Identity{}, reject("malformed header")
	}
	// No algorithm negotiation: RS256 or nothing (so "none" and HS256-with-the-public-key
	// confusion are refused before any key is looked at).
	if h.Alg != "RS256" {
		return Identity{}, reject("unsupported alg")
	}
	if h.Kid == "" {
		return Identity{}, reject("missing kid")
	}
	sig, err := b64.DecodeString(parts[2])
	if err != nil || len(sig) == 0 {
		return Identity{}, reject("malformed signature")
	}
	pub, err := v.keys.get(ctx, h.Kid)
	if err != nil {
		return Identity{}, err
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if err := rsa.VerifyPKCS1v15(pub, crypto.SHA256, digest[:], sig); err != nil {
		return Identity{}, reject("bad signature")
	}

	var c claims
	if raw, err := b64.DecodeString(parts[1]); err != nil || json.Unmarshal(raw, &c) != nil {
		return Identity{}, reject("malformed claims")
	}
	if c.Iss != "https://accounts.google.com" && c.Iss != "accounts.google.com" {
		return Identity{}, reject("wrong issuer")
	}
	if !slices.Contains(v.aud, c.Aud) {
		return Identity{}, reject("wrong audience")
	}
	now := v.now()
	if c.Exp == nil || c.Iat == nil {
		return Identity{}, reject("missing exp or iat")
	}
	if now.After(time.Unix(int64(*c.Exp), 0).Add(Leeway)) {
		return Identity{}, reject("expired")
	}
	if time.Unix(int64(*c.Iat), 0).After(now.Add(Leeway)) {
		return Identity{}, reject("issued in the future")
	}
	if c.Sub == "" || len(c.Sub) > 255 {
		return Identity{}, reject("missing sub")
	}
	if c.Email == "" || !bool(c.EmailVerified) {
		return Identity{}, reject("email not verified")
	}
	if c.Nonce == "" {
		return Identity{}, reject("missing nonce")
	}
	if err := v.nonce.consume(c.Nonce); err != nil {
		return Identity{}, err
	}
	return Identity{Sub: c.Sub, Email: c.Email, GivenName: c.GivenName, Name: c.Name}, nil
}
