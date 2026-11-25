package accounts

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const (
	testSecret = "0123456789abcdef0123456789abcdef"
	uidA       = "11111111-1111-4111-8111-111111111111"
	devA       = "22222222-2222-4222-8222-222222222222"
)

type clock struct {
	mu sync.Mutex
	t  time.Time
}

func newClock() *clock { return &clock{t: time.Unix(1_700_000_000, 0)} }
func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}
func (c *clock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

// stubStore implements the few Store methods the unit tests reach; any other call panics on the
// nil embedded interface, which is the point: unit tests must not touch persistence they do not
// stub. The Postgres-backed behaviour is tested in internal/store.
type stubStore struct {
	Store
	auth      func() (Role, bool, error)
	authCalls atomic.Int32
	createdBy atomic.Int32
}

func (s *stubStore) AuthState(context.Context, string, string, time.Time) (Role, bool, error) {
	s.authCalls.Add(1)
	return s.auth()
}

func (s *stubStore) CreateFirstAdmin(_ context.Context, name, dev string, _ []byte, _ time.Time) (Registered, error) {
	s.createdBy.Add(1)
	return Registered{User: User{ID: uidA, DisplayName: name, Role: RoleAdmin, Settings: DefaultSettings()}, DeviceID: devA}, nil
}

func newService(t *testing.T, st Store, c *clock, password string) *Service {
	t.Helper()
	s, err := New(Config{Secret: testSecret, InstancePassword: password, Store: st, Now: c.Now})
	if err != nil {
		t.Fatal(err)
	}
	return s
}

func TestNewRejectsWeakConfig(t *testing.T) {
	for name, c := range map[string]Config{
		"short secret": {Secret: "short", Store: &stubStore{}},
		"no store":     {Secret: testSecret},
	} {
		if _, err := New(c); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestJWT(t *testing.T) {
	now := time.Unix(1_700_000_000, 0)
	key := []byte(testSecret)
	good := Claims{Sub: uidA, Dev: devA, Iat: now.Unix(), Exp: now.Add(time.Hour).Unix()}
	tok, err := sign(key, good)
	if err != nil {
		t.Fatal(err)
	}
	parts := strings.Split(tok, ".")
	noneHead := b64.EncodeToString([]byte(`{"alg":"none","typ":"JWT"}`))
	expired, _ := sign(key, Claims{Sub: uidA, Dev: devA, Exp: now.Add(-time.Second).Unix()})
	noDev, _ := sign(key, Claims{Sub: uidA, Exp: now.Add(time.Hour).Unix()})
	other, _ := sign([]byte("another-secret-another-secret-123"), good)

	tests := []struct {
		name  string
		token string
		at    time.Time
		ok    bool
	}{
		{"valid", tok, now, true},
		{"valid just before expiry", tok, now.Add(time.Hour - time.Second), true},
		{"expired exactly", tok, now.Add(time.Hour), false},
		{"expired", expired, now, false},
		{"wrong key", other, now, false},
		{"tampered payload", parts[0] + "." + b64.EncodeToString([]byte(`{"sub":"x","dev":"y","exp":9999999999}`)) + "." + parts[2], now, false},
		{"alg none", noneHead + "." + parts[1] + ".", now, false},
		{"alg none keeps signature", noneHead + "." + parts[1] + "." + parts[2], now, false},
		{"missing dev", noDev, now, false},
		{"two parts", parts[0] + "." + parts[1], now, false},
		{"empty", "", now, false},
		{"garbage", "a.b.c", now, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			c, err := verify(key, tc.token, tc.at)
			if (err == nil) != tc.ok {
				t.Fatalf("verify err = %v, want ok=%v", err, tc.ok)
			}
			if tc.ok && (c.Sub != uidA || c.Dev != devA) {
				t.Fatalf("claims = %+v", c)
			}
		})
	}
}

func TestFailLimiter(t *testing.T) {
	c := newClock()
	l := newFailLimiter(3, time.Minute, c.Now)
	for i := 0; i < 3; i++ {
		if ok, _ := l.acquire(); !ok {
			t.Fatalf("attempt %d refused", i)
		}
		c.Advance(10 * time.Second)
	}
	ok, retry := l.acquire()
	if ok || retry != 30*time.Second {
		t.Fatalf("4th attempt: ok=%v retry=%v, want refused with 30s", ok, retry)
	}
	l.refund() // a success gives a slot back
	if ok, _ := l.acquire(); !ok {
		t.Fatal("refund did not free a slot")
	}
	c.Advance(time.Minute)
	for i := 0; i < 3; i++ {
		if ok, _ := l.acquire(); !ok {
			t.Fatalf("after the window, attempt %d refused", i)
		}
	}
}

func TestFailLimiterConcurrent(t *testing.T) {
	l := newFailLimiter(5, time.Minute, time.Now)
	var granted atomic.Int32
	var wg sync.WaitGroup
	for i := 0; i < 50; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if ok, _ := l.acquire(); ok {
				granted.Add(1)
			}
		}()
	}
	wg.Wait()
	if granted.Load() != 5 {
		t.Fatalf("granted %d, want 5", granted.Load())
	}
}

