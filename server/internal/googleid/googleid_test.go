package googleid

import (
	"context"
	"crypto"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const (
	testAud    = "34828481793-g6n5cm2psogtrga580g41tmgab4h264p.apps.googleusercontent.com"
	testSecret = "0123456789abcdef0123456789abcdef"
)

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

var rsaKeys sync.Map

func testKey(t testing.TB, name string) *rsa.PrivateKey {
	t.Helper()
	if k, ok := rsaKeys.Load(name); ok {
		return k.(*rsa.PrivateKey)
	}
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	rsaKeys.Store(name, k)
	return k
}

// fakeGoogle serves a JWKS and counts fetches.
type fakeGoogle struct {
	srv          *httptest.Server
	fetches      atomic.Int32
	mu           sync.Mutex
	keys         map[string]*rsa.PrivateKey // kid -> key
	cacheControl string
	status       int
	trailing     string // appended after the JSON document
}

func newFakeGoogle(t *testing.T, keys map[string]*rsa.PrivateKey) *fakeGoogle {
	g := &fakeGoogle{keys: keys, cacheControl: "public, max-age=3600, must-revalidate", status: 200}
	g.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		g.fetches.Add(1)
		g.mu.Lock()
		defer g.mu.Unlock()
		if g.status != 200 {
			http.Error(w, "boom", g.status)
			return
		}
		type jk struct {
			Kty, Alg, Use, Kid, N, E string
		}
		var set struct {
			Keys []map[string]string `json:"keys"`
		}
		for kid, k := range g.keys {
			set.Keys = append(set.Keys, map[string]string{
				"kty": "RSA", "alg": "RS256", "use": "sig", "kid": kid,
				"n": b64.EncodeToString(k.N.Bytes()), "e": b64.EncodeToString(big.NewInt(int64(k.E)).Bytes()),
			})
		}
		w.Header().Set("Cache-Control", g.cacheControl)
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(set)
		_, _ = w.Write([]byte(g.trailing))
	}))
	t.Cleanup(g.srv.Close)
	return g
}

func (g *fakeGoogle) setKeys(keys map[string]*rsa.PrivateKey) {
	g.mu.Lock()
	g.keys = keys
	g.mu.Unlock()
}

type env struct {
	t   *testing.T
	v   *Verifier
	g   *fakeGoogle
	clk *clock
	key *rsa.PrivateKey
}

func newEnv(t *testing.T) *env {
	t.Helper()
	key := testKey(t, "k1")
	g := newFakeGoogle(t, map[string]*rsa.PrivateKey{"k1": key})
	clk := &clock{t: time.Unix(1_800_000_000, 0)}
	v, err := New(Config{ClientIDs: []string{testAud}, Secret: testSecret, JWKSURL: g.srv.URL, HTTPClient: g.srv.Client(), Now: clk.Now})
	if err != nil {
		t.Fatal(err)
	}
	return &env{t, v, g, clk, key}
}

func (e *env) nonce() string {
	e.t.Helper()
	n, _, err := e.v.NewNonce()
	if err != nil {
		e.t.Fatal(err)
	}
	return n
}

// claims returns valid claims for the env's clock with a fresh nonce.
func (e *env) claims() map[string]any {
	now := e.clk.Now().Unix()
	return map[string]any{
		"iss": "https://accounts.google.com", "aud": testAud, "sub": "1234567890",
		"iat": now, "exp": now + 3600, "email": "reader@example.com", "email_verified": true,
		"given_name": "Rita", "name": "Rita Reader", "nonce": e.nonce(),
	}
}

func sign(t testing.TB, key *rsa.PrivateKey, header, claims map[string]any) string {
	t.Helper()
	hb, _ := json.Marshal(header)
	cb, _ := json.Marshal(claims)
	msg := b64.EncodeToString(hb) + "." + b64.EncodeToString(cb)
	d := sha256.Sum256([]byte(msg))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, d[:])
	if err != nil {
		t.Fatal(err)
	}
	return msg + "." + b64.EncodeToString(sig)
}

func (e *env) token(claims map[string]any) string {
	return sign(e.t, e.key, map[string]any{"alg": "RS256", "kid": "k1", "typ": "JWT"}, claims)
}

