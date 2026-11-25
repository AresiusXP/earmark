package appapi

import (
	"testing"
	"time"
)

func TestLinkCache(t *testing.T) {
	clk := &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}
	c := newLinkCache(clk.Now)
	const link = "https://nexus.tb-cdn.st/x"
	c.put("fpA", "torbox", "h", 1, link)

	if got, ok := c.get("fpA", "torbox", "h", 1); !ok || got != link {
		t.Fatal("miss right after put")
	}
	for _, miss := range [][4]any{{"fpB", "torbox", "h", 1}, {"fpA", "realdebrid", "h", 1}, {"fpA", "torbox", "g", 1}, {"fpA", "torbox", "h", 2}} {
		if _, ok := c.get(miss[0].(string), miss[1].(string), miss[2].(string), miss[3].(int)); ok {
			t.Errorf("hit for %v: the key is (fingerprint, provider, hash, index)", miss)
		}
	}
	// A fingerprint that merely starts like another one must not be evicted with it.
	c.put("fpAB", "torbox", "h", 1, link)
	c.evict("fpA")
	if _, ok := c.get("fpA", "torbox", "h", 1); ok {
		t.Error("evict left an entry")
	}
	if _, ok := c.get("fpAB", "torbox", "h", 1); !ok {
		t.Error("evict removed another fingerprint's entry")
	}

	// TTL: ten minutes.
	c.put("fpA", "torbox", "h", 1, link)
	clk.Advance(defaultLinkTTL - time.Second)
	if _, ok := c.get("fpA", "torbox", "h", 1); !ok {
		t.Error("expired too early")
	}
	clk.Advance(2 * time.Second)
	if _, ok := c.get("fpA", "torbox", "h", 1); ok {
		t.Error("served past the TTL")
	}
	if len(c.m) != 1 { // the expired entry was dropped on read, fpAB remains
		t.Errorf("entries = %d", len(c.m))
	}
}

func TestLinkTTLNeverExceedsTheProviderCap(t *testing.T) {
	if linkTTL("torbox") != defaultLinkTTL || linkTTL("unknown") != defaultLinkTTL {
		t.Fatal("default TTL is 10 minutes")
	}
	old := maxLinkTTL["realdebrid"]
	maxLinkTTL["realdebrid"] = time.Minute
	defer func() { maxLinkTTL["realdebrid"] = old }()
	if linkTTL("realdebrid") != time.Minute {
		t.Fatal("a provider cap below the default wins")
	}
	clk := &clock{t: time.Now()}
	c := newLinkCache(clk.Now)
	c.put("fp", "realdebrid", "h", 0, "https://x.rdb.so/y")
	clk.Advance(61 * time.Second)
	if _, ok := c.get("fp", "realdebrid", "h", 0); ok {
		t.Fatal("cached beyond the provider cap")
	}
}

func TestLinkCacheIsBounded(t *testing.T) {
	clk := &clock{t: time.Now()}
	c := newLinkCache(clk.Now)
	for i := 0; i < maxCachedLinks*2; i++ {
		c.put("fp", "torbox", "h", i, "https://x.tb-cdn.st/y")
	}
	if len(c.m) > maxCachedLinks {
		t.Fatalf("entries = %d, max %d", len(c.m), maxCachedLinks)
	}
}

func TestBudgetSlidingWindow(t *testing.T) {
	clk := &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}
	b := newBudget(3, clk.Now)
	for i := 0; i < 3; i++ {
		if ok, _ := b.spend("fp"); !ok {
			t.Fatalf("spend %d refused", i)
		}
		clk.Advance(10 * time.Minute)
	}
	ok, after := b.spend("fp")
	if ok || after != 30*time.Minute {
		t.Fatalf("4th spend: ok=%v after=%v (the oldest add leaves the window in 30 min)", ok, after)
	}
	if ok, _ := b.spend("other"); !ok {
		t.Fatal("budgets are per fingerprint")
	}
	clk.Advance(30 * time.Minute)
	if ok, _ := b.spend("fp"); !ok {
		t.Fatal("window did not slide")
	}
	if b.used("fp") != 3 {
		t.Fatalf("used = %d before refund", b.used("fp"))
	}
	b.refund("fp")
	if b.used("fp") != 2 {
		t.Fatalf("used = %d", b.used("fp"))
	}
}

func TestTrackerCooldownAndExpiry(t *testing.T) {
	clk := &clock{t: time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)}
	tr := newTracker(clk.Now)
	if tr.coolingDown("fp", "realdebrid") != 0 {
		t.Fatal("cooling by default")
	}
	if d, ok := tr.setCooldown("fp", "realdebrid", 0); !ok || d != defaultCooldown {
		t.Fatalf("default = %v", d)
	}
	if d, ok := tr.setCooldown("fp", "realdebrid", time.Hour); !ok || d != maxCooldown {
		t.Fatalf("capped = %v", d)
	}
	if tr.coolingDown("fp", "torbox") != 0 || tr.coolingDown("other", "realdebrid") != 0 {
		t.Fatal("cooldown is per fingerprint and provider")
	}
	clk.Advance(maxCooldown + time.Second)
	if tr.coolingDown("fp", "realdebrid") != 0 {
		t.Fatal("cooldown did not end")
	}

	k := prepKey{"fp", "realdebrid", "h"}
	tr.update(k, func(s *prepState) { s.inAccount = true })
	if !tr.get(k).inAccount {
		t.Fatal("state lost")
	}
	clk.Advance(stateTTL + time.Second)
	if tr.get(k).inAccount {
		t.Fatal("state outlived its TTL")
	}
}
