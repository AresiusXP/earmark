package accounts

import (
	"sync"
	"time"
)

// failLimiter allows at most `max` unsuccessful attempts per `window`, globally (there is no
// per-IP identity behind Traefik). Every attempt takes a slot up front and a success gives it
// back, so concurrent guesses cannot all slip past the limit.
type failLimiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	now    func() time.Time
	times  []time.Time
}

func newFailLimiter(max int, window time.Duration, now func() time.Time) *failLimiter {
	return &failLimiter{max: max, window: window, now: now}
}

func (l *failLimiter) prune(now time.Time) {
	cut := now.Add(-l.window)
	i := 0
	for i < len(l.times) && !l.times[i].After(cut) {
		i++
	}
	l.times = l.times[i:]
}

// acquire records an attempt. When the limit is reached it returns false and how long until a
// slot frees up.
func (l *failLimiter) acquire() (ok bool, retryAfter time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	l.prune(now)
	if len(l.times) >= l.max {
		return false, max(l.times[0].Add(l.window).Sub(now), time.Second)
	}
	l.times = append(l.times, now)
	return true, 0
}

// refund releases the slot of a successful attempt.
func (l *failLimiter) refund() {
	l.mu.Lock()
	defer l.mu.Unlock()
	if n := len(l.times); n > 0 {
		l.times = l.times[:n-1]
	}
}
