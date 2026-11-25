package appapi

import (
	"context"
	"fmt"
	"sync"
)

// flights coalesces concurrent calls with the same key: one runs, the others share its result.
// The work runs in its own goroutine, so a caller whose context ends stops waiting without
// cancelling the work for the others. Keys always contain the key fingerprint, so two accounts
// never share a flight (and never a provider link).
type flights[T any] struct {
	mu sync.Mutex
	m  map[string]*flight[T]
}

type flight[T any] struct {
	done chan struct{}
	val  T
	err  error
}

func (g *flights[T]) do(ctx context.Context, key string, fn func() (T, error)) (T, error) {
	g.mu.Lock()
	if g.m == nil {
		g.m = map[string]*flight[T]{}
	}
	c, ok := g.m[key]
	if !ok {
		c = &flight[T]{done: make(chan struct{})}
		g.m[key] = c
		go func() {
			defer func() {
				if r := recover(); r != nil {
					c.err = fmt.Errorf("appapi: panic: %v", r)
				}
				g.mu.Lock()
				delete(g.m, key)
				g.mu.Unlock()
				close(c.done)
			}()
			c.val, c.err = fn()
		}()
	}
	g.mu.Unlock()

	select {
	case <-c.done:
		return c.val, c.err
	case <-ctx.Done():
		var zero T
		return zero, ctx.Err()
	}
}
