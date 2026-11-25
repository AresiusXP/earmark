package store

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/accounts"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

const acctSecret = "0123456789abcdef0123456789abcdef"

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

type acctEnv struct {
	db  *Postgres
	svc *accounts.Service
	clk *fakeClock
}

func newAcctEnv(t *testing.T) acctEnv {
	t.Helper()
	db := openStore(t)
	clk := &fakeClock{t: time.Now().UTC().Truncate(time.Second)}
	svc, err := accounts.New(accounts.Config{Secret: acctSecret, InstancePassword: "pw", Store: db, Now: clk.Now})
	if err != nil {
		t.Fatal(err)
	}
	return acctEnv{db, svc, clk}
}

func (e acctEnv) bootstrap(t *testing.T) (accounts.Session, accounts.Principal) {
	t.Helper()
	s, err := e.svc.Bootstrap(context.Background(), "pw", "Admin", "Admin phone")
	if err != nil {
		t.Fatal(err)
	}
	return s, e.principal(t, s)
}

func (e acctEnv) principal(t *testing.T, s accounts.Session) accounts.Principal {
	t.Helper()
	p, err := e.svc.Authenticate(context.Background(), s.AccessToken)
	if err != nil {
		t.Fatalf("authenticate: %v", err)
	}
	return p
}

// join makes a plain user through an invite made by admin.
func (e acctEnv) join(t *testing.T, admin accounts.Principal, name string) (accounts.Session, accounts.Principal) {
	t.Helper()
	_, code, err := e.svc.CreateInvite(context.Background(), admin, "", 0)
	if err != nil {
		t.Fatal(err)
	}
	s, err := e.svc.Join(context.Background(), code, name, name+" phone")
	if err != nil {
		t.Fatal(err)
	}
	return s, e.principal(t, s)
}

func TestBootstrapOnlyOnce(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	if ok, err := e.svc.Bootstrapped(ctx); err != nil || ok {
		t.Fatalf("fresh instance: bootstrapped=%v err=%v", ok, err)
	}
	if _, err := e.svc.Bootstrap(ctx, "wrong", "Admin", "phone"); !errors.Is(err, accounts.ErrBadPassword) {
		t.Fatalf("wrong password: %v", err)
	}
	if ok, _ := e.svc.Bootstrapped(ctx); ok {
		t.Fatal("a wrong password created a user")
	}

	const racers = 8
	var wg sync.WaitGroup
	errs := make([]error, racers)
	for i := range racers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, errs[i] = e.svc.Bootstrap(ctx, "pw", fmt.Sprintf("Admin %d", i), "phone")
		}()
	}
	wg.Wait()
	wins := 0
	for _, err := range errs {
		switch {
		case err == nil:
			wins++
		case errors.Is(err, accounts.ErrAlreadyBootstrapped):
		default:
			t.Fatalf("unexpected error: %v", err)
		}
	}
	if wins != 1 {
		t.Fatalf("%d bootstraps succeeded, want exactly 1", wins)
	}
	var users, devices int
	_ = e.db.pool.QueryRow(ctx, `SELECT (SELECT count(*) FROM users), (SELECT count(*) FROM devices)`).Scan(&users, &devices)
	if users != 1 || devices != 1 {
		t.Fatalf("users=%d devices=%d, want 1 and 1", users, devices)
	}
	if _, err := e.svc.Bootstrap(ctx, "pw", "Again", "phone"); !errors.Is(err, accounts.ErrAlreadyBootstrapped) {
		t.Fatalf("second bootstrap: %v", err)
	}
	if ok, _ := e.svc.Bootstrapped(ctx); !ok {
		t.Fatal("not bootstrapped after bootstrap")
	}
}

