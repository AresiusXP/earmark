package metadata

import (
	"sync"
	"time"
)

const (
	chapterTTL        = 24 * time.Hour // chapter marks of a published book do not change
	chapterMissTTL    = time.Hour      // Audnexus does not know the book (yet)
	chapterCacheLimit = 512
)

type chapterEntry struct {
	list    ChapterList
	miss    bool
	expires time.Time
}

// chapterCache is a small bounded TTL cache; the zero value is ready to use.
type chapterCache struct {
	mu sync.Mutex
	m  map[string]chapterEntry
}

func (c *chapterCache) get(key string, now time.Time) (list ChapterList, miss, ok bool) {
	c.mu.Lock()
	defer c.mu.Unlock()
	e, found := c.m[key]
	if !found || !now.Before(e.expires) {
		delete(c.m, key)
		return ChapterList{}, false, false
	}
	return e.list, e.miss, true
}

func (c *chapterCache) put(key string, l ChapterList, miss bool, now, expires time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil {
		c.m = map[string]chapterEntry{}
	}
	if _, replace := c.m[key]; !replace && len(c.m) >= chapterCacheLimit {
		for k, e := range c.m {
			if !now.Before(e.expires) {
				delete(c.m, k)
			}
		}
		if len(c.m) >= chapterCacheLimit {
			clear(c.m)
		}
	}
	c.m[key] = chapterEntry{list: l, miss: miss, expires: expires}
}
