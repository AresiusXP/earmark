package appapi

import (
	"sync"
	"time"
)

// Defaults of the limits on concurrent provider work (see workGate). A unit of work is one flight
// that talks to a debrid provider: a prepare, a download resolution or a HEAD probe. Callers that
// join a running flight cost nothing.
const (
	DefaultMaxWork       = 32 // in flight at once, all keys together
	DefaultMaxWorkPerKey = 4  // in flight at once for one key fingerprint (a household shares it)

	busyRetryAfter = 5 * time.Second
)

// busyError: too much provider work is in flight (globally or for this key). It answers 429
// rate_limited with Retry-After.
type busyError struct{ retryAfter time.Duration }

func (e *busyError) Error() string { return "appapi: too much provider work in flight" }

// workGate bounds in-flight provider work so a burst of requests (or a slow provider) cannot pile
// up goroutines and sockets: at most `global` units overall and `perKey` per key fingerprint.
type workGate struct {
	global, perKey int

	mu    sync.Mutex
	total int
	byKey map[string]int
}

func newWorkGate(global, perKey int) *workGate {
	if global <= 0 {
		global = DefaultMaxWork
	}
	if perKey <= 0 {
		perKey = DefaultMaxWorkPerKey
	}
	return &workGate{global: global, perKey: perKey, byKey: map[string]int{}}
}

// acquire takes one unit for the fingerprint, or fails with *busyError. The release function is
// idempotent. It never blocks.
func (g *workGate) acquire(fingerprint string) (func(), error) {
	g.mu.Lock()
	defer g.mu.Unlock()
	if g.total >= g.global || g.byKey[fingerprint] >= g.perKey {
		return nil, &busyError{busyRetryAfter}
	}
	g.total++
	g.byKey[fingerprint]++
	var once sync.Once
	return func() {
		once.Do(func() {
			g.mu.Lock()
			defer g.mu.Unlock()
			g.total--
			if g.byKey[fingerprint]--; g.byKey[fingerprint] <= 0 {
				delete(g.byKey, fingerprint)
			}
		})
	}, nil
}

// inUse and inUseBy report the units in use (tests, diagnostics).
func (g *workGate) inUse() int { g.mu.Lock(); defer g.mu.Unlock(); return g.total }
func (g *workGate) inUseBy(fingerprint string) int {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.byKey[fingerprint]
}
