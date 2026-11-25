package accounts

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/AresiusXP/earmark/server/internal/googleid"
)

type fakeVerifier struct {
	id    googleid.Identity
	err   error
	calls atomic.Int32
}

func (f *fakeVerifier) NewNonce() (string, time.Time, error) {
	return "nonce", time.Unix(1_700_000_300, 0), nil
}

func (f *fakeVerifier) Verify(context.Context, string) (googleid.Identity, error) {
	f.calls.Add(1)
	return f.id, f.err
}

type googleStub struct {
	Store
	signIn  func(GoogleIdentity) (Registered, error)
	redeem  func(name string) (Registered, error)
	link    error
	unlink  atomic.Int32
	linked  atomic.Int32
	user    User
	devName string
}

func (s *googleStub) GoogleSignIn(_ context.Context, g GoogleIdentity, dev string, _ []byte, _ time.Time) (Registered, error) {
	s.devName = dev
	return s.signIn(g)
}

func (s *googleStub) RedeemInviteGoogle(_ context.Context, _ []byte, _ GoogleIdentity, name, _ string, _ []byte, _ time.Time) (Registered, error) {
	return s.redeem(name)
}

func (s *googleStub) LinkGoogle(context.Context, string, GoogleIdentity, time.Time) error {
	s.linked.Add(1)
	return s.link
}

func (s *googleStub) UnlinkGoogle(context.Context, string) error    { s.unlink.Add(1); return nil }
func (s *googleStub) GetUser(context.Context, string) (User, error) { return s.user, nil }

func newGoogleService(t *testing.T, st Store, v GoogleVerifier) (*Service, *clock) {
	t.Helper()
	c := newClock()
	s, err := New(Config{Secret: testSecret, InstancePassword: "pw", Store: st, Google: v, Now: c.Now})
	if err != nil {
		t.Fatal(err)
	}
	return s, c
}

func okReg() Registered {
	return Registered{User: User{ID: uidA, DisplayName: "x", Role: RoleUser, Settings: DefaultSettings()}, DeviceID: devA}
}

func TestGoogleDisabledWhenNoVerifier(t *testing.T) {
	s := newService(t, &stubStore{}, newClock(), "pw")
	ctx := context.Background()
	p := Principal{UserID: uidA, DeviceID: devA, Role: RoleUser}
	if s.GoogleEnabled() {
		t.Fatal("enabled without a verifier")
	}
	if _, _, err := s.GoogleNonce(); !errors.Is(err, ErrGoogleDisabled) {
		t.Errorf("nonce: %v", err)
	}
	if _, err := s.GoogleSignIn(ctx, "t", "d"); !errors.Is(err, ErrGoogleDisabled) {
		t.Errorf("sign-in: %v", err)
	}
	if _, err := s.GoogleJoin(ctx, "t", "c", "n", "d"); !errors.Is(err, ErrGoogleDisabled) {
		t.Errorf("join: %v", err)
	}
	if _, err := s.GoogleLink(ctx, p, "t"); !errors.Is(err, ErrGoogleDisabled) {
		t.Errorf("link: %v", err)
	}
	if _, err := s.GoogleUnlink(ctx, p); !errors.Is(err, ErrGoogleDisabled) {
		t.Errorf("unlink: %v", err)
	}
}

