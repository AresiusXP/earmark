package appapi

import (
	"strconv"
	"sync"
	"time"
)

// A resolved provider link is cached in memory per (key fingerprint, provider, hash, file index)
// so a player that reconnects or seeks (new Range request, new GET) does not call the provider
// each time (Real-Debrid unrestrict is throttled to 4 requests/s per key). Only successful,
// allowlist-checked links are cached; 202 (not ready) and failures never are. Links are never
// logged and never leave the process.
const (
	defaultLinkTTL  = 10 * time.Minute
	maxCachedLinks  = 2048
	linkCacheSweeps = 256 // expired entries are swept every N puts
)

// maxLinkTTL caps the cache per provider at what the provider's links certainly outlive. Neither
// provider documents a precise validity (TorBox: hours; Real-Debrid: IP bound, no stated limit),
// so the caps are conservative and the 10 minute default is what usually applies.
var maxLinkTTL = map[string]time.Duration{
	"torbox":     time.Hour,
	"realdebrid": time.Hour,
}

func linkTTL(provider string) time.Duration {
	ttl := defaultLinkTTL
	if m, ok := maxLinkTTL[provider]; ok && m < ttl {
		ttl = m
	}
	return ttl
}

type linkEntry struct {
	url     string
	expires time.Time
}

type linkCache struct {
	now func() time.Time

	mu   sync.Mutex
	m    map[string]linkEntry
	puts int
}

func newLinkCache(now func() time.Time) *linkCache {
	return &linkCache{now: now, m: map[string]linkEntry{}}
}

func linkKey(fingerprint, provider, hash string, index int) string {
	return fingerprint + "\x00" + provider + "\x00" + hash + "\x00" + strconv.Itoa(index)
}

func (c *linkCache) get(fingerprint, provider, hash string, index int) (string, bool) {
	k := linkKey(fingerprint, provider, hash, index)
	c.mu.Lock()
	defer c.mu.Unlock()
	e, ok := c.m[k]
	if !ok {
		return "", false
	}
	if !c.now().Before(e.expires) {
		delete(c.m, k)
		return "", false
	}
	return e.url, true
}

func (c *linkCache) put(fingerprint, provider, hash string, index int, link string) {
	now := c.now()
	c.mu.Lock()
	defer c.mu.Unlock()
	c.puts++
	if c.puts%linkCacheSweeps == 0 || len(c.m) >= maxCachedLinks {
		for k, e := range c.m {
			if !now.Before(e.expires) {
				delete(c.m, k)
			}
		}
		for k := range c.m { // still full: make room (links are cheap to get again)
			if len(c.m) < maxCachedLinks {
				break
			}
			delete(c.m, k)
		}
	}
	c.m[linkKey(fingerprint, provider, hash, index)] = linkEntry{url: link, expires: now.Add(linkTTL(provider))}
}

// evict drops every link of a key (the provider rejected it).
func (c *linkCache) evict(fingerprint string) {
	prefix := fingerprint + "\x00"
	c.mu.Lock()
	defer c.mu.Unlock()
	for k := range c.m {
		if len(k) >= len(prefix) && k[:len(prefix)] == prefix {
			delete(c.m, k)
		}
	}
}
