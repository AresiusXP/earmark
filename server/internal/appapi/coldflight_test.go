package appapi

import (
	"fmt"
	"sync"
	"testing"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
)

// coldWaiters is how many outer flights wait on the cold-add flight of the key.
func (e *relEnv) coldWaiters(fp, provider, hash string) int {
	e.app.cold.mu.Lock()
	defer e.app.cold.mu.Unlock()
	if c := e.app.cold.m[coldKey(fp, provider, hash)]; c != nil {
		return c.waiters
	}
	return 0
}

// A burst of prepare + downloads of several files of one torrent must add it ONCE and spend ONE
// budget slot, whatever the route or the file index.
func TestConcurrentPrepareAndDownloadsAddOnceAndSpendOnce(t *testing.T) {
	for _, provider := range []string{"realdebrid", "torbox"} {
		t.Run(provider, func(t *testing.T) {
			e := newRelEnv(t, 5)
			e.setKey("u1", provider, map[string]string{"realdebrid": rdKey1, "torbox": tbKey1}[provider])
			h := h40('a')
			e.addRelease(h)
			fp := e.fp("u1", provider)
			prov := e.rd.provBase
			if provider == "torbox" {
				prov = e.tb.provBase
			}
			prov.gate = make(chan struct{})

			var wg sync.WaitGroup
			codes := make([]int, 0, 8)
			var mu sync.Mutex
			record := func(c int) { mu.Lock(); codes = append(codes, c); mu.Unlock() }
			run := func(f func() int) { wg.Add(1); go func() { defer wg.Done(); record(f()) }() }
			outer := 0
			for range 2 { // two prepares are one flight; they must not add either
				run(func() int { return e.prepare("u1", h, provider, nil).Code })
			}
			outer++
			for idx := 1; idx <= 3; idx++ { // distinct files = distinct flights
				run(func() int { return e.download("u1", "GET", h, idx, provider).Code })
				outer++
			}
			waitFor(t, "every route waiting on the cold add", func() bool { return e.coldWaiters(fp, provider, h) == outer })
			if prov.gated() != 1 {
				t.Fatalf("provider add calls started = %d, want 1", prov.gated())
			}
			close(prov.gate)
			wg.Wait()

			for _, c := range codes {
				if c != 200 && c != 302 {
					t.Fatalf("codes = %v", codes)
				}
			}
			if prov.addCount() != 1 {
				t.Fatalf("provider adds = %d, want 1", prov.addCount())
			}
			if used := e.app.bud.used(fp); used != 1 {
				t.Fatalf("budget spent = %d, want 1", used)
			}
		})
	}
}

// The file the add call was made for may be missing from the torrent; that must not fail the
// other files that waited for the same add.
func TestColdFlightLeaderFileNotFoundDoesNotFailOtherFiles(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	h := h40('a')
	e.addRelease(h)
	fp := e.fp("u1", "realdebrid")
	e.rd.gate = make(chan struct{})
	e.rd.answer = func(_ string, req debrid.ResolveRequest, _ bool) (debrid.ResolveResult, error) {
		if req.FileIndex == 9 {
			return debrid.ResolveResult{}, fmt.Errorf("file 9 not in torrent: %w", debrid.ErrNotFound)
		}
		return debrid.ResolveResult{URL: linkFor("realdebrid", "k", req.FileIndex)}, nil
	}
	var wg sync.WaitGroup
	var leader, other int
	wg.Add(2)
	go func() { defer wg.Done(); leader = e.download("u1", "GET", h, 9, "realdebrid").Code }()
	waitFor(t, "leader at the provider", func() bool { return e.rd.gated() == 1 })
	go func() { defer wg.Done(); other = e.download("u1", "GET", h, 1, "realdebrid").Code }()
	waitFor(t, "second file waiting", func() bool { return e.coldWaiters(fp, "realdebrid", h) == 2 })
	close(e.rd.gate)
	wg.Wait()
	if leader != 404 || other != 302 {
		t.Fatalf("leader=%d other=%d, want 404 and 302", leader, other)
	}
	if e.rd.addCount() != 1 || e.app.bud.used(fp) != 1 {
		t.Fatalf("adds=%d budget=%d", e.rd.addCount(), e.app.bud.used(fp))
	}
}

// A provider rate limit seen by the add is ONE call and one cooldown for everybody who waited.
func TestColdFlightFailureIsSharedByTheWaiters(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	h := h40('a')
	e.addRelease(h)
	fp := e.fp("u1", "realdebrid")
	e.rd.gate = make(chan struct{})
	e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
		return debrid.ResolveResult{}, debrid.ErrRateLimited
	}
	var wg sync.WaitGroup
	codes := make([]int, 3)
	for i := range codes {
		wg.Add(1)
		go func() { defer wg.Done(); codes[i] = e.download("u1", "GET", h, i+1, "realdebrid").Code }()
	}
	waitFor(t, "waiters", func() bool { return e.coldWaiters(fp, "realdebrid", h) == 3 })
	close(e.rd.gate)
	wg.Wait()
	for _, c := range codes {
		if c != 202 {
			t.Fatalf("codes = %v", codes)
		}
	}
	if e.rd.calls() != 1 {
		t.Fatalf("provider calls = %d, want 1", e.rd.calls())
	}
	if e.app.bud.used(fp) != 0 {
		t.Fatalf("a rejected add must not cost budget, used = %d", e.app.bud.used(fp))
	}
	if e.app.trk.coolingDown(fp, "realdebrid") == 0 {
		t.Fatal("no cooldown recorded")
	}
}

// Once the torrent is in the account nobody enters the cold flight any more.
func TestKnownTorrentSkipsTheColdFlight(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	h := h40('a')
	e.addRelease(h)
	e.download("u1", "GET", h, 1, "realdebrid").status(t, 302)
	e.download("u1", "GET", h, 2, "realdebrid").status(t, 302)
	e.prepare("u1", h, "realdebrid", nil).status(t, 200)
	if e.rd.addCount() != 1 || e.app.bud.used(e.fp("u1", "realdebrid")) != 1 {
		t.Fatalf("adds=%d", e.rd.addCount())
	}
}