func TestGoogleVerifierErrorsAreMapped(t *testing.T) {
	cases := []struct {
		name string
		in   error
		is   error
	}{
		{"invalid token", &googleid.TokenError{Reason: "expired"}, ErrGoogleToken},
		{"keys down", fmt.Errorf("%w: status 500", googleid.ErrUnavailable), ErrGoogleUnavailable},
		{"nonce cache full", googleid.ErrBusy, ErrTooManyAttempts},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			st := &googleStub{}
			s, _ := newGoogleService(t, st, &fakeVerifier{err: tc.in})
			ctx := context.Background()
			p := Principal{UserID: uidA, DeviceID: devA}
			for name, call := range map[string]func() error{
				"sign-in": func() error { _, err := s.GoogleSignIn(ctx, "tok", "Pixel"); return err },
				"join":    func() error { _, err := s.GoogleJoin(ctx, "tok", "code", "Bob", "Pixel"); return err },
				"link":    func() error { _, err := s.GoogleLink(ctx, p, "tok"); return err },
			} {
				err := call()
				if !errors.Is(err, tc.is) {
					t.Errorf("%s: err = %v, want %v", name, err, tc.is)
				}
			}
			if st.linked.Load() != 0 {
				t.Error("a failed verification reached the store")
			}
		})
	}
}

func TestGoogleInputIsValidatedBeforeTheTokenIsSpent(t *testing.T) {
	v := &fakeVerifier{id: googleid.Identity{Sub: "s", Email: "a@b.c"}}
	s, _ := newGoogleService(t, &googleStub{}, v)
	ctx := context.Background()
	var ve *ValidationError
	if _, err := s.GoogleSignIn(ctx, "tok", ""); !errors.As(err, &ve) {
		t.Errorf("empty device: %v", err)
	}
	if _, err := s.GoogleJoin(ctx, "tok", "code", "Bob", strings.Repeat("d", 81)); !errors.As(err, &ve) {
		t.Errorf("long device: %v", err)
	}
	if _, err := s.GoogleJoin(ctx, "tok", "code", strings.Repeat("n", 41), "Pixel"); !errors.As(err, &ve) {
		t.Errorf("long explicit name: %v", err)
	}
	if _, err := s.GoogleJoin(ctx, "tok", "code", "bad\x00name", "Pixel"); !errors.As(err, &ve) {
		t.Errorf("control chars in explicit name: %v", err)
	}
	if v.calls.Load() != 0 {
		t.Fatalf("the verifier (and so a nonce) was used %d times for invalid input", v.calls.Load())
	}
}

func TestGoogleJoinDisplayNameDefaults(t *testing.T) {
	cases := []struct {
		name string
		id   googleid.Identity
		in   string
		want string
	}{
		{"given name", googleid.Identity{Sub: "s", Email: "gina@x.io", GivenName: "Gina", Name: "Gina Google"}, "", "Gina"},
		{"explicit wins", googleid.Identity{Sub: "s", Email: "gina@x.io", GivenName: "Gina"}, "  Bob  ", "Bob"},
		{"falls back to name", googleid.Identity{Sub: "s", Email: "gina@x.io", Name: "Gina Google"}, "", "Gina Google"},
		{"falls back to email local part", googleid.Identity{Sub: "s", Email: "gina.g@x.io"}, "   ", "gina.g"},
		{"nothing usable", googleid.Identity{Sub: "s", Email: "@x.io", GivenName: "\x00\u200b \t"}, "", "Listener"},
		{"control and format chars dropped", googleid.Identity{Sub: "s", Email: "a@b.c", GivenName: "Gi\x00na\u202e\n  Lee"}, "", "Gina Lee"},
		{"limited to 40 chars", googleid.Identity{Sub: "s", Email: "a@b.c", GivenName: strings.Repeat("é", 60)}, "", strings.Repeat("é", 40)},
		{"cut does not leave a trailing space", googleid.Identity{Sub: "s", Email: "a@b.c", GivenName: strings.Repeat("a", 39) + " bbb"}, "", strings.Repeat("a", 39)},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			var got string
			st := &googleStub{redeem: func(name string) (Registered, error) { got = name; return okReg(), nil }}
			s, _ := newGoogleService(t, st, &fakeVerifier{id: tc.id})
			if _, err := s.GoogleJoin(context.Background(), "tok", "code", tc.in, "Pixel"); err != nil {
				t.Fatal(err)
			}
			if got != tc.want {
				t.Fatalf("display name = %q, want %q", got, tc.want)
			}
			if _, err := cleanName(got, "display_name", 40); err != nil {
				t.Fatalf("the default %q would not pass cleanName: %v", got, err)
			}
		})
	}
}

