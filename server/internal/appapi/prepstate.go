package appapi

import (
	"sync"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

// In-memory, per key fingerprint state of the release routes. A household that shares a key
// shares these limits like it shares the debrid account. Everything is bounded and lost on restart.

const (
	// DefaultAddsPerHour is the default budget of cold torrent adds per key fingerprint and hour
	// (env PREPARE_ADDS_PER_HOUR). Adding is the expensive, provider-limited action; polling a
	// torrent that is already queued costs nothing from it.
	DefaultAddsPerHour = 30

	stateTTL         = 30 * time.Minute // how long a torrent is remembered as "in the caller's account"
	readyTTL         = 5 * time.Minute  // a ready answer is reused for polls without asking the provider
	failedTTL        = 10 * time.Minute // a failed torrent is not retried (nor re-added) sooner
	defaultCooldown  = time.Minute
	maxCooldown      = 10 * time.Minute
	maxTrackedStates = 4096
	maxBudgetKeys    = 4096
)

// budget is a sliding one-hour window of cold adds per key fingerprint.
type budget struct {
	max int
	now func() time.Time

	mu   sync.Mutex
	hits map[string][]time.Time
}

func newBudget(max int, now func() time.Time) *budget {
	return &budget{max: max, now: now, hits: map[string][]time.Time{}}
}

// spend takes one add; when the window is full it returns false and when the oldest add leaves it.
func (b *budget) spend(fingerprint string) (ok bool, retryAfter time.Duration) {
	now := b.now()
	b.mu.Lock()
	defer b.mu.Unlock()
	hs := b.prune(fingerprint, now)
	if len(hs) >= b.max {
		return false, max(hs[0].Add(time.Hour).Sub(now), time.Second)
	}
	if _, known := b.hits[fingerprint]; !known && len(b.hits) >= maxBudgetKeys {
		for k := range b.hits {
			if len(b.prune(k, now)) == 0 {
				delete(b.hits, k)
			}
		}
	}
	b.hits[fingerprint] = append(hs, now)
	return true, 0
}

// refund gives back the most recent add (the add did not happen).
func (b *budget) refund(fingerprint string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	if hs := b.hits[fingerprint]; len(hs) > 0 {
		b.hits[fingerprint] = hs[:len(hs)-1]
	}
}

func (b *budget) prune(fingerprint string, now time.Time) []time.Time {
	hs := b.hits[fingerprint]
	i := 0
	for i < len(hs) && !now.Before(hs[i].Add(time.Hour)) {
		i++
	}
	hs = hs[i:]
	if len(hs) == 0 {
		delete(b.hits, fingerprint)
		return nil
	}
	b.hits[fingerprint] = hs
	return hs
}

// used is how many adds the fingerprint has in the window (tests, diagnostics).
func (b *budget) used(fingerprint string) int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.prune(fingerprint, b.now()))
}

type prepKey struct{ fingerprint, provider, hash string }

// prepState is what is known about one torrent in one key's account.
type prepState struct {
	inAccount  bool // added by us (or seen there): polling it never adds again
	readyUntil time.Time
	files      []model.File
	failedAt   time.Time
	failReason string
	seen       time.Time
}

type tracker struct {
	now func() time.Time

	mu sync.Mutex
	m  map[prepKey]*prepState
	// cooldown blocks a (fingerprint, provider) after the provider rate limited the key.
	cool map[[2]string]time.Time
}

func newTracker(now func() time.Time) *tracker {
	return &tracker{now: now, m: map[prepKey]*prepState{}, cool: map[[2]string]time.Time{}}
}

// get returns a copy of the live state (zero value when unknown or expired).
func (t *tracker) get(k prepKey) prepState {
	t.mu.Lock()
	defer t.mu.Unlock()
	s, ok := t.m[k]
	if !ok {
		return prepState{}
	}
	if t.now().Sub(s.seen) > stateTTL {
		delete(t.m, k)
		return prepState{}
	}
	return *s
}

func (t *tracker) update(k prepKey, fn func(*prepState)) {
	now := t.now()
	t.mu.Lock()
	defer t.mu.Unlock()
	s, ok := t.m[k]
	if !ok {
		if len(t.m) >= maxTrackedStates {
			for kk, ss := range t.m {
				if now.Sub(ss.seen) > stateTTL {
					delete(t.m, kk)
				}
			}
			if len(t.m) >= maxTrackedStates {
				clear(t.m)
			}
		}
		s = &prepState{}
		t.m[k] = s
	}
	fn(s)
	s.seen = now
}

func (t *tracker) forget(k prepKey) {
	t.mu.Lock()
	delete(t.m, k)
	t.mu.Unlock()
}

// coolingDown reports how long the key stays blocked at the provider (0 = not blocked).
func (t *tracker) coolingDown(fingerprint, provider string) time.Duration {
	t.mu.Lock()
	defer t.mu.Unlock()
	k := [2]string{fingerprint, provider}
	until, ok := t.cool[k]
	if !ok {
		return 0
	}
	if left := until.Sub(t.now()); left > 0 {
		return left
	}
	delete(t.cool, k)
	return 0
}

func (t *tracker) setCooldown(fingerprint, provider string, d time.Duration) time.Duration {
	if d <= 0 {
		d = defaultCooldown
	}
	d = min(d, maxCooldown)
	t.mu.Lock()
	defer t.mu.Unlock()
	if len(t.cool) >= maxBudgetKeys {
		now := t.now()
		for k, u := range t.cool {
			if !now.Before(u) {
				delete(t.cool, k)
			}
		}
	}
	t.cool[[2]string{fingerprint, provider}] = t.now().Add(d)
	return d
}
