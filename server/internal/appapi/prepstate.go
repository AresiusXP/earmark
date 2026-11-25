package appapi

import (
	"sync"
	"time"

	"github.com/AresiusXP/earmark/server/internal/model"
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
	// maxCooldownKeys bounds the cooldown map like the budget map. Both are hard caps: when every
	// slot belongs to a still-active fingerprint, a NEW fingerprint is refused (429 rate_limited)
	// instead of evicting someone's active history.
	maxCooldownKeys = 4096
)

// budget is a sliding one-hour window of cold adds per key fingerprint.
type budget struct {
	max     int
	maxKeys int // distinct fingerprints tracked at once (tests lower it)
	now     func() time.Time

	mu   sync.Mutex
	hits map[string][]time.Time
}

func newBudget(max int, now func() time.Time) *budget {
	return &budget{max: max, maxKeys: maxBudgetKeys, now: now, hits: map[string][]time.Time{}}
}

// spend takes one add; when the window is full it returns false and when the oldest add leaves it.
// The map of fingerprints is capped: when it is full of fingerprints with adds still in their
// window, a new fingerprint is refused (false, retry when the first slot frees up) rather than
// evicting an active one, which would hand that key a fresh budget.
func (b *budget) spend(fingerprint string) (ok bool, retryAfter time.Duration) {
	now := b.now()
	b.mu.Lock()
	defer b.mu.Unlock()
	hs := b.prune(fingerprint, now)
	if len(hs) >= b.max {
		return false, max(hs[0].Add(time.Hour).Sub(now), time.Second)
	}
	if _, known := b.hits[fingerprint]; !known && len(b.hits) >= b.maxKeys {
		var soonest time.Time
		for k := range b.hits {
			if hs := b.prune(k, now); len(hs) > 0 {
				if free := hs[0].Add(time.Hour); soonest.IsZero() || free.Before(soonest) {
					soonest = free
				}
			}
		}
		if len(b.hits) >= b.maxKeys {
			return false, max(soonest.Sub(now), time.Second)
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

	maxStates, maxCool int // tests lower them
}

func newTracker(now func() time.Time) *tracker {
	return &tracker{now: now, m: map[prepKey]*prepState{}, cool: map[[2]string]time.Time{},
		maxStates: maxTrackedStates, maxCool: maxCooldownKeys}
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
		if len(t.m) >= t.maxStates {
			var oldest prepKey
			var oldestSeen time.Time
			for kk, ss := range t.m {
				if now.Sub(ss.seen) > stateTTL {
					delete(t.m, kk)
				} else if oldestSeen.IsZero() || ss.seen.Before(oldestSeen) {
					oldest, oldestSeen = kk, ss.seen
				}
			}
			if len(t.m) >= t.maxStates {
				delete(t.m, oldest) // only the least recently used one; never the whole map
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

// setCooldown blocks the key at the provider for d (default 1 min, at most 10 min) and returns
// the length. The map is capped: when it is full of cooldowns that are still running, a NEW
// fingerprint is refused (ok false, retry when the first one ends) instead of evicting a running one.
func (t *tracker) setCooldown(fingerprint, provider string, d time.Duration) (time.Duration, bool) {
	if d <= 0 {
		d = defaultCooldown
	}
	d = min(d, maxCooldown)
	t.mu.Lock()
	defer t.mu.Unlock()
	k := [2]string{fingerprint, provider}
	now := t.now()
	if _, known := t.cool[k]; !known && len(t.cool) >= t.maxCool {
		var soonest time.Time
		for kk, u := range t.cool {
			if !now.Before(u) {
				delete(t.cool, kk)
			} else if soonest.IsZero() || u.Before(soonest) {
				soonest = u
			}
		}
		if len(t.cool) >= t.maxCool {
			return max(soonest.Sub(now), time.Second), false
		}
	}
	t.cool[k] = now.Add(d)
	return d, true
}