func TestGoogleSignInIssuesSessionAndPassesIdentity(t *testing.T) {
	var seen GoogleIdentity
	st := &googleStub{signIn: func(g GoogleIdentity) (Registered, error) { seen = g; return okReg(), nil }}
	s, _ := newGoogleService(t, st, &fakeVerifier{id: googleid.Identity{Sub: "sub-1", Email: "gina@x.io"}})
	sess, err := s.GoogleSignIn(context.Background(), "tok", "  Pixel 9 ")
	if err != nil {
		t.Fatal(err)
	}
	if seen != (GoogleIdentity{Sub: "sub-1", Email: "gina@x.io"}) || st.devName != "Pixel 9" {
		t.Fatalf("store saw %+v / %q", seen, st.devName)
	}
	if sess.AccessToken == "" || sess.RefreshToken == "" || sess.ExpiresIn != 3600 {
		t.Fatalf("session = %+v", sess)
	}
	if c, err := verify(s.secret, sess.AccessToken, s.now()); err != nil || c.Sub != uidA || c.Dev != devA {
		t.Fatalf("access token claims = %+v, %v", c, err)
	}
}

func TestGoogleNotLinkedAndDisabledPassThrough(t *testing.T) {
	for _, want := range []error{ErrGoogleNotLinked, ErrUnauthorized} {
		st := &googleStub{signIn: func(GoogleIdentity) (Registered, error) { return Registered{}, want }}
		s, _ := newGoogleService(t, st, &fakeVerifier{id: googleid.Identity{Sub: "s", Email: "a@b.c"}})
		if _, err := s.GoogleSignIn(context.Background(), "tok", "Pixel"); !errors.Is(err, want) {
			t.Errorf("err = %v, want %v", err, want)
		}
	}
}

// The verification endpoints have a request-rate cap (never a failure lockout).
func TestGoogleRateCap(t *testing.T) {
	st := &googleStub{signIn: func(GoogleIdentity) (Registered, error) { return okReg(), nil }}
	v := &fakeVerifier{err: &googleid.TokenError{Reason: "bad signature"}}
	s, c := newGoogleService(t, st, v)
	ctx := context.Background()
	for i := 0; i < 20; i++ {
		if _, err := s.GoogleSignIn(ctx, "junk", "Pixel"); !errors.Is(err, ErrGoogleToken) {
			t.Fatalf("garbage %d: %v", i, err)
		}
	}
	var le *LimitError
	if _, err := s.GoogleSignIn(ctx, "junk", "Pixel"); !errors.As(err, &le) {
		t.Fatalf("over the cap: %v", err)
	}
	if v.calls.Load() != 20 {
		t.Fatalf("verifier called %d times, want 20 (capped requests are not verified)", v.calls.Load())
	}
	c.Advance(time.Second)
	v.err, v.id = nil, googleid.Identity{Sub: "s", Email: "a@b.c"}
	if _, err := s.GoogleSignIn(ctx, "good", "Pixel"); err != nil {
		t.Fatalf("valid token after the cap window: %v", err)
	}
}

func TestGoogleUnlinkIsIdempotentAndReturnsTheUser(t *testing.T) {
	st := &googleStub{user: User{ID: uidA}}
	s, _ := newGoogleService(t, st, &fakeVerifier{})
	for i := 0; i < 2; i++ {
		u, err := s.GoogleUnlink(context.Background(), Principal{UserID: uidA})
		if err != nil || u.ID != uidA || u.GoogleEmail != nil {
			t.Fatalf("unlink %d: %+v %v", i, u, err)
		}
	}
	if st.unlink.Load() != 2 {
		t.Fatal("unlink did not reach the store")
	}
}
