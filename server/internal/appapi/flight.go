package appapi

import (
	"context"
	"fmt"
	"sync"
	"time"
)

// flights coalesces concurrent calls with the same key: one runs, the others share its result.
// The work runs in its own goroutine on a context that is detached from the callers (a caller whose
// context ends stops waiting without cancelling the work for the others) but bounded: it ends at
// the timeout, and as soon as the LAST waiter has left, so abandoned work does not run on for 40 s.
// Keys always contain the key fingerprint, so two accounts never share a flight (and never a
// provider link).
type flights[T any] struct {
	mu sync.Mutex
	m  map[string]*flight[T]
}

type flight[T any] struct {
	done    chan struct{}
	val     T
	err     error
	waiters int
	cancel  context.CancelFunc
}

// do joins the flight of key or starts it. admit is called (under the flights lock, so it must not
// block) only when a NEW flight would start; it may refuse, and then the caller gets its error and
// nothing runs. Its release function is called when the work has really ended. fn receives the
// work context.
func (g *flights[T]) do(ctx context.Context, key string, timeout time.Duration, admit func() (release func(), err error), fn func(ctx context.Context) (T, error)) (T, error) {
	var zero T
	g.mu.Lock()
	if g.m == nil {
		g.m = map[string]*flight[T]{}
	}
	c, ok := g.m[key]
	if !ok {
		release := func() {}
		if admit != nil {
			r, err := admit()
			if err != nil {
				g.mu.Unlock()
				return zero, err
			}
			release = r
		}
		wctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), timeout)
		c = &flight[T]{done: make(chan struct{}), cancel: cancel}
		g.m[key] = c
		go func() {
			defer func() {
				if r := recover(); r != nil {
					c.err = fmt.Errorf("appapi: panic: %v", r)
				}
				cancel()
				release()
				g.mu.Lock()
				if g.m[key] == c { // a flight abandoned by every waiter was removed already
					delete(g.m, key)
				}
				g.mu.Unlock()
				close(c.done)
			}()
			c.val, c.err = fn(wctx)
		}()
	}
	c.waiters++
	g.mu.Unlock()

	select {
	case <-c.done:
		return c.val, c.err
	case <-ctx.Done():
	}
	g.mu.Lock()
	c.waiters--
	if c.waiters == 0 {
		select {
		case <-c.done: // finished at the same moment: nothing to cancel
		default:
			c.cancel()
			if g.m[key] == c { // a new caller must not join work that is being cancelled
				delete(g.m, key)
			}
		}
	}
	g.mu.Unlock()
	select {
	case <-c.done:
		return c.val, c.err
	default:
		return zero, ctx.Err()
	}
}