func TestInviteSingleUseAndExpiry(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)

	inv, code, err := e.svc.CreateInvite(ctx, admin, "for Bob", 0)
	if err != nil {
		t.Fatal(err)
	}
	if inv.Note != "for Bob" || inv.ExpiresAt.Sub(inv.CreatedAt) != accounts.DefaultInviteTTL {
		t.Fatalf("invite = %+v", inv)
	}
	// stored as a hash only
	var n int
	_ = e.db.pool.QueryRow(ctx, `SELECT count(*) FROM invites WHERE code_hash = $1 OR convert_from(code_hash, 'SQL_ASCII') LIKE '%' || $2 || '%'`,
		[]byte(code), strings.ReplaceAll(code, "-", "")).Scan(&n)
	if n != 0 {
		t.Fatal("the invite code is stored in clear")
	}
	var hashLen int
	_ = e.db.pool.QueryRow(ctx, `SELECT length(code_hash) FROM invites WHERE id = $1::uuid`, inv.ID).Scan(&hashLen)
	if hashLen != 32 {
		t.Fatalf("code_hash length = %d, want a SHA-256", hashLen)
	}

	// concurrent redemption: exactly one wins
	const racers = 6
	var wg sync.WaitGroup
	results := make([]error, racers)
	for i := range racers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, results[i] = e.svc.Join(ctx, code, fmt.Sprintf("Bob %d", i), "phone")
		}()
	}
	wg.Wait()
	wins := 0
	for _, err := range results {
		switch {
		case err == nil:
			wins++
		case errors.Is(err, accounts.ErrInvalidCode), errors.Is(err, accounts.ErrTooManyAttempts):
		default:
			t.Fatalf("unexpected: %v", err)
		}
	}
	if wins != 1 {
		t.Fatalf("%d joins succeeded with one invite, want 1", wins)
	}
	e.clk.Advance(2 * time.Minute) // clear the failure limiter
	if _, err := e.svc.Join(ctx, code, "Mallory", "phone"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("reusing a used invite: %v", err)
	}
	if invs, _ := e.svc.ListInvites(ctx, admin); len(invs) != 0 {
		t.Fatalf("a used invite is still listed: %+v", invs)
	}
	var usedBy *string
	_ = e.db.pool.QueryRow(ctx, `SELECT used_by::text FROM invites WHERE id = $1::uuid`, inv.ID).Scan(&usedBy)
	if usedBy == nil {
		t.Fatal("used_by not recorded")
	}

	// expiry
	_, code2, _ := e.svc.CreateInvite(ctx, admin, "", time.Hour)
	e.clk.Advance(time.Hour)
	if _, err := e.svc.Join(ctx, code2, "Late", "phone"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("expired invite: %v", err)
	}
	if invs, _ := e.svc.ListInvites(ctx, admin); len(invs) != 0 {
		t.Fatalf("expired invite listed: %+v", invs)
	}

	// revoked; revoking twice and an unknown id are fine
	inv3, code3, _ := e.svc.CreateInvite(ctx, admin, "", 0)
	for range 2 {
		if err := e.svc.RevokeInvite(ctx, admin, inv3.ID); err != nil {
			t.Fatal(err)
		}
	}
	if err := e.svc.RevokeInvite(ctx, admin, "not-a-uuid"); err != nil {
		t.Fatal(err)
	}
	e.clk.Advance(2 * time.Minute)
	if _, err := e.svc.Join(ctx, code3, "Revoked", "phone"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("revoked invite: %v", err)
	}

	// an invite code is not a link code
	_, code4, _ := e.svc.CreateInvite(ctx, admin, "", 0)
	e.clk.Advance(2 * time.Minute)
	if _, err := e.svc.Link(ctx, code4, "phone"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("invite used as link code: %v", err)
	}

	// the new user is a plain user with default settings
	s, err := e.svc.Join(ctx, code4, "Carol", "phone")
	if err != nil {
		t.Fatal(err)
	}
	if s.User.Role != accounts.RoleUser || s.User.Settings.SourceMode != "auto" || s.User.Settings.P2PEnabled ||
		s.User.Settings.Region != "us" || len(s.User.Settings.Languages) != 1 {
		t.Fatalf("new user = %+v", s.User)
	}
}