func TestCodes(t *testing.T) {
	for _, n := range []int{inviteCodeLen, linkCodeLen} {
		code, err := newCode(n)
		if err != nil {
			t.Fatal(err)
		}
		if got := len(strings.ReplaceAll(code, "-", "")); got != n {
			t.Fatalf("code %q has %d chars, want %d", code, got, n)
		}
		for _, r := range strings.ReplaceAll(code, "-", "") {
			if !strings.ContainsRune(codeAlphabet, r) {
				t.Fatalf("code %q has %q outside the alphabet", code, r)
			}
		}
		// a typed code (lower case, spaces, no dashes, look-alikes) hashes like the issued one
		typed := strings.ToLower(strings.ReplaceAll(code, "-", " "))
		if string(hashCode(typed)) != string(hashCode(code)) {
			t.Fatalf("typed code %q does not match %q", typed, code)
		}
	}
	a, _ := newCode(inviteCodeLen)
	b, _ := newCode(inviteCodeLen)
	if a == b {
		t.Fatal("codes repeat")
	}
	if string(hashCode("o0il-1")) != string(hashCode("0011-1")) {
		t.Fatal("look-alike folding is inconsistent")
	}
	if string(hashCode("ABCD")) == string(hashCode("ABCE")) {
		t.Fatal("different codes hash equal")
	}
}

func TestRefreshTokenShape(t *testing.T) {
	tok, hash, err := newRefresh()
	if err != nil {
		t.Fatal(err)
	}
	got, ok := hashRefresh(tok)
	if !ok || string(got) != string(hash) {
		t.Fatal("hashRefresh does not match newRefresh")
	}
	for _, bad := range []string{"", "abc", strings.Repeat("A", 43) + "!", strings.Repeat("A", 100)} {
		if _, ok := hashRefresh(bad); ok {
			t.Errorf("hashRefresh(%q) accepted", bad)
		}
	}
}