func wantReason(t *testing.T, err error, reason string) {
	t.Helper()
	var te *TokenError
	if !errors.As(err, &te) || !errors.Is(err, ErrInvalidToken) {
		t.Fatalf("err = %v, want a TokenError %q", err, reason)
	}
	if !strings.Contains(te.Reason, reason) {
		t.Fatalf("reason = %q, want %q", te.Reason, reason)
	}
}

func TestVerifyValid(t *testing.T) {
	e := newEnv(t)
	id, err := e.v.Verify(context.Background(), e.token(e.claims()))
	if err != nil {
		t.Fatal(err)
	}
	if id != (Identity{Sub: "1234567890", Email: "reader@example.com", GivenName: "Rita", Name: "Rita Reader"}) {
		t.Errorf("identity = %+v", id)
	}
}

func TestVerifyClaimChecks(t *testing.T) {
	cases := []struct {
		name   string
		mutate func(c map[string]any, now int64)
		reason string
	}{
		{"wrong aud", func(c map[string]any, _ int64) { c["aud"] = "other.apps.googleusercontent.com" }, "audience"},
		{"missing aud", func(c map[string]any, _ int64) { delete(c, "aud") }, "audience"},
		{"wrong iss", func(c map[string]any, _ int64) { c["iss"] = "https://evil.example" }, "issuer"},
		{"expired", func(c map[string]any, now int64) { c["exp"] = now - 3*60 }, "expired"},
		{"missing exp", func(c map[string]any, _ int64) { delete(c, "exp") }, "exp"},
		{"iat in future", func(c map[string]any, now int64) { c["iat"] = now + 5*60 }, "future"},
		{"email unverified", func(c map[string]any, _ int64) { c["email_verified"] = false }, "email"},
		{"email_verified absent", func(c map[string]any, _ int64) { delete(c, "email_verified") }, "email"},
		{"email empty", func(c map[string]any, _ int64) { c["email"] = "" }, "email"},
		{"no sub", func(c map[string]any, _ int64) { c["sub"] = "" }, "sub"},
		{"no nonce", func(c map[string]any, _ int64) { delete(c, "nonce") }, "nonce"},
		{"foreign nonce", func(c map[string]any, _ int64) { c["nonce"] = "AAAA.BBBB" }, "nonce"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			e := newEnv(t)
			c := e.claims()
			tc.mutate(c, e.clk.Now().Unix())
			_, err := e.v.Verify(context.Background(), e.token(c))
			wantReason(t, err, tc.reason)
		})
	}
}

func TestVerifyLeewayAndAlternates(t *testing.T) {
	e := newEnv(t)
	now := e.clk.Now().Unix()

	c := e.claims()
	c["exp"] = now - 60 // expired 1 min ago: inside the 2 min leeway
	c["iat"] = now + 60 // 1 min in the future: inside the leeway
	c["iss"] = "accounts.google.com"
	c["email_verified"] = "true"
	if _, err := e.v.Verify(context.Background(), e.token(c)); err != nil {
		t.Fatalf("leeway/alternate iss/string bool: %v", err)
	}
}

func TestVerifyBadSignatureAndAlgorithms(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()

	// signed by another key under the known kid
	other := testKey(t, "attacker")
	forged := sign(t, other, map[string]any{"alg": "RS256", "kid": "k1"}, e.claims())
	_, err := e.v.Verify(ctx, forged)
	wantReason(t, err, "bad signature")

	// payload tampered after signing
	tok := e.token(e.claims())
	p := strings.Split(tok, ".")
	c := e.claims()
	c["sub"] = "someone-else"
	cb, _ := json.Marshal(c)
	_, err = e.v.Verify(ctx, p[0]+"."+b64.EncodeToString(cb)+"."+p[2])
	wantReason(t, err, "bad signature")

	// alg none, with and without a signature part
	hb := b64.EncodeToString([]byte(`{"alg":"none","kid":"k1"}`))
	cb, _ = json.Marshal(e.claims())
	_, err = e.v.Verify(ctx, hb+"."+b64.EncodeToString(cb)+".")
	wantReason(t, err, "alg")
	_, err = e.v.Verify(ctx, hb+"."+b64.EncodeToString(cb)+".AAAA")
	wantReason(t, err, "alg")

	// HS256 confusion: MAC keyed with the public modulus bytes
	hb = b64.EncodeToString([]byte(`{"alg":"HS256","kid":"k1"}`))
	msg := hb + "." + b64.EncodeToString(cb)
	m := hmac.New(sha256.New, e.key.N.Bytes())
	m.Write([]byte(msg))
	_, err = e.v.Verify(ctx, msg+"."+b64.EncodeToString(m.Sum(nil)))
	wantReason(t, err, "alg")

	// other algorithms and a missing kid
	for _, h := range []string{`{"alg":"RS512","kid":"k1"}`, `{"alg":"ES256","kid":"k1"}`, `{"alg":"rs256","kid":"k1"}`, `{"alg":"RS256"}`} {
		msg := b64.EncodeToString([]byte(h)) + "." + b64.EncodeToString(cb)
		_, err = e.v.Verify(ctx, msg+".AAAA")
		wantReason(t, err, "")
	}

	// the failed attempts above must not have spent any nonce
	if _, err := e.v.Verify(ctx, e.token(e.claims())); err != nil {
		t.Fatalf("valid token after failures: %v", err)
	}
}