func TestRefreshRotation(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	s1, _ := e.bootstrap(t)

	// only a hash is stored
	var n int
	_ = e.db.pool.QueryRow(ctx, `SELECT count(*) FROM devices WHERE convert_from(refresh_hash, 'SQL_ASCII') LIKE '%' || $1 || '%' OR length(refresh_hash) <> 32`, s1.RefreshToken[:10]).Scan(&n)
	if n != 0 {
		t.Fatal("refresh token stored in clear or not hashed")
	}

	s2, err := e.svc.Refresh(ctx, s1.RefreshToken)
	if err != nil {
		t.Fatal(err)
	}
	if s2.RefreshToken == s1.RefreshToken || s2.AccessToken == "" {
		t.Fatal("refresh token not rotated")
	}
	if _, err := e.svc.Refresh(ctx, s1.RefreshToken); !errors.Is(err, accounts.ErrBadRefresh) {
		t.Fatalf("reusing a rotated refresh token: %v", err)
	}
	s3, err := e.svc.Refresh(ctx, s2.RefreshToken)
	if err != nil {
		t.Fatalf("the newest token must still work: %v", err)
	}
	if s3.User.ID != s1.User.ID {
		t.Fatal("refresh changed the user")
	}
	for _, bad := range []string{"", "x", strings.Repeat("A", 43)} {
		if _, err := e.svc.Refresh(ctx, bad); !errors.Is(err, accounts.ErrBadRefresh) {
			t.Fatalf("Refresh(%q): %v", bad, err)
		}
	}

	// concurrent use of one token: exactly one rotation succeeds
	const racers = 6
	var wg sync.WaitGroup
	res := make([]error, racers)
	for i := range racers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, res[i] = e.svc.Refresh(ctx, s3.RefreshToken)
		}()
	}
	wg.Wait()
	wins := 0
	for _, err := range res {
		if err == nil {
			wins++
		}
	}
	if wins != 1 {
		t.Fatalf("%d concurrent refreshes succeeded, want 1", wins)
	}
}