func TestSettingsPatch(t *testing.T) {
	s := func(v string) *string { return &v }
	b := func(v bool) *bool { return &v }
	langs := func(v ...string) *[]string { return &v }
	nul := Nullable[string]{Set: true}
	prov := Nullable[string]{Set: true, Value: s("realdebrid")}
	tests := []struct {
		name    string
		patch   SettingsPatch
		invalid bool
		check   func(Settings) bool
	}{
		{"empty patch keeps defaults", SettingsPatch{}, false, func(x Settings) bool { return x.Region == "us" && x.SourceMode == "auto" }},
		{"region", SettingsPatch{Region: s("nl")}, false, func(x Settings) bool { return x.Region == "nl" }},
		{"unknown region", SettingsPatch{Region: s("xx")}, true, nil},
		{"languages deduplicated", SettingsPatch{Languages: langs("nl", "en", "nl")}, false, func(x Settings) bool { return len(x.Languages) == 2 && x.Languages[0] == "nl" }},
		{"empty languages", SettingsPatch{Languages: langs()}, true, nil},
		{"unknown language", SettingsPatch{Languages: langs("en", "fr")}, true, nil},
		{"format mp3", SettingsPatch{PreferFormat: s("mp3")}, false, func(x Settings) bool { return x.PreferFormat == "mp3" }},
		{"format empty", SettingsPatch{PreferFormat: s("")}, false, func(x Settings) bool { return x.PreferFormat == "" }},
		{"format flac", SettingsPatch{PreferFormat: s("flac")}, true, nil},
		{"provider set", SettingsPatch{PreferredProvider: prov}, false, func(x Settings) bool { return x.PreferredProvider != nil && *x.PreferredProvider == "realdebrid" }},
		{"provider null clears", SettingsPatch{PreferredProvider: nul}, false, func(x Settings) bool { return x.PreferredProvider == nil }},
		{"provider unknown", SettingsPatch{PreferredProvider: Nullable[string]{Set: true, Value: s("alldebrid")}}, true, nil},
		{"mode p2p", SettingsPatch{SourceMode: s("p2p")}, false, func(x Settings) bool { return x.SourceMode == "p2p" }},
		{"mode bad", SettingsPatch{SourceMode: s("torrent")}, true, nil},
		{"p2p enabled", SettingsPatch{P2PEnabled: b(true)}, false, func(x Settings) bool { return x.P2PEnabled }},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			base := DefaultSettings()
			got, err := tc.patch.Apply(base)
			var ve *ValidationError
			if tc.invalid {
				if !errors.As(err, &ve) {
					t.Fatalf("err = %v, want ValidationError", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			if !tc.check(got) {
				t.Fatalf("settings = %+v", got)
			}
			if base.Region != "us" || len(base.Languages) != 1 {
				t.Fatal("Apply modified its input")
			}
		})
	}
}

func TestCleanName(t *testing.T) {
	tests := []struct {
		in, want string
		max      int
		ok       bool
	}{
		{"  Ada  ", "Ada", 40, true},
		{"", "", 40, false},
		{"   ", "", 40, false},
		{strings.Repeat("é", 40), strings.Repeat("é", 40), 40, true}, // 40 characters, 80 bytes
		{strings.Repeat("é", 41), "", 40, false},
		{"bad\x00name", "", 40, false},
		{"line\nbreak", "", 40, false},
		{"\xff\xfe", "", 40, false},
	}
	for _, tc := range tests {
		got, err := cleanName(tc.in, "display_name", tc.max)
		if (err == nil) != tc.ok || got != tc.want {
			t.Errorf("cleanName(%q) = %q, %v", tc.in, got, err)
		}
	}
}

func TestMiddleware(t *testing.T) {
	c := newClock()
	st := &stubStore{auth: func() (Role, bool, error) { return RoleUser, true, nil }}
	s := newService(t, st, c, "pw")
	mint := func(sub, dev string, exp time.Time) string {
		tok, err := sign(s.secret, Claims{Sub: sub, Dev: dev, Iat: c.Now().Unix(), Exp: exp.Unix()})
		if err != nil {
			t.Fatal(err)
		}
		return tok
	}
	var seen Principal
	h := s.Middleware(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		seen, _ = PrincipalFrom(r.Context())
		w.WriteHeader(http.StatusNoContent)
	}))
	do := func(auth string) *httptest.ResponseRecorder {
		req := httptest.NewRequest("GET", "/x", nil)
		if auth != "" {
			req.Header.Set("Authorization", auth)
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec
	}
	valid := mint(uidA, devA, c.Now().Add(time.Hour))

	tests := []struct {
		name string
		auth string
		want int
	}{
		{"no header", "", 401},
		{"basic scheme", "Basic abc", 401},
		{"empty bearer", "Bearer ", 401},
		{"garbage", "Bearer not.a.jwt", 401},
		{"oversized", "Bearer " + strings.Repeat("a", 3000), 401},
		{"expired", "Bearer " + mint(uidA, devA, c.Now().Add(-time.Minute)), 401},
		{"ids that are not uuids", "Bearer " + mint("1", "2", c.Now().Add(time.Hour)), 401},
		{"valid", "Bearer " + valid, 204},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			rec := do(tc.auth)
			if rec.Code != tc.want {
				t.Fatalf("status = %d, want %d", rec.Code, tc.want)
			}
			if tc.want == 401 {
				if got := strings.TrimSpace(rec.Body.String()); got != `{"error":{"code":"unauthorized"}}` {
					t.Fatalf("body = %s", got)
				}
				if rec.Header().Get("Cache-Control") != "no-store" || rec.Header().Get("WWW-Authenticate") == "" {
					t.Fatalf("headers = %v", rec.Header())
				}
			}
		})
	}
	if seen != (Principal{UserID: uidA, DeviceID: devA, Role: RoleUser}) {
		t.Fatalf("principal = %+v", seen)
	}
}