func TestVerifyMalformed(t *testing.T) {
	e := newEnv(t)
	for _, tok := range []string{"", "abc", "a.b", "a.b.c.d", "!!.!!.!!", strings.Repeat("a", maxTokenLen+1)} {
		if _, err := e.v.Verify(context.Background(), tok); !errors.Is(err, ErrInvalidToken) {
			t.Errorf("Verify(%.10q) = %v, want ErrInvalidToken", tok, err)
		}
	}
}

func TestNonceLifecycle(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()

	// single use
	c := e.claims()
	tok := e.token(c)
	if _, err := e.v.Verify(ctx, tok); err != nil {
		t.Fatal(err)
	}
	_, err := e.v.Verify(ctx, tok)
	wantReason(t, err, "already used")

	// expired nonce (token still valid)
	c = e.claims()
	e.clk.Advance(NonceTTL + time.Second)
	now := e.clk.Now().Unix()
	c["iat"], c["exp"] = now, now+3600
	_, err = e.v.Verify(ctx, e.token(c))
	wantReason(t, err, "nonce expired")

	// nonce signed with another server secret
	other, _ := New(Config{ClientIDs: []string{testAud}, Secret: "ffffffffffffffffffffffffffffffff", JWKSURL: e.g.srv.URL, Now: e.clk.Now})
	foreign, _, _ := other.NewNonce()
	c = e.claims()
	c["nonce"] = foreign
	_, err = e.v.Verify(ctx, e.token(c))
	wantReason(t, err, "bad nonce")

	// tampered expiry
	n := e.nonce()
	p, mac, _ := strings.Cut(n, ".")
	raw, _ := b64.DecodeString(p)
	raw[23] ^= 0xff
	c = e.claims()
	c["nonce"] = b64.EncodeToString(raw) + "." + mac
	_, err = e.v.Verify(ctx, e.token(c))
	wantReason(t, err, "bad nonce")
}

func TestNonceShape(t *testing.T) {
	e := newEnv(t)
	n1, exp, err := e.v.NewNonce()
	if err != nil {
		t.Fatal(err)
	}
	n2, _, _ := e.v.NewNonce()
	if n1 == n2 {
		t.Error("nonces repeat")
	}
	if want := e.clk.Now().Add(NonceTTL); !exp.Equal(want) {
		t.Errorf("expires = %v, want %v", exp, want)
	}
	if len(n1) > 128 {
		t.Errorf("nonce too long: %d", len(n1))
	}
}

func TestNonceCacheIsBounded(t *testing.T) {
	e := newEnv(t)
	for i := 0; i < maxUsedNonces; i++ {
		if err := e.v.nonce.consume(e.nonce()); err != nil {
			t.Fatalf("consume %d: %v", i, err)
		}
	}
	if err := e.v.nonce.consume(e.nonce()); !errors.Is(err, ErrBusy) {
		t.Fatalf("full cache: err = %v, want ErrBusy", err)
	}
	if n := len(e.v.nonce.used); n != maxUsedNonces {
		t.Errorf("used = %d", n)
	}
	e.clk.Advance(NonceTTL + time.Second) // everything expired: pruned on demand
	if err := e.v.nonce.consume(e.nonce()); err != nil {
		t.Fatalf("after expiry: %v", err)
	}
	if n := len(e.v.nonce.used); n != 1 {
		t.Errorf("used after prune = %d, want 1", n)
	}
}

