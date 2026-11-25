package userkeys

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/AresiusXP/earmark/server/internal/debrid"
)

const testSecret = "0123456789abcdef0123456789abcdef"

func TestSealer(t *testing.T) {
	s, err := NewSealer(testSecret)
	if err != nil {
		t.Fatal(err)
	}
	const user, prov, key = "user-a", "torbox", "tb_supersecretkey123"
	sealed, err := s.Seal(user, prov, key)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(sealed, key) || !strings.HasPrefix(sealed, "v1:") {
		t.Fatalf("sealed value %q", sealed)
	}
	if got, err := s.Open(user, prov, sealed); err != nil || got != key {
		t.Fatalf("Open = %q, %v", got, err)
	}
	again, _ := s.Seal(user, prov, key)
	if again == sealed {
		t.Fatal("sealing twice gave the same output (nonce reuse)")
	}

	other, _ := NewSealer(strings.Repeat("z", 32))
	flip := func(v string) string {
		b := []byte(v)
		b[len(b)-3] ^= 1
		return string(b)
	}
	tests := []struct {
		name string
		open func() (string, error)
	}{
		{"wrong secret", func() (string, error) { return other.Open(user, prov, sealed) }},
		{"another user's row", func() (string, error) { return s.Open("user-b", prov, sealed) }},
		{"another provider's row", func() (string, error) { return s.Open(user, "realdebrid", sealed) }},
		{"tampered", func() (string, error) { return s.Open(user, prov, flip(sealed)) }},
		{"truncated", func() (string, error) { return s.Open(user, prov, sealed[:len(sealed)-8]) }},
		{"no prefix", func() (string, error) { return s.Open(user, prov, strings.TrimPrefix(sealed, "v1:")) }},
		{"not base64", func() (string, error) { return s.Open(user, prov, "v1:!!!") }},
		{"empty", func() (string, error) { return s.Open(user, prov, "") }},
		{"prefix only", func() (string, error) { return s.Open(user, prov, "v1:") }},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := tc.open()
			if !errors.Is(err, ErrInvalidSealed) || got != "" {
				t.Fatalf("Open = %q, %v", got, err)
			}
		})
	}
	if _, err := NewSealer("short"); err == nil {
		t.Fatal("short secret accepted")
	}
}

func TestFingerprint(t *testing.T) {
	a, _ := NewSealer(testSecret)
	b, _ := NewSealer(testSecret)
	c, _ := NewSealer(strings.Repeat("z", 32))
	if a.Fingerprint("torbox", "key-one-1234") != b.Fingerprint("torbox", "key-one-1234") {
		t.Fatal("fingerprint is not deterministic")
	}
	if a.Fingerprint("torbox", "key-one-1234") == a.Fingerprint("torbox", "key-two-1234") {
		t.Fatal("different keys share a fingerprint")
	}
	if a.Fingerprint("torbox", "key-one-1234") == c.Fingerprint("torbox", "key-one-1234") {
		t.Fatal("fingerprint does not depend on the server secret")
	}
	if a.Fingerprint("torbox", "key-one-1234") == a.Fingerprint("realdebrid", "key-one-1234") {
		t.Fatal("the same string under two providers shares a fingerprint")
	}
	// the provider/key boundary is unambiguous
	if a.Fingerprint("torbox", "xkey-one-1234") == a.Fingerprint("torboxx", "key-one-1234") {
		t.Fatal("provider and key run together in the HMAC input")
	}
	fp := a.Fingerprint("torbox", "key-one-1234")
	if !strings.HasPrefix(fp, FingerprintPrefix) || len(fp) != len(FingerprintPrefix)+64 || strings.Contains(fp, "key-one") {
		t.Fatalf("fingerprint %q", fp)
	}
}

