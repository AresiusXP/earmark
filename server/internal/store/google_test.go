package store

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/accounts"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/googleid/googleidtest"
)

type googleEnv struct {
	acctEnv
	g *googleidtest.Google
}

// newGoogleEnv is an acctEnv whose Service has Sign in with Google switched on (a real
// googleid.Verifier against a fake JWKS, on the env's fake clock).
func newGoogleEnv(t *testing.T) googleEnv {
	t.Helper()
	e := newAcctEnv(t)
	g := googleidtest.New(t, acctSecret, e.clk.Now)
	svc, err := accounts.New(accounts.Config{Secret: acctSecret, InstancePassword: "pw", Store: e.db, Google: g.V, Now: e.clk.Now})
	if err != nil {
		t.Fatal(err)
	}
	e.svc = svc
	return googleEnv{e, g}
}

func (e googleEnv) token(t *testing.T, sub, email string) string {
	t.Helper()
	return e.g.Token(t, sub, email, nil)
}

func (e googleEnv) count(t *testing.T, query string, args ...any) int {
	t.Helper()
	var n int
	if err := e.db.pool.QueryRow(context.Background(), query, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func (e googleEnv) invite(t *testing.T, admin accounts.Principal) string {
	t.Helper()
	_, code, err := e.svc.CreateInvite(context.Background(), admin, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	return code
}

func TestGoogleLinkSignInUnlink(t *testing.T) {
	e := newGoogleEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t) // the first admin comes from the instance password, then links Google

	if u, _ := e.svc.Me(ctx, admin.UserID); u.GoogleEmail != nil {
		t.Fatal("a fresh user already has Google linked")
	}
	u, err := e.svc.GoogleLink(ctx, admin, e.token(t, "sub-admin", "admin@example.com"))
	if err != nil {
		t.Fatal(err)
	}
	if u.GoogleEmail == nil || *u.GoogleEmail != "admin@example.com" {
		t.Fatalf("after link: %+v", u.GoogleEmail)
	}

	// a new device signs in with Google as the same user
	tok := e.token(t, "sub-admin", "admin@example.com")
	s, err := e.svc.GoogleSignIn(ctx, tok, "Tablet")
	if err != nil {
		t.Fatal(err)
	}
	if s.User.ID != admin.UserID || s.User.Role != accounts.RoleAdmin || s.User.GoogleEmail == nil {
		t.Fatalf("session user = %+v", s.User)
	}
	p := e.principal(t, s)
	if p.DeviceID == admin.DeviceID {
		t.Fatal("Google sign-in reused a device")
	}
	devs, _ := e.svc.Devices(ctx, p)
	if len(devs) != 2 {
		t.Fatalf("devices = %d, want the original and the new one", len(devs))
	}
	// the refresh token of that device works like any other
	if _, err := e.svc.Refresh(ctx, s.RefreshToken); err != nil {
		t.Fatalf("refresh of a Google-signed-in device: %v", err)
	}
	// an ID token (nonce) cannot be replayed
	if _, err := e.svc.GoogleSignIn(ctx, tok, "Tablet"); !errors.Is(err, accounts.ErrGoogleToken) {
		t.Fatalf("replayed token: %v", err)
	}
	if n := e.count(t, `SELECT count(*) FROM devices WHERE user_id = $1::uuid`, admin.UserID); n != 2 {
		t.Fatalf("devices in the table = %d", n)
	}

	// the email follows Google
	if _, err := e.svc.GoogleSignIn(ctx, e.token(t, "sub-admin", "renamed@example.com"), "Tablet 2"); err != nil {
		t.Fatal(err)
	}
	if u, _ := e.svc.Me(ctx, admin.UserID); *u.GoogleEmail != "renamed@example.com" {
		t.Fatalf("email = %v", *u.GoogleEmail)
	}

	// unlink: Google no longer signs in; devices and link codes keep working; unlink twice is fine
	for i := 0; i < 2; i++ {
		u, err := e.svc.GoogleUnlink(ctx, admin)
		if err != nil || u.GoogleEmail != nil {
			t.Fatalf("unlink %d: %+v %v", i, u.GoogleEmail, err)
		}
	}
	if _, err := e.svc.GoogleSignIn(ctx, e.token(t, "sub-admin", "admin@example.com"), "x"); !errors.Is(err, accounts.ErrGoogleNotLinked) {
		t.Fatalf("sign-in after unlink: %v", err)
	}
	if _, err := e.svc.Authenticate(ctx, s.AccessToken); err != nil {
		t.Fatalf("existing device after unlink: %v", err)
	}
	if _, _, err := e.svc.CreateLinkCode(ctx, admin); err != nil {
		t.Fatal(err)
	}
	// and it can be linked again
	if _, err := e.svc.GoogleLink(ctx, admin, e.token(t, "sub-admin", "admin@example.com")); err != nil {
		t.Fatal(err)
	}
}

func TestGoogleNeverCreatesAnAccountByItself(t *testing.T) {
	e := newGoogleEnv(t)
	ctx := context.Background()

	// empty instance: unknown identity cannot sign in, and join needs a real invite
	if _, err := e.svc.GoogleSignIn(ctx, e.token(t, "stranger", "s@example.com"), "Pixel"); !errors.Is(err, accounts.ErrGoogleNotLinked) {
		t.Fatalf("sign-in of an unknown identity: %v", err)
	}
	if _, err := e.svc.GoogleJoin(ctx, e.token(t, "stranger", "s@example.com"), "NOPE-NOPE", "", "Pixel"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("join with a bogus invite: %v", err)
	}
	if n := e.count(t, `SELECT count(*) FROM users`); n != 0 {
		t.Fatalf("%d users exist; Google created an account on its own", n)
	}
	if n := e.count(t, `SELECT count(*) FROM google_identities`); n != 0 {
		t.Fatalf("%d identities exist", n)
	}

	// with users present too
	_, admin := e.bootstrap(t)
	if _, err := e.svc.GoogleSignIn(ctx, e.token(t, "stranger", "s@example.com"), "Pixel"); !errors.Is(err, accounts.ErrGoogleNotLinked) {
		t.Fatalf("sign-in of an unknown identity: %v", err)
	}
	if n := e.count(t, `SELECT count(*) FROM users`); n != 1 {
		t.Fatalf("users = %d", n)
	}
	// an invite code of the wrong kind (a link code) is no invite
	lc, _, _ := e.svc.CreateLinkCode(ctx, admin)
	if _, err := e.svc.GoogleJoin(ctx, e.token(t, "stranger", "s@example.com"), lc, "", "Pixel"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("join with a link code: %v", err)
	}
}

func TestGoogleJoinWithInvite(t *testing.T) {
	e := newGoogleEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	code := e.invite(t, admin)

	s, err := e.svc.GoogleJoin(ctx, e.token(t, "sub-gina", "gina@example.com"), code, "", "Pixel")
	if err != nil {
		t.Fatal(err)
	}
	if s.User.DisplayName != "Gina" || s.User.Role != accounts.RoleUser || s.User.GoogleEmail == nil || *s.User.GoogleEmail != "gina@example.com" {
		t.Fatalf("new user = %+v", s.User)
	}
	e.principal(t, s)
	if n := e.count(t, `SELECT count(*) FROM invites WHERE used_by = $1::uuid`, s.User.ID); n != 1 {
		t.Fatal("invite not marked as used by the new user")
	}
	// the invite is single use, also for Google joins
	if _, err := e.svc.GoogleJoin(ctx, e.token(t, "sub-other", "o@example.com"), code, "", "Pixel"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("reusing the invite: %v", err)
	}
	if _, err := e.svc.Join(ctx, code, "Mallory", "Pixel"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("reusing the invite with the plain join: %v", err)
	}
	// the new user signs in with Google from another device
	if s2, err := e.svc.GoogleSignIn(ctx, e.token(t, "sub-gina", "gina@example.com"), "Tablet"); err != nil || s2.User.ID != s.User.ID {
		t.Fatalf("sign-in: %+v %v", s2.User, err)
	}

	// an explicit display name wins; a long default is cut
	s3, err := e.svc.GoogleJoin(ctx, e.g.Token(t, "sub-bob", "bob@example.com", nil), e.invite(t, admin), "Bobby", "Pixel")
	if err != nil || s3.User.DisplayName != "Bobby" {
		t.Fatalf("explicit name: %+v %v", s3.User, err)
	}
	long := e.g.Token(t, "sub-long", "l@example.com", func(c map[string]any) { c["given_name"] = strings.Repeat("Z", 100) })
	s4, err := e.svc.GoogleJoin(ctx, long, e.invite(t, admin), "", "Pixel")
	if err != nil || len([]rune(s4.User.DisplayName)) != 40 {
		t.Fatalf("long given name: %q %v", s4.User.DisplayName, err)
	}
}

// When the identity is already linked, the join fails as a whole: no user, and the invite stays
// unused so the admin's code is not burned by somebody's second Google account attempt.
func TestGoogleJoinWithTakenIdentityKeepsTheInvite(t *testing.T) {
	e := newGoogleEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	if _, err := e.svc.GoogleLink(ctx, admin, e.token(t, "sub-admin", "admin@example.com")); err != nil {
		t.Fatal(err)
	}
	code := e.invite(t, admin)
	users := e.count(t, `SELECT count(*) FROM users`)

	if _, err := e.svc.GoogleJoin(ctx, e.token(t, "sub-admin", "admin@example.com"), code, "", "Pixel"); !errors.Is(err, accounts.ErrGoogleTaken) {
		t.Fatalf("join with a taken identity: %v", err)
	}
	if n := e.count(t, `SELECT count(*) FROM users`); n != users {
		t.Fatal("a user was created although the join failed")
	}
	if invs, _ := e.svc.ListInvites(ctx, admin); len(invs) != 1 {
		t.Fatalf("the invite was consumed: %+v", invs)
	}
	if _, err := e.svc.GoogleJoin(ctx, e.token(t, "sub-fresh", "f@example.com"), code, "", "Pixel"); err != nil {
		t.Fatalf("the invite works with a different identity: %v", err)
	}
}

func TestGoogleConcurrentJoinsOneInvite(t *testing.T) {
	e := newGoogleEnv(t)
	_, admin := e.bootstrap(t)
	code := e.invite(t, admin)
	const racers = 6
	tokens := make([]string, racers)
	for i := range tokens {
		tokens[i] = e.token(t, fmt.Sprintf("sub-%d", i), fmt.Sprintf("u%d@example.com", i))
	}
	var wg sync.WaitGroup
	res := make([]error, racers)
	for i := range racers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, res[i] = e.svc.GoogleJoin(context.Background(), tokens[i], code, "", "Pixel")
		}()
	}
	wg.Wait()
	wins := 0
	for _, err := range res {
		switch {
		case err == nil:
			wins++
		case errors.Is(err, accounts.ErrInvalidCode):
		default:
			t.Fatalf("unexpected: %v", err)
		}
	}
	if wins != 1 {
		t.Fatalf("%d joins won with one invite, want 1", wins)
	}
	if n := e.count(t, `SELECT count(*) FROM users`); n != 2 {
		t.Fatalf("users = %d, want admin + one", n)
	}
	if n := e.count(t, `SELECT count(*) FROM google_identities`); n != 1 {
		t.Fatalf("identities = %d, want 1", n)
	}
}