func TestMiddlewareChecksDeviceAndCaches(t *testing.T) {
	c := newClock()
	alive := atomic.Bool{}
	alive.Store(true)
	st := &stubStore{auth: func() (Role, bool, error) { return RoleAdmin, alive.Load(), nil }}
	s := newService(t, st, c, "pw")
	tok, _ := sign(s.secret, Claims{Sub: uidA, Dev: devA, Iat: c.Now().Unix(), Exp: c.Now().Add(time.Hour).Unix()})
	h := s.Middleware(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(204) }))
	do := func() int {
		req := httptest.NewRequest("GET", "/x", nil)
		req.Header.Set("Authorization", "Bearer "+tok)
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, req)
		return rec.Code
	}

	if do() != 204 || do() != 204 {
		t.Fatal("valid token refused")
	}
	if n := st.authCalls.Load(); n != 1 {
		t.Fatalf("store hit %d times for two requests within the cache TTL, want 1", n)
	}

	// The device is revoked behind the service's back: the cache bounds the delay to 5 s.
	alive.Store(false)
	if do() != 204 {
		t.Fatal("cached answer expected inside the TTL")
	}
	c.Advance(maxCacheTTL + time.Millisecond)
	if do() != 401 {
		t.Fatal("revoked device still accepted after the cache expired")
	}
	// A negative answer is not cached: reviving the device works at once.
	alive.Store(true)
	if do() != 204 {
		t.Fatal("negative answer was cached")
	}

	// Revoking through the service drops the cache immediately.
	st.Store = revokeStub{}
	if err := s.RevokeDevice(context.Background(), Principal{UserID: uidA, DeviceID: devA}, devA); err != nil {
		t.Fatal(err)
	}
	alive.Store(false)
	if do() != 401 {
		t.Fatal("revoked device accepted right after RevokeDevice")
	}
}

type revokeStub struct{ Store }

func (revokeStub) RevokeDevice(context.Context, string, string, time.Time) error { return nil }

func TestMiddlewareStoreFailureIs503(t *testing.T) {
	c := newClock()
	st := &stubStore{auth: func() (Role, bool, error) { return "", false, errors.New("db down") }}
	s := newService(t, st, c, "pw")
	tok, _ := sign(s.secret, Claims{Sub: uidA, Dev: devA, Iat: c.Now().Unix(), Exp: c.Now().Add(time.Hour).Unix()})
	req := httptest.NewRequest("GET", "/x", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	rec := httptest.NewRecorder()
	s.Middleware(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { t.Fatal("reached") })).ServeHTTP(rec, req)
	if rec.Code != 503 || rec.Header().Get("Retry-After") == "" {
		t.Fatalf("status = %d, headers = %v", rec.Code, rec.Header())
	}
}

func TestJWTKeyIsDerivedFromSecret(t *testing.T) {
	c := newClock()
	a := newService(t, &stubStore{auth: func() (Role, bool, error) { return RoleUser, true, nil }}, c, "pw")
	b := newService(t, &stubStore{auth: func() (Role, bool, error) { return RoleUser, true, nil }}, c, "pw")
	other, err := New(Config{Secret: strings.Repeat("z", 32), Store: &stubStore{auth: func() (Role, bool, error) { return RoleUser, true, nil }}, Now: c.Now})
	if err != nil {
		t.Fatal(err)
	}
	if string(a.secret) == testSecret {
		t.Fatal("the JWT key is the raw CONFIG_SECRET, not a derived key")
	}
	tok, _ := sign(a.secret, Claims{Sub: uidA, Dev: devA, Exp: c.Now().Add(time.Hour).Unix()})
	if _, err := b.Authenticate(context.Background(), tok); err != nil {
		t.Fatalf("same secret must verify: %v", err)
	}
	if _, err := other.Authenticate(context.Background(), tok); err == nil {
		t.Fatal("a token verified under a different CONFIG_SECRET")
	}
	// A token signed with the raw secret (e.g. by another component) must not verify.
	raw, _ := sign([]byte(testSecret), Claims{Sub: uidA, Dev: devA, Exp: c.Now().Add(time.Hour).Unix()})
	if _, err := a.Authenticate(context.Background(), raw); err == nil {
		t.Fatal("token signed with the raw secret verified")
	}
}