func TestSealAndFingerprintKeysAreSeparate(t *testing.T) {
	// The derivation labels differ, so the three keys derived from CONFIG_SECRET (JWT, sealing,
	// fingerprint) are independent. Guard against someone "simplifying" the labels.
	if sealLabel == fingerprintLabel || sealLabel == "audiobooks-jwt-v1" {
		t.Fatal("key labels must differ")
	}
}

// ---- Service ------------------------------------------------------------------------------

type fakeProvider struct {
	name string
	mu   sync.Mutex
	err  error
	seen []string
}

func (f *fakeProvider) Name() string { return f.name }
func (f *fakeProvider) Validate(_ context.Context, key string) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.seen = append(f.seen, key)
	return f.err
}
func (f *fakeProvider) setErr(err error) { f.mu.Lock(); f.err = err; f.mu.Unlock() }
func (f *fakeProvider) CheckCached(context.Context, string, []string) (map[string]debrid.Cached, error) {
	return nil, nil
}
func (f *fakeProvider) Resolve(context.Context, string, debrid.ResolveRequest) (debrid.ResolveResult, error) {
	return debrid.ResolveResult{}, nil
}

type memStore struct {
	mu   sync.Mutex
	rows map[[2]string]Record
}

func newMem() *memStore { return &memStore{rows: map[[2]string]Record{}} }

func (m *memStore) PutKey(_ context.Context, r Record) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.rows[[2]string{r.UserID, r.Provider}] = r
	return nil
}
func (m *memStore) GetKey(_ context.Context, u, p string) (Record, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.rows[[2]string{u, p}]
	return r, ok, nil
}
func (m *memStore) ListKeys(_ context.Context, u string) ([]Record, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []Record{}
	for k, r := range m.rows {
		if k[0] != u {
			continue
		}
		for k2, o := range m.rows {
			if k2[0] != u && k2[1] == k[1] && strings.HasPrefix(r.Fingerprint, FingerprintPrefix) && o.Fingerprint == r.Fingerprint {
				r.SharedWithOthers = true
			}
		}
		out = append(out, r)
	}
	return out, nil
}
func (m *memStore) DeleteKey(_ context.Context, u, p string) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.rows, [2]string{u, p})
	return nil
}
func (m *memStore) SetState(_ context.Context, u, p, state string, at *time.Time, msg string) (bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.rows[[2]string{u, p}]
	if !ok {
		return false, nil
	}
	r.State, r.LastError = state, msg
	if at != nil {
		r.ValidatedAt = at
	}
	m.rows[[2]string{u, p}] = r
	return true, nil
}

func newSvc(t *testing.T) (*Service, *memStore, *fakeProvider) {
	t.Helper()
	st := newMem()
	prov := &fakeProvider{name: "torbox"}
	s, err := New(Config{Secret: testSecret, Store: st, Providers: debrid.NewRegistry(prov, &fakeProvider{name: "realdebrid"}),
		Logger: slog.New(slog.NewTextHandler(&bytes.Buffer{}, nil))})
	if err != nil {
		t.Fatal(err)
	}
	return s, st, prov
}