func TestNonceConcurrentSingleUse(t *testing.T) {
	e := newEnv(t)
	n := e.nonce()
	var ok atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if e.v.nonce.consume(n) == nil {
				ok.Add(1)
			}
		}()
	}
	wg.Wait()
	if ok.Load() != 1 {
		t.Fatalf("%d consumers succeeded, want 1", ok.Load())
	}
}

func TestJWKSCaching(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		if _, err := e.v.Verify(ctx, e.token(e.claims())); err != nil {
			t.Fatal(err)
		}
	}
	if n := e.g.fetches.Load(); n != 1 {
		t.Fatalf("fetches = %d, want 1 (cached)", n)
	}
	e.clk.Advance(59 * time.Minute) // before max-age
	if _, err := e.v.Verify(ctx, e.token(e.claims())); err != nil {
		t.Fatal(err)
	}
	if n := e.g.fetches.Load(); n != 1 {
		t.Fatalf("fetches = %d before max-age", n)
	}
	e.clk.Advance(2 * time.Minute) // past max-age 3600
	if _, err := e.v.Verify(ctx, e.token(e.claims())); err != nil {
		t.Fatal(err)
	}
	if n := e.g.fetches.Load(); n != 2 {
		t.Fatalf("fetches = %d after max-age, want 2", n)
	}
}

func TestJWKSMaxAgeClamp(t *testing.T) {
	cases := []struct {
		cc   string
		want time.Duration
	}{
		{"public, max-age=3600", time.Hour},
		{"max-age=1", minKeyTTL},
		{"max-age=999999999", maxKeyTTL},
		{"no-cache", minKeyTTL},
		{"", minKeyTTL},
		{"public, MAX-AGE=7200, must-revalidate", 2 * time.Hour},
	}
	for _, tc := range cases {
		e := newEnv(t)
		e.g.cacheControl = tc.cc
		if _, err := e.v.Verify(context.Background(), e.token(e.claims())); err != nil {
			t.Fatal(err)
		}
		e.v.keys.mu.RLock()
		got := e.v.keys.expires.Sub(e.clk.Now())
		e.v.keys.mu.RUnlock()
		if got != tc.want {
			t.Errorf("Cache-Control %q: ttl = %v, want %v", tc.cc, got, tc.want)
		}
	}
}

func TestUnknownKidRefetchOnceAndRateLimited(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	if _, err := e.v.Verify(ctx, e.token(e.claims())); err != nil {
		t.Fatal(err)
	}
	// Google rotates: a new key appears under a new kid.
	k2 := testKey(t, "k2")
	e.g.setKeys(map[string]*rsa.PrivateKey{"k1": e.key, "k2": k2})
	e.clk.Advance(2 * time.Minute) // past refetchGap, well within max-age

	tok2 := sign(t, k2, map[string]any{"alg": "RS256", "kid": "k2"}, e.claims())
	if _, err := e.v.Verify(ctx, tok2); err != nil {
		t.Fatalf("rotated key: %v", err)
	}
	if n := e.g.fetches.Load(); n != 2 {
		t.Fatalf("fetches = %d, want 2", n)
	}

	// A made-up kid triggers one refetch at most per refetchGap.
	e.clk.Advance(2 * time.Minute)
	ghost := sign(t, k2, map[string]any{"alg": "RS256", "kid": "ghost"}, e.claims())
	for i := 0; i < 5; i++ {
		_, err := e.v.Verify(ctx, ghost)
		wantReason(t, err, "unknown key")
	}
	if n := e.g.fetches.Load(); n != 3 {
		t.Fatalf("fetches = %d after unknown-kid flood, want 3", n)
	}
	e.clk.Advance(2 * time.Minute)
	_, err := e.v.Verify(ctx, ghost)
	wantReason(t, err, "unknown key")
	if n := e.g.fetches.Load(); n != 4 {
		t.Fatalf("fetches = %d, want 4 (gap elapsed)", n)
	}
}