func TestDeviceRevocationAndDisableBlockAccess(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	adminSess, admin := e.bootstrap(t)
	bobSess, bob := e.join(t, admin, "Bob")

	// Bob links a second device.
	code, exp, err := e.svc.CreateLinkCode(ctx, bob)
	if err != nil {
		t.Fatal(err)
	}
	if exp.Sub(e.clk.Now()) != accounts.LinkCodeTTL {
		t.Fatalf("link code expires in %v", exp.Sub(e.clk.Now()))
	}
	tabSess, err := e.svc.Link(ctx, code, "Tablet")
	if err != nil {
		t.Fatal(err)
	}
	tab := e.principal(t, tabSess)
	if tab.UserID != bob.UserID || tab.DeviceID == bob.DeviceID {
		t.Fatalf("linked device: %+v vs %+v", tab, bob)
	}
	if _, err := e.svc.Link(ctx, code, "Again"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("link code reuse: %v", err)
	}

	devs, _ := e.svc.Devices(ctx, bob)
	if len(devs) != 2 {
		t.Fatalf("devices = %+v", devs)
	}
	for _, d := range devs {
		if d.Current != (d.ID == bob.DeviceID) {
			t.Fatalf("current flag wrong: %+v", devs)
		}
	}

	// Revoke the tablet: its tokens die at once, the phone is untouched.
	if err := e.svc.RevokeDevice(ctx, bob, tab.DeviceID); err != nil {
		t.Fatal(err)
	}
	if _, err := e.svc.Authenticate(ctx, tabSess.AccessToken); err == nil {
		t.Fatal("revoked device's access token still works")
	}
	if _, err := e.svc.Refresh(ctx, tabSess.RefreshToken); !errors.Is(err, accounts.ErrBadRefresh) {
		t.Fatalf("revoked device refreshed: %v", err)
	}
	if err := e.svc.RevokeDevice(ctx, bob, tab.DeviceID); err != nil { // idempotent
		t.Fatalf("second revoke: %v", err)
	}
	if _, err := e.svc.Authenticate(ctx, bobSess.AccessToken); err != nil {
		t.Fatalf("other device broke: %v", err)
	}
	if devs, _ := e.svc.Devices(ctx, bob); len(devs) != 1 {
		t.Fatalf("revoked device still listed: %+v", devs)
	}

	// Admin disables Bob: access and refresh stop, link codes stop; re-enabling restores.
	code2, _, _ := e.svc.CreateLinkCode(ctx, bob)
	if _, err := e.svc.SetDisabled(ctx, admin, bob.UserID, true); err != nil {
		t.Fatal(err)
	}
	if _, err := e.svc.Authenticate(ctx, bobSess.AccessToken); err == nil {
		t.Fatal("disabled user's access token still works")
	}
	if _, err := e.svc.Refresh(ctx, bobSess.RefreshToken); !errors.Is(err, accounts.ErrBadRefresh) {
		t.Fatalf("disabled user refreshed: %v", err)
	}
	if _, err := e.svc.Link(ctx, code2, "Sneaky"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("disabled user linked a device: %v", err)
	}
	users, _ := e.svc.ListUsers(ctx, admin)
	for _, u := range users {
		if u.ID == bob.UserID && !u.Disabled {
			t.Fatal("list does not show the user as disabled")
		}
	}
	if _, err := e.svc.SetDisabled(ctx, admin, bob.UserID, false); err != nil {
		t.Fatal(err)
	}
	if _, err := e.svc.Authenticate(ctx, bobSess.AccessToken); err != nil {
		t.Fatalf("re-enabled user locked out: %v", err)
	}
	if _, err := e.svc.Refresh(ctx, bobSess.RefreshToken); err != nil {
		t.Fatalf("re-enabled user cannot refresh: %v", err)
	}

	// protections
	if _, err := e.svc.SetDisabled(ctx, admin, admin.UserID, true); !errors.Is(err, accounts.ErrCannotDisableSelf) {
		t.Fatalf("self disable: %v", err)
	}
	if _, err := e.db.SetDisabled(ctx, bob.UserID, admin.UserID, true); !errors.Is(err, accounts.ErrLastAdmin) {
		t.Fatalf("disabling the last admin: %v", err)
	}
	if _, err := e.svc.SetDisabled(ctx, admin, "00000000-0000-4000-8000-000000000000", true); !errors.Is(err, accounts.ErrNotFound) {
		t.Fatalf("unknown user: %v", err)
	}
	if _, err := e.svc.SetDisabled(ctx, bob, admin.UserID, true); !errors.Is(err, accounts.ErrForbidden) {
		t.Fatalf("non-admin disabling: %v", err)
	}
	if _, err := e.svc.Authenticate(ctx, adminSess.AccessToken); err != nil {
		t.Fatalf("admin locked out: %v", err)
	}
}

func TestLastAdminProtectedUnderRace(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	s1, a1 := e.bootstrap(t)
	// a second admin (there is no API for it: promote through SQL)
	_, a2u := e.join(t, a1, "Second")
	if _, err := e.db.pool.Exec(ctx, `UPDATE users SET role = 'admin' WHERE id = $1::uuid`, a2u.UserID); err != nil {
		t.Fatal(err)
	}
	_ = s1

	var wg sync.WaitGroup
	res := make([]error, 2)
	for i, pair := range [][2]string{{a1.UserID, a2u.UserID}, {a2u.UserID, a1.UserID}} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, res[i] = e.db.SetDisabled(ctx, pair[0], pair[1], true)
		}()
	}
	wg.Wait()
	ok := 0
	for _, err := range res {
		if err == nil {
			ok++
		} else if !errors.Is(err, accounts.ErrLastAdmin) {
			t.Fatalf("unexpected: %v", err)
		}
	}
	if ok != 1 {
		t.Fatalf("%d admins disabled each other, want exactly one to win", ok)
	}
	var enabled int
	_ = e.db.pool.QueryRow(ctx, `SELECT count(*) FROM users WHERE role = 'admin' AND NOT disabled`).Scan(&enabled)
	if enabled != 1 {
		t.Fatalf("%d enabled admins left, want 1", enabled)
	}
}

