package appapi

import (
	"fmt"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
)

func TestBudgetMapIsCappedAndNeverEvictsActiveHistory(t *testing.T) {
	clk := &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}
	b := newBudget(2, clk.Now)
	b.maxKeys = 3
	for i := range 3 {
		if ok, _ := b.spend(fmt.Sprint("fp", i)); !ok {
			t.Fatalf("fp%d refused below the cap", i)
		}
		clk.Advance(time.Minute)
	}
	// Full of active fingerprints: a new one is refused until the first slot (fp0, +1 h) frees up.
	ok, after := b.spend("newcomer")
	if ok || after != 57*time.Minute {
		t.Fatalf("newcomer: ok=%v after=%v, want refused for 57m", ok, after)
	}
	if len(b.hits) != 3 {
		t.Fatalf("map grew to %d", len(b.hits))
	}
	// Nobody was evicted: fp0 still has its add on record, so its budget did not reset.
	if b.used("fp0") != 1 {
		t.Fatalf("fp0 history lost: used = %d", b.used("fp0"))
	}
	if ok, _ := b.spend("fp1"); !ok {
		t.Fatal("a known fingerprint must keep spending")
	}
	if ok, _ := b.spend("fp1"); ok {
		t.Fatal("fp1 exceeded its own budget")
	}
	clk.Advance(58 * time.Minute) // fp0 left the window: its slot is free again
	if ok, _ := b.spend("newcomer"); !ok {
		t.Fatal("a freed slot must admit a new fingerprint")
	}
	if len(b.hits) > 3 {
		t.Fatalf("map grew to %d", len(b.hits))
	}
}

func TestCooldownMapIsCappedAndNeverEvictsRunningCooldowns(t *testing.T) {
	clk := &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}
	tr := newTracker(clk.Now)
	tr.maxCool = 2
	for i := range 2 {
		if _, ok := tr.setCooldown(fmt.Sprint("fp", i), "realdebrid", time.Duration(i+1)*time.Minute); !ok {
			t.Fatalf("fp%d refused below the cap", i)
		}
	}
	d, ok := tr.setCooldown("newcomer", "realdebrid", time.Minute)
	if ok || d != time.Minute {
		t.Fatalf("newcomer: ok=%v d=%v, want refused until the first cooldown ends (1m)", ok, d)
	}
	if tr.coolingDown("fp0", "realdebrid") == 0 || tr.coolingDown("fp1", "realdebrid") == 0 {
		t.Fatal("a running cooldown was evicted")
	}
	if _, ok := tr.setCooldown("fp0", "realdebrid", time.Minute); !ok {
		t.Fatal("a known key may extend its own cooldown")
	}
	clk.Advance(2*time.Minute + time.Second)
	if _, ok := tr.setCooldown("newcomer", "realdebrid", time.Minute); !ok {
		t.Fatal("expired cooldowns must make room")
	}
}

func TestTrackerStatesEvictOnlyTheOldest(t *testing.T) {
	clk := &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}
	tr := newTracker(clk.Now)
	tr.maxStates = 3
	for i := range 3 {
		tr.update(prepKey{"fp", "realdebrid", fmt.Sprint(i)}, func(s *prepState) { s.inAccount = true })
		clk.Advance(time.Second)
	}
	tr.update(prepKey{"fp", "realdebrid", "new"}, func(s *prepState) { s.inAccount = true })
	if len(tr.m) != 3 {
		t.Fatalf("states = %d, want 3", len(tr.m))
	}
	if tr.get(prepKey{"fp", "realdebrid", "0"}).inAccount {
		t.Fatal("the oldest state should have gone")
	}
	for _, h := range []string{"1", "2", "new"} {
		if !tr.get(prepKey{"fp", "realdebrid", h}).inAccount {
			t.Fatalf("state %s was dropped; only the least recently used may go", h)
		}
	}
}

func TestPrepareRefusesNewFingerprintsWhenTheTablesAreFull(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	e.addRelease(h40('a'))

	// Budget table full of other, active fingerprints.
	e.app.bud.maxKeys = 1
	if ok, _ := e.app.bud.spend("someone-else"); !ok {
		t.Fatal("setup")
	}
	r := e.prepare("u1", h40('a'), "realdebrid", nil)
	r.errCode(t, 429, "rate_limited")
	if r.Header().Get("Retry-After") == "" || e.rd.calls() != 0 {
		t.Fatalf("Retry-After=%q provider calls=%d", r.Header().Get("Retry-After"), e.rd.calls())
	}

	// Cooldown table full: a provider rate limit of a new key cannot be remembered, so it is a 429 too.
	e2 := newRelEnv(t, 5)
	e2.setKey("u1", "realdebrid", rdKey1)
	e2.addRelease(h40('a'))
	e2.app.trk.maxCool = 1
	e2.app.trk.setCooldown("someone-else", "realdebrid", time.Minute)
	e2.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
		return debrid.ResolveResult{}, debrid.ErrRateLimited
	}
	e2.prepare("u1", h40('a'), "realdebrid", nil).errCode(t, 429, "rate_limited")
	if e2.app.bud.used(e2.fp("u1", "realdebrid")) != 0 {
		t.Fatal("the add was rejected before anything was added: the budget slot must be refunded")
	}
}