func TestPut(t *testing.T) {
	ctx := context.Background()
	const key = "tb_supersecretkey123"
	tests := []struct {
		name      string
		provider  string
		key       string
		provErr   error
		wantErr   error
		wantStore bool
	}{
		{"ok", "torbox", key, nil, nil, true},
		{"key is trimmed", "torbox", "  " + key + "\n", nil, nil, true},
		{"provider rejects", "torbox", key, debrid.ErrBadKey, ErrBadKey, false},
		{"provider rejects (wrapped)", "torbox", key, fmt.Errorf("x: %w", debrid.ErrBadKey), ErrBadKey, false},
		{"provider down", "torbox", key, errors.New("dial tcp: connection refused"), ErrUnavailable, false},
		{"provider times out", "torbox", key, context.DeadlineExceeded, ErrUnavailable, false},
		{"provider rate limits", "torbox", key, debrid.ErrRateLimited, ErrUnavailable, false},
		{"unknown provider", "alldebrid", key, nil, ErrUnknownProvider, false},
		{"too short", "torbox", "short", nil, ErrInvalidKey, false},
		{"too long", "torbox", strings.Repeat("k", 257), nil, ErrInvalidKey, false},
		{"inner space", "torbox", "abcd efgh1234", nil, ErrInvalidKey, false},
		{"control char", "torbox", "abcdefgh\x001234", nil, ErrInvalidKey, false},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			s, st, prov := newSvc(t)
			prov.setErr(tc.provErr)
			got, err := s.Put(ctx, "user-a", tc.provider, tc.key)
			if !errors.Is(err, tc.wantErr) {
				t.Fatalf("err = %v, want %v", err, tc.wantErr)
			}
			_, stored, _ := st.GetKey(ctx, "user-a", tc.provider)
			if stored != tc.wantStore {
				t.Fatalf("stored = %v, want %v", stored, tc.wantStore)
			}
			if tc.wantErr == nil {
				if !got.Configured || got.State != StateOK || got.ValidatedAt == nil || got.Provider != "torbox" {
					t.Fatalf("status = %+v", got)
				}
				rec, _, _ := st.GetKey(ctx, "user-a", "torbox")
				if strings.Contains(rec.SealedKey, "supersecret") {
					t.Fatal("key stored in clear")
				}
				if len(prov.seen) != 1 || prov.seen[0] != key {
					t.Fatalf("provider validated %q", prov.seen)
				}
			}
		})
	}
}