func TestGoogleIdentityBelongsToOneUser(t *testing.T) {
	e := newGoogleEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	_, bob := e.join(t, admin, "Bob")

	if _, err := e.svc.GoogleLink(ctx, admin, e.token(t, "sub-shared", "shared@example.com")); err != nil {
		t.Fatal(err)
	}
	// Bob cannot take the identity (409 in the API)
	if _, err := e.svc.GoogleLink(ctx, bob, e.token(t, "sub-shared", "shared@example.com")); !errors.Is(err, accounts.ErrGoogleTaken) {
		t.Fatalf("Bob linking Alice's identity: %v", err)
	}
	// the admin linking the same identity again is a no-op; a different one needs an unlink first
	if _, err := e.svc.GoogleLink(ctx, admin, e.token(t, "sub-shared", "shared@example.com")); err != nil {
		t.Fatalf("relinking the same identity: %v", err)
	}
	if _, err := e.svc.GoogleLink(ctx, admin, e.token(t, "sub-second", "second@example.com")); !errors.Is(err, accounts.ErrGoogleAlreadyLinked) {
		t.Fatalf("a second identity for one user: %v", err)
	}
	if n := e.count(t, `SELECT count(*) FROM google_identities WHERE sub = 'sub-shared'`); n != 1 {
		t.Fatalf("rows for the shared sub = %d", n)
	}
	// Bob's own identity; neither sees the other's email
	ub, err := e.svc.GoogleLink(ctx, bob, e.token(t, "sub-bob", "bob@example.com"))
	if err != nil || *ub.GoogleEmail != "bob@example.com" {
		t.Fatalf("Bob: %+v %v", ub.GoogleEmail, err)
	}
	if ua, _ := e.svc.Me(ctx, admin.UserID); *ua.GoogleEmail != "shared@example.com" {
		t.Fatalf("Alice's Me shows %v", *ua.GoogleEmail)
	}
	// unlinking is scoped to the caller
	if _, err := e.svc.GoogleUnlink(ctx, bob); err != nil {
		t.Fatal(err)
	}
	if ua, _ := e.svc.Me(ctx, admin.UserID); ua.GoogleEmail == nil {
		t.Fatal("Bob's unlink removed Alice's identity")
	}
	if s, err := e.svc.GoogleSignIn(ctx, e.token(t, "sub-shared", "shared@example.com"), "x"); err != nil || s.User.ID != admin.UserID {
		t.Fatalf("Alice's identity no longer signs her in: %v", err)
	}
	// once Alice unlinks, the identity is free for Bob (and no longer signs Alice in)
	if _, err := e.svc.GoogleUnlink(ctx, admin); err != nil {
		t.Fatal(err)
	}
	if _, err := e.svc.GoogleLink(ctx, bob, e.token(t, "sub-shared", "shared@example.com")); err != nil {
		t.Fatalf("Bob taking a released identity: %v", err)
	}
	if s, err := e.svc.GoogleSignIn(ctx, e.token(t, "sub-shared", "shared@example.com"), "x"); err != nil || s.User.ID != bob.UserID {
		t.Fatalf("the identity signs in %v, want Bob (%v)", s.User.ID, err)
	}
}

