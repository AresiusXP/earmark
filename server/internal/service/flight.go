package service

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

// flightGroup deduplicates concurrent work by key. The work runs in its own goroutine, so a
// caller whose context ends stops waiting without cancelling the work for the others.
type flightGroup[T any] struct {
	mu sync.Mutex
	m  map[string]*flightCall[T]
}

type flightCall[T any] struct {
	done chan struct{}
	val  T
	err  error
}

func (g *flightGroup[T]) do(ctx context.Context, key string, fn func() (T, error)) (T, error) {
	g.mu.Lock()
	if g.m == nil {
		g.m = map[string]*flightCall[T]{}
	}
	c, ok := g.m[key]
	if !ok {
		c = &flightCall[T]{done: make(chan struct{})}
		g.m[key] = c
		go func() {
			defer func() {
				if r := recover(); r != nil {
					c.err = fmt.Errorf("service: panic: %v", r)
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

// bookCache is a small bounded TTL cache of metadata list responses.
type bookCache struct {
	mu    sync.Mutex
	max   int
	clock func() time.Time
	m     map[string]bookCacheEntry
}

type bookCacheEntry struct {
	books   []model.Book
	expires time.Time
}

func newBookCache(max int, clock func() time.Time) *bookCache {
	return &bookCache{max: max, clock: clock, m: map[string]bookCacheEntry{}}
}

func (c *bookCache) get(key string) ([]model.Book, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[key]
	if !ok {
		return nil, false
	}
	if !c.clock().Before(e.expires) {
		delete(c.m, key)
		return nil, false
	}
	return append([]model.Book(nil), e.books...), true
}

func (c *bookCache) put(key string, books []model.Book, ttl time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	now := c.clock()
	if _, ok := c.m[key]; !ok && len(c.m) >= c.max {
		for k, e := range c.m {
			if !now.Before(e.expires) {
				delete(c.m, k)
			}
		}
		for len(c.m) >= c.max {
			var oldest string
			var oldestAt time.Time
			for k, e := range c.m {
				if oldest == "" || e.expires.Before(oldestAt) {
					oldest, oldestAt = k, e.expires
				}
			}
			delete(c.m, oldest)
		}
	}
	c.m[key] = bookCacheEntry{books: append([]model.Book(nil), books...), expires: now.Add(ttl)}
}

func (c *bookCache) len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.m)
}

func cacheKey(parts ...string) string { return strings.Join(parts, "\x00") }