func TestJWKSUnavailable(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	e.g.mu.Lock()
	e.g.status = 500
	e.g.mu.Unlock()
	_, err := e.v.Verify(ctx, e.token(e.claims()))
	if !errors.Is(err, ErrUnavailable) || errors.Is(err, ErrInvalidToken) {
		t.Fatalf("err = %v, want ErrUnavailable", err)
	}
	// The outage is not hammered: a second call inside the gap does not fetch again.
	_, _ = e.v.Verify(ctx, e.token(e.claims()))
	if n := e.g.fetches.Load(); n != 1 {
		t.Fatalf("fetches = %d during outage, want 1", n)
	}
	// Recovery after the gap.
	e.g.mu.Lock()
	e.g.status = 200
	e.g.mu.Unlock()
	e.clk.Advance(2 * time.Minute)
	if _, err := e.v.Verify(ctx, e.token(e.claims())); err != nil {
		t.Fatalf("after recovery: %v", err)
	}
}

func TestJWKSTrailingDataIsRejected(t *testing.T) {
	for _, trailing := range []string{"}", `{"keys":[]}`, "x"} {
		e := newEnv(t)
		e.g.mu.Lock()
		e.g.trailing = trailing
		e.g.mu.Unlock()
		if _, err := e.v.Verify(context.Background(), e.token(e.claims())); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("trailing %q: err = %v, want ErrUnavailable", trailing, err)
		}
	}
}

func TestStaleKeysSurviveAnOutage(t *testing.T) {
	e := newEnv(t)
	ctx := context.Background()
	if _, err := e.v.Verify(ctx, e.token(e.claims())); err != nil {
		t.Fatal(err)
	}
	e.g.mu.Lock()
	e.g.status = 503
	e.g.mu.Unlock()
	e.clk.Advance(2 * time.Hour)
	if _, err := e.v.Verify(ctx, e.token(e.claims())); err != nil {
		t.Fatalf("known key during outage: %v", err)
	}
}

func TestJWKSSkipsWeakAndForeignKeys(t *testing.T) {
	weak, err := rsa.GenerateKey(rand.Reader, 1024)
	if err != nil {
		t.Fatal(err)
	}
	e := newEnv(t)
	e.g.setKeys(map[string]*rsa.PrivateKey{"weak": weak, "k1": e.key})
	if _, err := e.v.Verify(context.Background(), e.token(e.claims())); err != nil {
		t.Fatal(err)
	}
	tok := sign(t, weak, map[string]any{"alg": "RS256", "kid": "weak"}, e.claims())
	_, err = e.v.Verify(context.Background(), tok)
	wantReason(t, err, "unknown key")
}

func TestConcurrentVerifyFetchesOnce(t *testing.T) {
	e := newEnv(t)
	var wg sync.WaitGroup
	toks := make([]string, 16)
	for i := range toks {
		toks[i] = e.token(e.claims())
	}
	for _, tok := range toks {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := e.v.Verify(context.Background(), tok); err != nil {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if n := e.g.fetches.Load(); n != 1 {
		t.Fatalf("fetches = %d, want 1", n)
	}
}

func TestNewValidation(t *testing.T) {
	cases := []struct {
		name string
		c    Config
	}{
		{"no ids", Config{Secret: testSecret}},
		{"blank ids", Config{ClientIDs: []string{" ", ""}, Secret: testSecret}},
		{"short secret", Config{ClientIDs: []string{testAud}, Secret: "short"}},
	}
	for _, tc := range cases {
		if _, err := New(tc.c); err == nil {
			t.Errorf("%s: want error", tc.name)
		}
	}
	// a second audience is accepted
	e := newEnv(t)
	v, err := New(Config{ClientIDs: []string{"first.apps.googleusercontent.com", testAud}, Secret: testSecret, JWKSURL: e.g.srv.URL, Now: e.clk.Now})
	if err != nil {
		t.Fatal(err)
	}
	n, _, _ := v.NewNonce()
	c := e.claims()
	c["nonce"] = n
	if _, err := v.Verify(context.Background(), e.token(c)); err != nil {
		t.Fatalf("second audience: %v", err)
	}
}

func TestErrorsNeverContainTheToken(t *testing.T) {
	e := newEnv(t)
	c := e.claims()
	c["aud"] = "other"
	tok := e.token(c)
	_, err := e.v.Verify(context.Background(), tok)
	for _, part := range strings.Split(tok, ".") {
		if strings.Contains(fmt.Sprint(err), part) {
			t.Fatalf("error %q contains token content", err)
		}
	}
}