func TestLinkCodeExpiryAndReplacement(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	_, bob := e.join(t, admin, "Bob")

	code, _, _ := e.svc.CreateLinkCode(ctx, bob)
	var n int
	_ = e.db.pool.QueryRow(ctx, `SELECT count(*) FROM link_codes WHERE code_hash = $1`, []byte(code)).Scan(&n)
	if n != 0 {
		t.Fatal("link code stored in clear")
	}
	// a newer code replaces the older unused one
	code2, _, _ := e.svc.CreateLinkCode(ctx, bob)
	if _, err := e.svc.Link(ctx, code, "Old"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("replaced link code: %v", err)
	}
	e.clk.Advance(accounts.LinkCodeTTL)
	if _, err := e.svc.Link(ctx, code2, "Late"); !errors.Is(err, accounts.ErrInvalidCode) {
		t.Fatalf("expired link code: %v", err)
	}
	// codes are per user: Bob's code gives a device of Bob, never of the admin
	code3, _, _ := e.svc.CreateLinkCode(ctx, bob)
	s, err := e.svc.Link(ctx, code3, "Tablet")
	if err != nil {
		t.Fatal(err)
	}
	if s.User.ID != bob.UserID {
		t.Fatalf("link code signed in user %s, want %s", s.User.ID, bob.UserID)
	}
}

func TestUpdateMePersistsAndValidates(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	_, bob := e.join(t, admin, "Bob")

	rd := "realdebrid"
	langs := []string{"nl", "en"}
	mode, region, fmtMp3, p2p := "p2p", "nl", "mp3", true
	name := "Bobby"
	u, err := e.svc.UpdateMe(ctx, bob.UserID, &name, accounts.SettingsPatch{
		Region: &region, Languages: &langs, PreferFormat: &fmtMp3, SourceMode: &mode, P2PEnabled: &p2p,
		PreferredProvider: accounts.Nullable[string]{Set: true, Value: &rd},
	})
	if err != nil {
		t.Fatal(err)
	}
	got, _ := e.svc.Me(ctx, bob.UserID)
	if got.DisplayName != "Bobby" || got.Settings.Region != "nl" || got.Settings.SourceMode != "p2p" || !got.Settings.P2PEnabled ||
		got.Settings.PreferredProvider == nil || *got.Settings.PreferredProvider != "realdebrid" || got.Settings.Languages[0] != "nl" ||
		got.Settings.PreferFormat != "mp3" || u.DisplayName != got.DisplayName {
		t.Fatalf("settings after update = %+v", got)
	}

	// an invalid patch changes nothing, even its valid half
	bad := "mars"
	name2 := "Ghost"
	if _, err := e.svc.UpdateMe(ctx, bob.UserID, &name2, accounts.SettingsPatch{Region: &bad}); err == nil {
		t.Fatal("invalid region accepted")
	}
	if got2, _ := e.svc.Me(ctx, bob.UserID); got2.DisplayName != "Bobby" {
		t.Fatalf("partial update applied: %+v", got2)
	}
	// null clears the provider
	if u, err := e.svc.UpdateMe(ctx, bob.UserID, nil, accounts.SettingsPatch{PreferredProvider: accounts.Nullable[string]{Set: true}}); err != nil || u.Settings.PreferredProvider != nil {
		t.Fatalf("clear provider: %+v, %v", u, err)
	}
	// the admin's settings were not touched
	if a, _ := e.svc.Me(ctx, admin.UserID); a.Settings.Region != "us" || a.DisplayName != "Admin" {
		t.Fatalf("another user's profile changed: %+v", a)
	}

	// concurrent patches of different fields do not lose each other's update
	var wg sync.WaitGroup
	reg, p2pOff := "de", false
	for _, p := range []accounts.SettingsPatch{{Region: &reg}, {P2PEnabled: &p2pOff}} {
		wg.Add(1)
		go func() { defer wg.Done(); _, _ = e.svc.UpdateMe(ctx, bob.UserID, nil, p) }()
	}
	wg.Wait()
	if got, _ := e.svc.Me(ctx, bob.UserID); got.Settings.Region != "de" || got.Settings.P2PEnabled {
		t.Fatalf("lost update: %+v", got.Settings)
	}
}

