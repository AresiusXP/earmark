package store

import (
	"context"
	"testing"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

// shared_with_others needs the same provider AND a current-format ("v2:") fingerprint equal.
func TestSharedWithOthersPredicate(t *testing.T) {
	e := newAcctEnv(t)
	ctx := context.Background()
	_, admin := e.bootstrap(t)
	_, bob := e.join(t, admin, "Bob")
	put := func(user, provider, fp string) {
		t.Helper()
		if err := e.db.PutKey(ctx, userkeys.Record{UserID: user, Provider: provider, SealedKey: "v1:x", Fingerprint: fp, State: "ok"}); err != nil {
			t.Fatal(err)
		}
	}
	shared := func(user, provider string) bool {
		t.Helper()
		recs, err := e.db.ListKeys(ctx, user)
		if err != nil {
			t.Fatal(err)
		}
		for _, r := range recs {
			if r.Provider == provider {
				return r.SharedWithOthers
			}
		}
		t.Fatalf("no %s row for %s", provider, user)
		return false
	}

	// equal fingerprints under different providers: not a household
	put(admin.UserID, "torbox", "v2:aaaa")
	put(bob.UserID, "realdebrid", "v2:aaaa")
	if shared(admin.UserID, "torbox") || shared(bob.UserID, "realdebrid") {
		t.Fatal("a fingerprint match across providers was reported as shared")
	}
	// same provider, same current fingerprint: shared on both sides
	put(bob.UserID, "torbox", "v2:aaaa")
	if !shared(admin.UserID, "torbox") || !shared(bob.UserID, "torbox") {
		t.Fatal("same provider and fingerprint not reported as shared")
	}
	// legacy (bare hex) fingerprints are unknown: never shared, even when equal
	put(admin.UserID, "realdebrid", "bbbb")
	put(bob.UserID, "realdebrid", "bbbb")
	if shared(admin.UserID, "realdebrid") || shared(bob.UserID, "realdebrid") {
		t.Fatal("a legacy fingerprint was reported as shared")
	}
}