func TestBootstrapPasswordAndLimiter(t *testing.T) {
	c := newClock()
	st := &stubStore{}
	s := newService(t, st, c, "correct horse")
	ctx := context.Background()

	for i := 0; i < 5; i++ {
		if _, err := s.Bootstrap(ctx, "wrong", "Ada", "Pixel"); !errors.Is(err, ErrBadPassword) {
			t.Fatalf("attempt %d: err = %v", i, err)
		}
	}
	_, err := s.Bootstrap(ctx, "correct horse", "Ada", "Pixel")
	var le *LimitError
	if !errors.As(err, &le) || !errors.Is(err, ErrTooManyAttempts) || le.RetryAfter <= 0 {
		t.Fatalf("after 5 failures err = %v, want a LimitError", err)
	}
	if st.createdBy.Load() != 0 {
		t.Fatal("an admin was created while rate limited")
	}
	c.Advance(time.Minute + time.Second)
	sess, err := s.Bootstrap(ctx, "correct horse", " Ada ", "Pixel")
	if err != nil {
		t.Fatal(err)
	}
	if sess.User.DisplayName != "Ada" || sess.User.Role != RoleAdmin || sess.ExpiresIn != 3600 || sess.RefreshToken == "" {
		t.Fatalf("session = %+v", sess)
	}
	// successes refund their slot: many successful calls never trip the limiter
	for i := 0; i < 10; i++ {
		if _, err := s.Bootstrap(ctx, "correct horse", "Ada", "Pixel"); err != nil {
			t.Fatalf("success %d: %v", i, err)
		}
	}
}

func TestBootstrapValidationDoesNotConsumeAttempts(t *testing.T) {
	c := newClock()
	s := newService(t, &stubStore{}, c, "pw")
	for i := 0; i < 20; i++ {
		var ve *ValidationError
		if _, err := s.Bootstrap(context.Background(), "pw", "", "Pixel"); !errors.As(err, &ve) {
			t.Fatalf("err = %v", err)
		}
	}
	if _, err := s.Bootstrap(context.Background(), "pw", "Ada", "Pixel"); err != nil {
		t.Fatal(err)
	}
}

func TestOpenInstanceBootstrapNeedsNoPassword(t *testing.T) {
	s := newService(t, &stubStore{}, newClock(), "")
	if _, err := s.Bootstrap(context.Background(), "", "Ada", "Pixel"); err != nil {
		t.Fatal(err)
	}
}

func TestAdminOnlyService(t *testing.T) {
	s := newService(t, &stubStore{}, newClock(), "pw")
	ctx := context.Background()
	user := Principal{UserID: uidA, DeviceID: devA, Role: RoleUser}
	if _, err := s.ListUsers(ctx, user); !errors.Is(err, ErrForbidden) {
		t.Errorf("ListUsers: %v", err)
	}
	if _, err := s.SetDisabled(ctx, user, devA, true); !errors.Is(err, ErrForbidden) {
		t.Errorf("SetDisabled: %v", err)
	}
	if _, _, err := s.CreateInvite(ctx, user, "", 0); !errors.Is(err, ErrForbidden) {
		t.Errorf("CreateInvite: %v", err)
	}
	if _, err := s.ListInvites(ctx, user); !errors.Is(err, ErrForbidden) {
		t.Errorf("ListInvites: %v", err)
	}
	if err := s.RevokeInvite(ctx, user, devA); !errors.Is(err, ErrForbidden) {
		t.Errorf("RevokeInvite: %v", err)
	}
	admin := Principal{UserID: uidA, DeviceID: devA, Role: RoleAdmin}
	if _, err := s.SetDisabled(ctx, admin, uidA, true); !errors.Is(err, ErrCannotDisableSelf) {
		t.Errorf("self disable: %v", err)
	}
	var ve *ValidationError
	if _, _, err := s.CreateInvite(ctx, admin, strings.Repeat("x", 81), 0); !errors.As(err, &ve) {
		t.Errorf("long note: %v", err)
	}
	if _, _, err := s.CreateInvite(ctx, admin, "", 721*time.Hour); !errors.As(err, &ve) {
		t.Errorf("long ttl: %v", err)
	}
}
