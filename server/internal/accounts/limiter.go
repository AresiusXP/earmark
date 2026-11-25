package accounts

import (
	"math"
	"sync"
	"time"
)

// failLimiter allows at most `max` unsuccessful attempts per `window`, globally (there is no
// per-IP identity behind Traefik). Every attempt takes a slot up front and a success gives back
// exactly the slot it took, so concurrent guesses cannot all slip past the limit.
//
// It is used only where a secret is guessable: the instance password at POST /v1/auth/bootstrap
// (which exists only while no user does) and the admin's invite creation. Everything else has
// >= 128 bits of entropy and is bounded by rateCap instead: a lockout there would let anybody
// who can send garbage lock every real user out.
type failLimiter struct {
	mu     sync.Mutex
	max    int
	window time.Duration
	now    func() time.Time
	next   uint64
	slots  []slot
}

type slot struct {
	id uint64
	at time.Time
}

// reservation identifies the slot an acquire took; the zero value refunds nothing.
type reservation uint64

func newFailLimiter(max int, window time.Duration, now func() time.Time) *failLimiter {
	return &failLimiter{max: max, window: window, now: now}
}

func (l *failLimiter) prune(now time.Time) {
	cut := now.Add(-l.window)
	i := 0
	for i < len(l.slots) && !l.slots[i].at.After(cut) {
		i++
	}
	l.slots = l.slots[i:]
}

// acquire records an attempt. When the limit is reached it returns ok=false and how long until
// a slot frees up.
func (l *failLimiter) acquire() (res reservation, ok bool, retryAfter time.Duration) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	l.prune(now)
	if len(l.slots) >= l.max {
		return 0, false, max(l.slots[0].at.Add(l.window).Sub(now), time.Second)
	}
	l.next++
	l.slots = append(l.slots, slot{l.next, now})
	return reservation(l.next), true, 0
}

// refund releases the slot of a successful attempt (only that one; an expired slot is already
// gone and refunding it is a no-op).
func (l *failLimiter) refund(res reservation) {
	l.mu.Lock()
	defer l.mu.Unlock()
	for i, s := range l.slots {
		if reservation(s.id) == res {
			l.slots = append(l.slots[:i], l.slots[i+1:]...)
			return
		}
	}
}

// rateCap is a global token bucket: `rate` requests per second with room for `burst`. It only
// bounds CPU and database load of an endpoint that is safe from guessing by entropy; it never
// counts failures, so garbage cannot starve legitimate callers beyond the bucket itself. Size
// it far above what a personal instance does.
type rateCap struct {
	mu     sync.Mutex
	rate   float64
	burst  float64
	now    func() time.Time
	tokens float64
	last   time.Time
}

func newRateCap(rate float64, burst int, now func() time.Time) *rateCap {
	return &rateCap{rate: rate, burst: float64(burst), now: now, tokens: float64(burst), last: now()}
}

// allow takes one token. When the bucket is empty it returns how long until one is available.
func (c *rateCap) allow() (ok bool, retryAfter time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.now()
	if d := now.Sub(c.last).Seconds(); d > 0 {
		c.tokens = math.Min(c.burst, c.tokens+d*c.rate)
		c.last = now
	}
	if c.tokens >= 1 {
		c.tokens--
		return true, 0
	}
	return false, max(time.Duration(math.Ceil((1-c.tokens)/c.rate*float64(time.Second))), time.Second)
}

// RateCap is a global request-rate cap for the public routes outside this package (see rateCap).
type RateCap struct{ c *rateCap }

// NewRateCap allows `rate` requests per second with room for `burst`.
func NewRateCap(rate float64, burst int, now func() time.Time) *RateCap {
	return &RateCap{newRateCap(rate, burst, now)}
}

// Admit takes a token; the error is a *LimitError (with the retry delay) when the bucket is empty.
func (r *RateCap) Admit() error { return admit(r.c) }