func TestPutReplacesOnlyOwnKey(t *testing.T) {
	ctx := context.Background()
	s, _, _ := newSvc(t)
	if _, err := s.Put(ctx, "user-a", "torbox", "key-of-user-a-111"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Put(ctx, "user-b", "torbox", "key-of-user-b-222"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Put(ctx, "user-a", "torbox", "key-of-user-a-333"); err != nil {
		t.Fatal(err)
	}
	a, err := s.GetForUse(ctx, "user-a", "torbox")
	if err != nil || a.APIKey() != "key-of-user-a-333" {
		t.Fatalf("a: %v", err)
	}
	b, err := s.GetForUse(ctx, "user-b", "torbox")
	if err != nil || b.APIKey() != "key-of-user-b-222" {
		t.Fatalf("b: %v", err)
	}
}

func TestGetForUseIsPerUser(t *testing.T) {
	ctx := context.Background()
	s, st, _ := newSvc(t)
	if _, err := s.Put(ctx, "user-a", "torbox", "key-of-user-a-111"); err != nil {
		t.Fatal(err)
	}
	// There is no fallback to somebody else's key.
	if _, err := s.GetForUse(ctx, "user-b", "torbox"); !errors.Is(err, ErrNoKey) {
		t.Fatalf("user b: %v", err)
	}
	if _, err := s.GetForUse(ctx, "user-a", "realdebrid"); !errors.Is(err, ErrNoKey) {
		t.Fatalf("other provider: %v", err)
	}
	if _, err := s.GetForUse(ctx, "user-a", "nope"); !errors.Is(err, ErrUnknownProvider) {
		t.Fatalf("unknown provider: %v", err)
	}
	// A sealed value copied into another user's row cannot be opened there.
	rec, _, _ := st.GetKey(ctx, "user-a", "torbox")
	rec.UserID = "user-b"
	_ = st.PutKey(ctx, rec)
	if _, err := s.GetForUse(ctx, "user-b", "torbox"); !errors.Is(err, ErrUnreadable) {
		t.Fatalf("copied row: %v", err)
	}

	// state handling
	if err := s.MarkInvalid(ctx, "user-a", "torbox"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.GetForUse(ctx, "user-a", "torbox"); !errors.Is(err, ErrBadKey) {
		t.Fatalf("invalid key: %v", err)
	}
	_, _ = st.SetState(ctx, "user-a", "torbox", StateError, nil, "down")
	if k, err := s.GetForUse(ctx, "user-a", "torbox"); err != nil || k.APIKey() != "key-of-user-a-111" {
		t.Fatalf("key in state error must still be usable: %v", err)
	}
}

func TestKeyNeverPrints(t *testing.T) {
	ctx := context.Background()
	s, _, _ := newSvc(t)
	const secret = "key-of-user-a-111"
	if _, err := s.Put(ctx, "user-a", "torbox", secret); err != nil {
		t.Fatal(err)
	}
	k, err := s.GetForUse(ctx, "user-a", "torbox")
	if err != nil {
		t.Fatal(err)
	}
	j, _ := json.Marshal(struct{ K Key }{k})
	var logs bytes.Buffer
	slog.New(slog.NewJSONHandler(&logs, nil)).Info("call", "key", k, "grp", slog.Group("g", "key", k))
	for name, out := range map[string]string{
		"%v": fmt.Sprintf("%v", k), "%+v": fmt.Sprintf("%+v", k), "%#v": fmt.Sprintf("%#v", k), "%s": fmt.Sprintf("%s", k),
		"%q": fmt.Sprintf("%q", k), "ptr": fmt.Sprintf("%+v", &k), "json": string(j), "slog": logs.String(),
	} {
		if strings.Contains(out, "key-of-user") || strings.Contains(out, k.Fingerprint) && k.Fingerprint != "" && name != "ptr" {
			t.Errorf("%s output leaks: %s", name, out)
		}
	}
	if k.APIKey() != secret {
		t.Fatal("APIKey must return the plaintext")
	}
}

func TestListStatusAndShared(t *testing.T) {
	ctx := context.Background()
	s, _, _ := newSvc(t)
	if _, err := s.Put(ctx, "user-a", "torbox", "household-key-123"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Put(ctx, "user-b", "torbox", "household-key-123"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Put(ctx, "user-c", "torbox", "own-key-of-user-c-1"); err != nil {
		t.Fatal(err)
	}
	for user, want := range map[string]bool{"user-a": true, "user-b": true, "user-c": false} {
		sts, err := s.List(ctx, user)
		if err != nil {
			t.Fatal(err)
		}
		if len(sts) != 2 || sts[0].Provider != "realdebrid" || sts[1].Provider != "torbox" {
			t.Fatalf("%s: statuses = %+v", user, sts)
		}
		if sts[0].Configured || sts[0].State != "" {
			t.Fatalf("%s: realdebrid must be unconfigured: %+v", user, sts[0])
		}
		if !sts[1].Configured || sts[1].SharedWithOthers != want {
			t.Fatalf("%s: torbox = %+v, want shared=%v", user, sts[1], want)
		}
	}
	// the key rows of a user never show up for somebody else
	if sts, _ := s.List(ctx, "user-d"); sts[1].Configured {
		t.Fatal("user d sees a configured key")
	}
}

func TestRecheckAndDelete(t *testing.T) {
	ctx := context.Background()
	s, st, prov := newSvc(t)
	if _, err := s.Recheck(ctx, "user-a", "torbox"); !errors.Is(err, ErrNoKey) {
		t.Fatalf("recheck without key: %v", err)
	}
	if _, err := s.Put(ctx, "user-a", "torbox", "key-of-user-a-111"); err != nil {
		t.Fatal(err)
	}

	prov.setErr(debrid.ErrBadKey)
	got, err := s.Recheck(ctx, "user-a", "torbox")
	if err != nil || got.State != StateInvalid || got.LastError == "" {
		t.Fatalf("rejected key: %+v, %v", got, err)
	}
	prov.setErr(errors.New("boom"))
	got, err = s.Recheck(ctx, "user-a", "torbox")
	if err != nil || got.State != StateError || strings.Contains(got.LastError, "boom") {
		t.Fatalf("outage: %+v, %v", got, err)
	}
	prov.setErr(nil)
	got, err = s.Recheck(ctx, "user-a", "torbox")
	if err != nil || got.State != StateOK || got.LastError != "" {
		t.Fatalf("recovered: %+v, %v", got, err)
	}

	// CONFIG_SECRET rotated: the sealed key cannot be read; the status says so.
	rec, _, _ := st.GetKey(ctx, "user-a", "torbox")
	rec.SealedKey = "v1:AAAA"
	_ = st.PutKey(ctx, rec)
	got, err = s.Recheck(ctx, "user-a", "torbox")
	if err != nil || got.State != StateInvalid {
		t.Fatalf("unreadable: %+v, %v", got, err)
	}

	if err := s.Delete(ctx, "user-b", "torbox"); err != nil { // someone else's: no effect
		t.Fatal(err)
	}
	if _, found, _ := st.GetKey(ctx, "user-a", "torbox"); !found {
		t.Fatal("user b's delete removed user a's key")
	}
	for i := 0; i < 2; i++ { // idempotent
		if err := s.Delete(ctx, "user-a", "torbox"); err != nil {
			t.Fatal(err)
		}
	}
	if _, found, _ := st.GetKey(ctx, "user-a", "torbox"); found {
		t.Fatal("key still there")
	}
}

// A row written with the old fingerprint format (bare hex, no provider) is never "shared"; the next
// read for use or recheck rewrites it, and the key used for budgets is always the current format.
func TestLegacyFingerprintIsHealed(t *testing.T) {
	ctx := context.Background()
	s, st, _ := newSvc(t)
	const key = "tb_supersecretkey123"
	const alice, bob = "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"
	for _, u := range []string{alice, bob} {
		if _, err := s.Put(ctx, u, "torbox", key); err != nil {
			t.Fatal(err)
		}
	}
	legacy := func(u string) {
		st.mu.Lock()
		r := st.rows[[2]string{u, "torbox"}]
		r.Fingerprint = "00ff" // bare hex of the old format
		st.rows[[2]string{u, "torbox"}] = r
		st.mu.Unlock()
	}
	legacy(alice)
	legacy(bob)
	sts, _ := s.List(ctx, alice)
	for _, x := range sts {
		if x.Provider == "torbox" && x.SharedWithOthers {
			t.Fatal("a legacy fingerprint was reported as shared")
		}
	}
	k, err := s.GetForUse(ctx, alice, "torbox")
	if err != nil {
		t.Fatal(err)
	}
	if want := s.sealer.Fingerprint("torbox", key); k.Fingerprint != want {
		t.Fatalf("Key.Fingerprint = %q, want the current format", k.Fingerprint)
	}
	if rec, _, _ := st.GetKey(ctx, alice, "torbox"); rec.Fingerprint != k.Fingerprint {
		t.Fatal("GetForUse did not heal the stored fingerprint")
	}
	if _, err := s.Recheck(ctx, bob, "torbox"); err != nil {
		t.Fatal(err)
	}
	if rec, _, _ := st.GetKey(ctx, bob, "torbox"); rec.Fingerprint != k.Fingerprint {
		t.Fatal("Recheck did not heal the stored fingerprint")
	}
	sts, _ = s.List(ctx, alice)
	for _, x := range sts {
		if x.Provider == "torbox" && !x.SharedWithOthers {
			t.Fatal("healed rows of one household are not shared")
		}
	}
}

// The same string held as a TorBox key by one user and a Real-Debrid key by another is not
// a household.
func TestSameKeyOnTwoProvidersIsNotShared(t *testing.T) {
	ctx := context.Background()
	s, _, _ := newSvc(t)
	const key = "identical_key_1234"
	const alice, bob = "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222"
	if _, err := s.Put(ctx, alice, "torbox", key); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Put(ctx, bob, "realdebrid", key); err != nil {
		t.Fatal(err)
	}
	for _, u := range []string{alice, bob} {
		sts, _ := s.List(ctx, u)
		for _, x := range sts {
			if x.SharedWithOthers {
				t.Fatalf("%s/%s reported as shared", u, x.Provider)
			}
		}
	}
}