// ---- per-user isolation ----------------------------------------------------------------------

type isoProvider struct {
	mu  sync.Mutex
	err error
}

func (p *isoProvider) Name() string { return "torbox" }
func (p *isoProvider) Validate(context.Context, string) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.err
}
func (p *isoProvider) CheckCached(context.Context, string, []string) (map[string]debrid.Cached, error) {
	return nil, nil
}
func (p *isoProvider) Resolve(context.Context, string, debrid.ResolveRequest) (debrid.ResolveResult, error) {
	return debrid.ResolveResult{}, nil
}

func TestUserCannotTouchAnotherUsersKeysOrDevices(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	aliceSess, alice := e.join(t, admin, "Alice")
	bobSess, bob := e.join(t, admin, "Bob")
	keys, err := userkeys.New(userkeys.Config{Secret: acctSecret, Store: e.db, Providers: debrid.NewRegistry(&isoProvider{}),
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatal(err)
	}

	const aliceKey = "alice-torbox-key-1234567"
	if _, err := keys.Put(ctx, alice.UserID, "torbox", aliceKey); err != nil {
		t.Fatal(err)
	}

	// -- keys: Bob sees nothing of Alice's, at every level
	if _, err := keys.GetForUse(ctx, bob.UserID, "torbox"); !errors.Is(err, userkeys.ErrNoKey) {
		t.Fatalf("Bob reads Alice's key: %v", err)
	}
	if _, found, _ := e.db.GetKey(ctx, bob.UserID, "torbox"); found {
		t.Fatal("store.GetKey(bob) returned a row")
	}
	if recs, _ := e.db.ListKeys(ctx, bob.UserID); len(recs) != 0 {
		t.Fatalf("store.ListKeys(bob) = %+v", recs)
	}
	if ok, _ := e.db.SetState(ctx, bob.UserID, "torbox", "invalid", nil, "x"); ok {
		t.Fatal("Bob changed the state of Alice's key")
	}
	if err := keys.Delete(ctx, bob.UserID, "torbox"); err != nil {
		t.Fatal(err)
	}
	if _, err := keys.Recheck(ctx, bob.UserID, "torbox"); !errors.Is(err, userkeys.ErrNoKey) {
		t.Fatalf("Bob rechecked Alice's key: %v", err)
	}
	if k, err := keys.GetForUse(ctx, alice.UserID, "torbox"); err != nil || k.APIKey() != aliceKey {
		t.Fatalf("Alice's key damaged by Bob's calls: %v", err)
	}
	if sts, _ := keys.List(ctx, bob.UserID); sts[0].Configured {
		t.Fatalf("Bob's list shows a key: %+v", sts)
	}

	// Bob writes his own: Alice's is untouched, and a different key is not "shared".
	if _, err := keys.Put(ctx, bob.UserID, "torbox", "bob-torbox-key-7654321"); err != nil {
		t.Fatal(err)
	}
	if k, _ := keys.GetForUse(ctx, alice.UserID, "torbox"); k.APIKey() != aliceKey {
		t.Fatal("Bob's Put overwrote Alice's key")
	}
	if sts, _ := keys.List(ctx, alice.UserID); sts[0].SharedWithOthers {
		t.Fatal("different keys reported as shared")
	}
	// Same key in a household: shared_with_others on both sides, still two separate rows.
	if _, err := keys.Put(ctx, bob.UserID, "torbox", aliceKey); err != nil {
		t.Fatal(err)
	}
	for _, id := range []string{alice.UserID, bob.UserID} {
		sts, _ := keys.List(ctx, id)
		if !sts[0].SharedWithOthers {
			t.Fatalf("user %s: same key not reported as shared", id)
		}
	}
	// Alice removing hers leaves Bob's (and the flag goes away).
	if err := keys.Delete(ctx, alice.UserID, "torbox"); err != nil {
		t.Fatal(err)
	}
	if k, err := keys.GetForUse(ctx, bob.UserID, "torbox"); err != nil || k.APIKey() != aliceKey {
		t.Fatalf("Bob lost his key: %v", err)
	}
	if sts, _ := keys.List(ctx, bob.UserID); sts[0].SharedWithOthers {
		t.Fatal("shared flag stuck after the other holder deleted")
	}

	// sealed at rest: no plaintext anywhere in the table, the fingerprint is not the key
	var leaks int
	_ = e.db.pool.QueryRow(ctx, `SELECT count(*) FROM user_debrid_keys WHERE sealed_key LIKE '%torbox-key%'
		OR fingerprint LIKE '%torbox-key%' OR last_error LIKE '%torbox-key%'`).Scan(&leaks)
	if leaks != 0 {
		t.Fatal("a debrid key is stored in clear")
	}

	// the admin view says who has a key, never the key
	users, _ := e.svc.ListUsers(ctx, admin)
	for _, u := range users {
		want := u.ID == bob.UserID
		if u.Debrid["torbox"] != want {
			t.Fatalf("admin list: %s has key = %v, want %v", u.DisplayName, u.Debrid["torbox"], want)
		}
	}

	// -- devices: Alice cannot list or revoke Bob's
	devs, _ := e.svc.Devices(ctx, alice)
	if len(devs) != 1 || devs[0].ID != alice.DeviceID {
		t.Fatalf("Alice's devices = %+v", devs)
	}
	if err := e.svc.RevokeDevice(ctx, alice, bob.DeviceID); !errors.Is(err, accounts.ErrNotFound) {
		t.Fatalf("Alice revoking Bob's device: %v", err)
	}
	if err := e.db.RevokeDevice(ctx, alice.UserID, bob.DeviceID, e.clk.Now()); !errors.Is(err, accounts.ErrNotFound) {
		t.Fatalf("store: Alice revoking Bob's device: %v", err)
	}
	if err := e.svc.RevokeDevice(ctx, alice, "00000000-0000-4000-8000-000000000000"); !errors.Is(err, accounts.ErrNotFound) {
		t.Fatalf("unknown device: %v", err)
	}
	if _, err := e.svc.Authenticate(ctx, bobSess.AccessToken); err != nil {
		t.Fatalf("Bob's device was revoked by Alice: %v", err)
	}
	// a token whose device belongs to someone else is not a login
	if _, ok, _ := e.db.AuthState(ctx, alice.UserID, bob.DeviceID, e.clk.Now()); ok {
		t.Fatal("AuthState accepted Alice with Bob's device")
	}
	// link codes and profile too
	if _, err := e.svc.Me(ctx, alice.UserID); err != nil {
		t.Fatal(err)
	}
	_ = aliceSess

	// -- not an admin: no admin API
	if _, err := e.svc.ListUsers(ctx, alice); !errors.Is(err, accounts.ErrForbidden) {
		t.Fatalf("user listing users: %v", err)
	}
}

func TestAccountsMigrationConstraints(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	for name, q := range map[string]string{
		"role":       `INSERT INTO users (display_name, role) VALUES ('x', 'root')`,
		"source":     `INSERT INTO users (display_name, role, source_mode) VALUES ('x', 'user', 'torrent')`,
		"languages":  `INSERT INTO users (display_name, role, languages) VALUES ('x', 'user', '{}')`,
		"language":   `INSERT INTO users (display_name, role, languages) VALUES ('x', 'user', '{fr}')`,
		"region":     `INSERT INTO users (display_name, role, region) VALUES ('x', 'user', 'zz')`,
		"empty name": `INSERT INTO users (display_name, role) VALUES ('', 'user')`,
	} {
		if _, err := e.db.pool.Exec(ctx, q); err == nil {
			t.Errorf("%s: invalid row accepted", name)
		}
	}
	if _, err := e.db.pool.Exec(ctx, `INSERT INTO user_debrid_keys (user_id, provider, sealed_key, fingerprint, status)
		VALUES (gen_random_uuid(), 'torbox', 's', 'f', 'ok')`); err == nil {
		t.Error("a key for a user that does not exist was accepted")
	}
}
