package appapi

import (
	"strings"
	"testing"
	"time"
)

func TestLinkAllowlist(t *testing.T) {
	l := NewLinkAllowlist(nil)
	cases := []struct {
		provider, link string
		ok             bool
	}{
		{"torbox", "https://nexus-12.weur.tb-cdn.st/dl/abc?token=x", true},
		{"torbox", "https://tb-cdn.st/x", true},
		{"torbox", "https://store-3.torbox.app/x", true},
		{"torbox", "https://NEXUS.TB-CDN.ST/x", true},
		{"torbox", "https://nexus.tb-cdn.st:443/x", true},
		{"torbox", "https://tb-cdn.st.evil.com/x", false},
		{"torbox", "https://evil-tb-cdn.st/x", false},
		{"torbox", "https://nexus.tb-cdn.st.evil.com/x", false},
		{"torbox", "http://nexus.tb-cdn.st/x", false},
		{"torbox", "https://nexus.tb-cdn.st:8080/x", false},
		{"torbox", "https://user:pw@nexus.tb-cdn.st/x", false},
		{"torbox", "https://tb-cdn.st@evil.com/x", false},
		{"torbox", `https://tb-cdn.st\@evil.com/x`, false},
		{"torbox", "https://nexus.tb-cdn.st./x", false},
		{"torbox", "https://127.0.0.1/x", false},
		{"torbox", "https://[::1]/x", false},
		{"torbox", "//nexus.tb-cdn.st/x", false},
		{"torbox", "https://nexus.tb-cdn.st/x y", false},
		{"torbox", "https://nexus.tb-cdn.st/x\r\nSet-Cookie: a=b", false},
		{"torbox", "https://nexus.tb-cdn.st/\x00", false},
		{"torbox", "https://22-4.download.real-debrid.com/x", false}, // another provider's host
		{"torbox", "", false},
		{"torbox", "https://nexus.tb-cdn.st/" + strings.Repeat("a", 5000), false},
		{"realdebrid", "https://22-4.download.real-debrid.com/d/ABC", true},
		{"realdebrid", "https://real-debrid.com/d/ABC", true},
		{"realdebrid", "https://abc.real-debrid.cloud/d/ABC", true},
		{"realdebrid", "https://x.rdb.so/d/ABC", true},
		{"realdebrid", "https://real-debrid.com.evil.com/x", false},
		{"realdebrid", "https://fakerdb.so/x", false},
		{"realdebrid", "http://x.rdb.so/x", false},
		{"realdebrid", "https://nexus.tb-cdn.st/x", false},
		{"premiumize", "https://nexus.tb-cdn.st/x", false}, // unknown provider: nothing is allowed
	}
	for _, c := range cases {
		got, err := l.Check(c.provider, c.link)
		if (err == nil) != c.ok {
			t.Errorf("%s %q: err = %v, want ok=%v", c.provider, c.link, err, c.ok)
		}
		if err != nil && got != "" {
			t.Errorf("%q: a refused link is returned", c.link)
		}
		if err != nil && strings.Contains(err.Error(), "tb-cdn") {
			t.Errorf("the error must not echo the link: %v", err)
		}
	}
}

func TestLinkAllowlistExtraHosts(t *testing.T) {
	extra, err := ParseLinkHosts("realdebrid:new-cdn.example, torbox:TB-NEW.example")
	if err != nil {
		t.Fatal(err)
	}
	l := NewLinkAllowlist(extra)
	for _, link := range []string{"https://a.new-cdn.example/x", "https://new-cdn.example/x"} {
		if _, err := l.Check("realdebrid", link); err != nil {
			t.Errorf("%s: %v", link, err)
		}
		if _, err := l.Check("torbox", link); err == nil {
			t.Errorf("%s: extra hosts are per provider", link)
		}
	}
	if _, err := l.Check("torbox", "https://a.tb-new.example/x"); err != nil {
		t.Errorf("extra suffix is lowercased: %v", err)
	}
	if _, err := NewLinkAllowlist(nil).Check("realdebrid", "https://a.new-cdn.example/x"); err == nil {
		t.Error("extras must not leak into the built-in table")
	}
	for _, bad := range []string{"realdebrid", "realdebrid:", "realdebrid:com", "premiumize:x.example", "realdebrid:.x.example", "realdebrid:x.example/", "realdebrid:*.x.example"} {
		if _, err := ParseLinkHosts(bad); err == nil {
			t.Errorf("%q accepted", bad)
		}
	}
	if m, err := ParseLinkHosts(""); err != nil || len(m) != 0 {
		t.Errorf("empty: %v %v", m, err)
	}
}

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
	if d := tr.setCooldown("fp", "realdebrid", 0); d != defaultCooldown {
		t.Fatalf("default = %v", d)
	}
	if d := tr.setCooldown("fp", "realdebrid", time.Hour); d != maxCooldown {
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