func TestGoogleConcurrentLinksOfOneIdentity(t *testing.T) {
	e := newGoogleEnv(t)
	_, admin := e.bootstrap(t)
	_, bob := e.join(t, admin, "Bob")
	tokens := []string{e.token(t, "sub-race", "r@example.com"), e.token(t, "sub-race", "r@example.com")}
	var wg sync.WaitGroup
	res := make([]error, 2)
	for i, p := range []accounts.Principal{admin, bob} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, res[i] = e.svc.GoogleLink(context.Background(), p, tokens[i])
		}()
	}
	wg.Wait()
	wins := 0
	for _, err := range res {
		switch {
		case err == nil:
			wins++
		case errors.Is(err, accounts.ErrGoogleTaken):
		default:
			t.Fatalf("unexpected: %v", err)
		}
	}
	if wins != 1 {
		t.Fatalf("%d users linked one identity, want 1", wins)
	}
}

func TestGoogleDisabledUserCannotSignIn(t *testing.T) {
	e := newGoogleEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	_, bob := e.join(t, admin, "Bob")
	if _, err := e.svc.GoogleLink(ctx, bob, e.token(t, "sub-bob", "bob@example.com")); err != nil {
		t.Fatal(err)
	}
	if _, err := e.svc.SetDisabled(ctx, admin, bob.UserID, true); err != nil {
		t.Fatal(err)
	}
	devices := e.count(t, `SELECT count(*) FROM devices`)
	if _, err := e.svc.GoogleSignIn(ctx, e.token(t, "sub-bob", "bob@example.com"), "Pixel"); !errors.Is(err, accounts.ErrUnauthorized) {
		t.Fatalf("disabled user signing in with Google: %v", err)
	}
	if n := e.count(t, `SELECT count(*) FROM devices`); n != devices {
		t.Fatal("a device was created for a disabled user")
	}
	if _, err := e.svc.SetDisabled(ctx, admin, bob.UserID, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.svc.GoogleSignIn(ctx, e.token(t, "sub-bob", "bob@example.com"), "Pixel"); err != nil {
		t.Fatalf("re-enabled user: %v", err)
	}
}

func TestGoogleAdminListAndCascade(t *testing.T) {
	e := newGoogleEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	_, bob := e.join(t, admin, "Bob")
	if _, err := e.svc.GoogleLink(ctx, bob, e.token(t, "sub-bob", "bob@example.com")); err != nil {
		t.Fatal(err)
	}
	us, err := e.svc.ListUsers(ctx, admin)
	if err != nil {
		t.Fatal(err)
	}
	for _, u := range us {
		switch u.ID {
		case bob.UserID:
			if u.GoogleEmail == nil || *u.GoogleEmail != "bob@example.com" {
				t.Fatalf("admin list: Bob = %v", u.GoogleEmail)
			}
		default:
			if u.GoogleEmail != nil {
				t.Fatalf("admin list: %s shows %v", u.DisplayName, *u.GoogleEmail)
			}
		}
	}
	// deleting a user takes the identity along (and frees the sub)
	if _, err := e.db.pool.Exec(ctx, `DELETE FROM users WHERE id = $1::uuid`, bob.UserID); err != nil {
		t.Fatal(err)
	}
	if n := e.count(t, `SELECT count(*) FROM google_identities`); n != 0 {
		t.Fatalf("identities left after the user was deleted: %d", n)
	}
}

func TestGoogleMigrationConstraints(t *testing.T) {
	e := newGoogleEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	_, bob := e.join(t, admin, "Bob")
	ins := func(sub, user, email string) error {
		_, err := e.db.pool.Exec(ctx, `INSERT INTO google_identities (sub, user_id, email) VALUES ($1, $2::uuid, $3)`, sub, user, email)
		return err
	}
	if err := ins("s1", admin.UserID, "a@example.com"); err != nil {
		t.Fatal(err)
	}
	for name, err := range map[string]error{
		"same sub, other user": ins("s1", bob.UserID, "b@example.com"),
		"same user, other sub": ins("s2", admin.UserID, "a@example.com"),
		"empty sub":            ins("", bob.UserID, "b@example.com"),
		"empty email":          ins("s3", bob.UserID, ""),
		"unknown user":         ins("s4", "00000000-0000-4000-8000-000000000000", "x@example.com"),
		"sub longer than 255":  ins(strings.Repeat("s", 256), bob.UserID, "b@example.com"),
	} {
		if err == nil {
			t.Errorf("%s: accepted", name)
		}
	}
}
