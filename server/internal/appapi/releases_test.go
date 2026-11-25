package appapi

import (
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

const (
	rdKey1 = "rd-key-of-user-one-0001"
	rdKey2 = "rd-key-of-user-two-0002"
	tbKey1 = "tb-key-of-user-one-0001"
)

func TestPrepareRealDebridBudgetAndPolling(t *testing.T) {
	e := newRelEnv(t, 2)
	e.setKey("u1", "realdebrid", rdKey1)
	for _, h := range []string{h40('a'), h40('b'), h40('c')} {
		e.addRelease(h)
	}
	steps := 0
	e.rd.answer = func(_ string, _ debrid.ResolveRequest, added bool) (debrid.ResolveResult, error) {
		steps++
		if steps <= 4 { // the add and three polls
			return debrid.ResolveResult{Pending: true, Progress: steps * 10}, nil
		}
		return debrid.ResolveResult{URL: linkFor("realdebrid", rdKey1, 1)}, nil
	}
	fp := e.fp("u1", "realdebrid")
	a := h40('a')

	for i := 1; i <= 4; i++ {
		r := e.prepare("u1", a, "realdebrid", nil)
		r.status(t, 200)
		m := r.json(t)
		if m["state"] != "preparing" || m["progress"] != float64(i*10) {
			t.Fatalf("poll %d: %v", i, m)
		}
		if _, has := m["files"]; has {
			t.Fatalf("files before ready: %v", m)
		}
	}
	if e.rd.addCount() != 1 || e.app.bud.used(fp) != 1 {
		t.Fatalf("polling re-added: adds=%d budget=%d", e.rd.addCount(), e.app.bud.used(fp))
	}
	if cached, ok := e.store.userAvailOf(fp, "realdebrid", a); !ok || cached {
		t.Fatalf("a cold add that is pending is recorded as not cached: %v %v", cached, ok)
	}

	r := e.prepare("u1", a, "realdebrid", nil)
	r.status(t, 200)
	m := r.json(t)
	files, _ := m["files"].([]any)
	if m["state"] != "ready" || len(files) != 2 {
		t.Fatalf("ready = %v", m)
	}
	if f0 := files[0].(map[string]any); f0["index"] != float64(1) || f0["path"] != "Book/01 a.mp3" || f0["size"] != float64(10) {
		t.Fatalf("files not audio-only in chapter order with provider indexes: %v", files)
	}
	if cached, ok := e.store.userAvailOf(fp, "realdebrid", a); !ok || !cached {
		t.Fatalf("ready must be recorded per key: %v %v", cached, ok)
	}
	// Further polls of a ready release do not call the provider at all.
	calls := e.rd.calls()
	e.prepare("u1", a, "realdebrid", nil).status(t, 200)
	if e.rd.calls() != calls {
		t.Fatal("a ready release was polled at the provider again")
	}
	if len(e.store.shared["realdebrid"]) != 0 {
		t.Fatalf("RealDebrid state leaked into the shared availability table: %v", e.store.shared)
	}

	// Budget: two cold adds an hour, the third is a 429 with the time the window frees up.
	e.prepare("u1", h40('b'), "realdebrid", nil).status(t, 200)
	r = e.prepare("u1", h40('c'), "realdebrid", nil)
	r.errCode(t, 429, "rate_limited")
	if ra, _ := r.json(t)["error"].(map[string]any)["retry_after_s"].(float64); ra < 3000 || ra > 3600 || r.Header().Get("Retry-After") == "" {
		t.Fatalf("retry_after_s = %v, header %q", ra, r.Header().Get("Retry-After"))
	}
	if e.rd.addCount() != 2 {
		t.Fatalf("a spent budget must not add: adds=%d", e.rd.addCount())
	}
	e.clk.Advance(time.Hour + time.Second)
	e.prepare("u1", h40('c'), "realdebrid", nil).status(t, 200)
	if e.rd.addCount() != 3 {
		t.Fatalf("budget did not free up: adds=%d", e.rd.addCount())
	}
}

func TestPrepareTorBoxCachedAddIsFreeAndShared(t *testing.T) {
	e := newRelEnv(t, 1)
	e.setKey("u1", "torbox", tbKey1)
	cachedHash, coldHash := h40('a'), h40('b')
	e.addRelease(cachedHash)
	e.addRelease(coldHash)
	e.tb.cached[cachedHash] = debrid.Cached{Files: []model.File{{Index: 7, Path: "x/2.mp3", Size: 2}, {Index: 5, Path: "x/1.mp3", Size: 1}}}
	e.tb.answer = func(_ string, req debrid.ResolveRequest, _ bool) (debrid.ResolveResult, error) {
		if req.InfoHash == cachedHash {
			return debrid.ResolveResult{URL: linkFor("torbox", tbKey1, 5)}, nil
		}
		return debrid.ResolveResult{Pending: true, Progress: 3}, nil
	}
	fp := e.fp("u1", "torbox")

	r := e.prepare("u1", cachedHash, "torbox", nil)
	r.status(t, 200)
	m := r.json(t)
	files := m["files"].([]any)
	if m["state"] != "ready" || len(files) != 2 || files[0].(map[string]any)["index"] != float64(5) {
		t.Fatalf("cached release: %v", m)
	}
	if e.app.bud.used(fp) != 0 {
		t.Fatal("a cached TorBox add must not spend the cold budget")
	}
	if av := e.store.shared["torbox"][cachedHash]; !av.Cached {
		t.Fatalf("TorBox cache state is shared: %+v", e.store.shared)
	}
	if len(e.store.files[cachedHash]) != 2 {
		t.Fatal("files of a cached TorBox torrent are stored for everybody")
	}
	// The cold one spends the budget; asking for another cold one is refused.
	e.prepare("u1", coldHash, "torbox", nil).status(t, 200)
	e.addRelease(h40('c'))
	e.prepare("u1", h40('c'), "torbox", nil).errCode(t, 429, "rate_limited")
	if av := e.store.shared["torbox"][coldHash]; av.Cached || av.InfoHash == "" {
		t.Fatalf("a negative check is shared too: %+v", e.store.shared["torbox"])
	}
}

func TestPrepareFailureModes(t *testing.T) {
	type tc struct {
		name   string
		err    error
		status int
		check  func(t *testing.T, e *relEnv, r reply)
	}
	cases := []tc{
		{"dead swarm", fmt.Errorf("realdebrid: torrent status %q: %w", "dead", debrid.ErrFailed), 200, func(t *testing.T, _ *relEnv, r reply) {
			if m := r.json(t); m["state"] != "failed" || m["reason"] != "no_seeders" {
				t.Fatalf("%v", m)
			}
		}},
		{"other failure", fmt.Errorf("realdebrid: torrent status %q: %w", "magnet_error", debrid.ErrFailed), 200, func(t *testing.T, _ *relEnv, r reply) {
			if m := r.json(t); m["state"] != "failed" || m["reason"] != "debrid_failed" {
				t.Fatalf("%v", m)
			}
		}},
		{"file not in the torrent", fmt.Errorf("no audio: %w", debrid.ErrNotFound), 200, func(t *testing.T, _ *relEnv, r reply) {
			if m := r.json(t); m["state"] != "failed" || m["reason"] != "not_found" {
				t.Fatalf("%v", m)
			}
		}},
		{"rate limited", debrid.ErrRateLimited, 200, func(t *testing.T, _ *relEnv, r reply) {
			m := r.json(t)
			if m["state"] != "preparing" || m["reason"] != "debrid_cooldown" || m["retry_after_s"] != float64(60) || r.Header().Get("Retry-After") != "60" {
				t.Fatalf("%v %v", m, r.Header())
			}
		}},
		{"bad key", debrid.ErrBadKey, 403, func(t *testing.T, e *relEnv, r reply) {
			r.errCode(t, 403, "bad_debrid_key")
			if st := e.keys.state("u1", "realdebrid"); st != "invalid" {
				t.Fatalf("key state = %q, want invalid", st)
			}
		}},
		{"upstream error text is not echoed", errors.New("boom rd-key-of-user-one-0001 https://secret.example/x"), 502, func(t *testing.T, _ *relEnv, r reply) {
			r.errCode(t, 502, "debrid_unavailable")
			if strings.Contains(r.Body.String(), "boom") {
				t.Fatalf("provider text in body: %s", r.Body.String())
			}
		}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			e := newRelEnv(t, 5)
			e.setKey("u1", "realdebrid", rdKey1)
			e.addRelease(h40('a'))
			e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
				return debrid.ResolveResult{}, c.err
			}
			r := e.prepare("u1", h40('a'), "realdebrid", nil)
			r.status(t, c.status)
			c.check(t, e, r)
		})
	}
}

func TestPrepareFailedIsStickyThenRetried(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	e.addRelease(h40('a'))
	fail := true
	e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
		if fail {
			return debrid.ResolveResult{}, fmt.Errorf("status dead: %w", debrid.ErrFailed)
		}
		return debrid.ResolveResult{URL: linkFor("realdebrid", rdKey1, 1)}, nil
	}
	e.prepare("u1", h40('a'), "realdebrid", nil).status(t, 200)
	calls := e.rd.calls()
	for i := 0; i < 3; i++ {
		if m := e.prepare("u1", h40('a'), "realdebrid", nil).json(t); m["state"] != "failed" {
			t.Fatalf("%v", m)
		}
	}
	if e.rd.calls() != calls {
		t.Fatal("a failed torrent was asked about again inside the failure window")
	}
	// HEAD never retries a failed one, even after the window.
	e.clk.Advance(failedTTL + time.Second)
	e.download("u1", "HEAD", h40('a'), 1, "realdebrid").errCode(t, 502, "upstream_failed")
	if e.rd.calls() != calls || len(e.rd.deleted) != 0 {
		t.Fatal("HEAD retried a failed torrent")
	}
	fail = false
	m := e.prepare("u1", h40('a'), "realdebrid", nil).json(t)
	if m["state"] != "ready" || len(e.rd.deleted) != 1 || e.rd.addCount() != 2 {
		t.Fatalf("retry after the window: %v deleted=%v adds=%d", m, e.rd.deleted, e.rd.addCount())
	}
}

func TestPrepareCooldownBlocksTheKeyNotOthers(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	e.setKey("u2", "realdebrid", rdKey2)
	e.addRelease(h40('a'))
	limited := true
	e.rd.answer = func(key string, _ debrid.ResolveRequest, _ bool) (debrid.ResolveResult, error) {
		if key == rdKey1 && limited {
			return debrid.ResolveResult{}, debrid.ErrRateLimited
		}
		return debrid.ResolveResult{Pending: true}, nil
	}
	e.prepare("u1", h40('a'), "realdebrid", nil).status(t, 200)
	calls := e.rd.calls()
	if m := e.prepare("u1", h40('a'), "realdebrid", nil).json(t); m["reason"] != "debrid_cooldown" {
		t.Fatalf("%v", m)
	}
	if e.rd.calls() != calls {
		t.Fatal("the provider was called during the cooldown")
	}
	if m := e.prepare("u2", h40('a'), "realdebrid", nil).json(t); m["state"] != "preparing" || m["reason"] != nil {
		t.Fatalf("another key must not be in cooldown: %v", m)
	}
	e.clk.Advance(61 * time.Second)
	limited = false
	if m := e.prepare("u1", h40('a'), "realdebrid", nil).json(t); m["reason"] != nil {
		t.Fatalf("cooldown did not end: %v", m)
	}
}

func TestPrepareUnknownReleaseAndMagnets(t *testing.T) {
	h := h40('a')
	good := "magnet:?xt=urn:btih:" + strings.ToUpper(h) + "&dn=My+Book&tr=http%3A%2F%2Fevil.example%2Fannounce"
	cases := []struct {
		name   string
		extra  map[string]any
		status int
		code   string
	}{
		{"unknown without magnet", nil, 404, "not_found"},
		{"magnet for another hash", map[string]any{"magnet": "magnet:?xt=urn:btih:" + h40('b')}, 400, "invalid_request"},
		{"not a magnet", map[string]any{"magnet": "https://example.com/x"}, 400, "invalid_request"},
		{"no btih", map[string]any{"magnet": "magnet:?dn=x"}, 400, "invalid_request"},
		{"two different hashes", map[string]any{"magnet": "magnet:?xt=urn:btih:" + h + "&xt=urn:btih:" + h40('b')}, 400, "invalid_request"},
		{"control chars", map[string]any{"magnet": "magnet:?xt=urn:btih:" + h + "&dn=a\nb"}, 400, "invalid_request"},
		{"too long", map[string]any{"magnet": "magnet:?xt=urn:btih:" + h + "&dn=" + strings.Repeat("a", 3000)}, 400, "invalid_request"},
		{"valid", map[string]any{"magnet": good}, 200, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			e := newRelEnv(t, 5)
			e.setKey("u1", "realdebrid", rdKey1)
			e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
				return debrid.ResolveResult{Pending: true}, nil
			}
			r := e.prepare("u1", h, "realdebrid", c.extra)
			if c.code != "" {
				r.errCode(t, c.status, c.code)
				if len(e.store.releases) != 0 || e.rd.addCount() != 0 {
					t.Fatal("a rejected request changed state")
				}
				return
			}
			r.status(t, 200)
			rel := e.store.releases[h]
			if rel.Title != "My Book" || rel.Source != "app" || strings.Contains(rel.Magnet, "evil.example") || !strings.Contains(rel.Magnet, h) {
				t.Fatalf("stored release must be rebuilt from the hash, not the client's magnet: %+v", rel)
			}
		})
	}
}

func TestPrepareNeedsTheCallersOwnKey(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "torbox", tbKey1)
	e.addRelease(h40('a'))
	e.prepare("u2", h40('a'), "realdebrid", nil).errCode(t, 403, "no_debrid_key")
	e.prepare("u1", h40('a'), "realdebrid", nil).errCode(t, 403, "no_debrid_key") // has a TorBox key only
	e.prepare("u1", h40('a'), "premiumize", nil).errCode(t, 400, "invalid_request")
	e.prepare("u1", "XYZ", "torbox", nil).errCode(t, 400, "invalid_request")
	if e.rd.calls()+e.tb.calls() != 0 {
		t.Fatal("a request without the caller's key reached a provider")
	}
	if e.app.bud.used("") != 0 {
		t.Fatal("budget spent without a key")
	}
}

func TestUsersNeverShareKeysLinksOrBudgets(t *testing.T) {
	e := newRelEnv(t, 1)
	e.setKey("u1", "realdebrid", rdKey1)
	e.setKey("u2", "realdebrid", rdKey2)
	e.setKey("u3", "realdebrid", rdKey1) // a household: the same key as u1
	h := h40('a')
	e.addRelease(h)

	r1 := e.download("u1", "GET", h, 1, "realdebrid")
	r1.status(t, 302)
	r2 := e.download("u2", "GET", h, 1, "realdebrid")
	r2.status(t, 302)
	l1, l2 := r1.Header().Get("Location"), r2.Header().Get("Location")
	if !strings.Contains(l1, rdKey1) || !strings.Contains(l2, rdKey2) || l1 == l2 {
		t.Fatalf("links must come from each user's own key: %q %q", l1, l2)
	}
	if e.rd.addCount() != 2 {
		t.Fatalf("each account adds on its own: %d", e.rd.addCount())
	}
	for _, c := range e.rd.resolves {
		if !strings.HasPrefix(c, rdKey1+"|") && !strings.HasPrefix(c, rdKey2+"|") {
			t.Fatalf("unexpected key in call %q", c)
		}
	}

	// u3 holds the same key as u1: same fingerprint, so the cached link and the account are shared
	// (like the debrid account itself) and the budget of 1 is not spent a second time.
	calls := e.rd.calls()
	r3 := e.download("u3", "GET", h, 1, "realdebrid")
	r3.status(t, 302)
	if r3.Header().Get("Location") != l1 || e.rd.calls() != calls {
		t.Fatal("a household shares the cached link")
	}
	// ...but u2 asking for another file never sees u1's link.
	if got := e.download("u2", "GET", h, 2, "realdebrid").Header().Get("Location"); strings.Contains(got, rdKey1) {
		t.Fatalf("u2 got a link of u1: %q", got)
	}
	// Budgets are per fingerprint: u1 and u2 each have 1 add and both are spent.
	e.addRelease(h40('b'))
	e.download("u1", "GET", h40('b'), 1, "realdebrid").errCode(t, 429, "rate_limited")
	e.download("u2", "GET", h40('b'), 1, "realdebrid").errCode(t, 429, "rate_limited")
}

func TestDownloadRedirectAndLinkCache(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	h := h40('a')
	e.addRelease(h)

	r := e.download("u1", "GET", h, 1, "realdebrid")
	r.status(t, 302)
	want := linkFor("realdebrid", rdKey1, 1)
	if r.Header().Get("Location") != want || r.Header().Get("Cache-Control") != "no-store" || r.Body.Len() != 0 {
		t.Fatalf("redirect: %v body %q", r.Header(), r.Body.String())
	}
	calls := e.rd.calls()
	// Reconnects and seeks of a player: GET and HEAD are served from the link cache.
	for i := 0; i < 5; i++ {
		for _, m := range []string{"GET", "HEAD"} {
			if got := e.download("u1", m, h, 1, "realdebrid"); got.Code != 302 || got.Header().Get("Location") != want {
				t.Fatalf("%s: %d %v", m, got.Code, got.Header())
			}
		}
	}
	if e.rd.calls() != calls {
		t.Fatalf("the provider was called %d times for cached links", e.rd.calls()-calls)
	}
	// Another file is another entry.
	e.download("u1", "GET", h, 2, "realdebrid").status(t, 302)
	if e.rd.calls() != calls+1 {
		t.Fatal("index is part of the cache key")
	}
	// TTL: after 10 minutes the link is asked for again.
	e.clk.Advance(defaultLinkTTL + time.Second)
	e.download("u1", "GET", h, 1, "realdebrid").status(t, 302)
	if e.rd.calls() != calls+2 {
		t.Fatalf("expired link was not refreshed: %d", e.rd.calls()-calls)
	}
}

func TestDownloadPendingAndFailedAreNeverCached(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	h := h40('a')
	e.addRelease(h)
	var mode string
	e.rd.answer = func(_ string, _ debrid.ResolveRequest, _ bool) (debrid.ResolveResult, error) {
		switch mode {
		case "pending":
			return debrid.ResolveResult{Pending: true, Progress: 42}, nil
		case "failed":
			return debrid.ResolveResult{}, fmt.Errorf("status dead: %w", debrid.ErrFailed)
		}
		return debrid.ResolveResult{URL: linkFor("realdebrid", rdKey1, 1)}, nil
	}

	mode = "pending"
	r := e.download("u1", "GET", h, 1, "realdebrid")
	r.status(t, 202)
	if m := r.json(t); m["state"] != "preparing" || m["progress"] != float64(42) || r.Header().Get("Location") != "" {
		t.Fatalf("%v", m)
	}
	if _, ok := e.app.links.get(e.fp("u1", "realdebrid"), "realdebrid", h, 1); ok {
		t.Fatal("a 202 was cached")
	}
	mode = "ready"
	e.download("u1", "GET", h, 1, "realdebrid").status(t, 302) // the next request asks again and gets the link
	e.app.links.evict(e.fp("u1", "realdebrid"))

	e2 := newRelEnv(t, 5)
	e2.setKey("u1", "realdebrid", rdKey1)
	e2.addRelease(h)
	e2.rd.answer = e.rd.answer
	mode = "failed"
	e2.download("u1", "GET", h, 1, "realdebrid").errCode(t, 502, "upstream_failed")
	if _, ok := e2.app.links.get(e2.fp("u1", "realdebrid"), "realdebrid", h, 1); ok {
		t.Fatal("a failure was cached")
	}
}

func TestDownloadRejectsLinksOutsideTheAllowlist(t *testing.T) {
	bad := []string{
		"http://22-4.download.real-debrid.com/d/x",              // not https
		"https://real-debrid.com.evil.com/d/x",                  // look-alike suffix
		"https://evil.com/real-debrid.com",                      // allowed name only in the path
		"https://evil.com/?u=https://download.real-debrid.com/", // ...or the query
		"https://download.real-debrid.com@evil.com/x",           // userinfo trick
		"https://user:pw@22-4.download.real-debrid.com/x",       // userinfo
		"https://notreal-debrid.com/x",                          // not a subdomain
		"https://22-4.download.real-debrid.com:8443/x",          // odd port
		"https://127.0.0.1/x",
		"//22-4.download.real-debrid.com/x",
		"https://tb-cdn.st/x", // another provider's host
		"javascript:alert(1)",
		"",
	}
	for _, link := range bad {
		t.Run(link, func(t *testing.T) {
			e := newRelEnv(t, 5)
			e.setKey("u1", "realdebrid", rdKey1)
			e.addRelease(h40('a'))
			e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
				return debrid.ResolveResult{URL: link}, nil
			}
			r := e.download("u1", "GET", h40('a'), 1, "realdebrid")
			r.errCode(t, 502, "upstream_failed")
			if loc := r.Header().Get("Location"); loc != "" {
				t.Fatalf("open redirect to %q", loc)
			}
			if _, ok := e.app.links.get(e.fp("u1", "realdebrid"), "realdebrid", h40('a'), 1); ok {
				t.Fatal("a rejected link was cached")
			}
		})
	}
}

func TestHeadHasNoSideEffects(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	e.setKey("u1", "torbox", tbKey1)
	h := h40('a')
	e.addRelease(h)
	fpRD := e.fp("u1", "realdebrid")

	for _, p := range []string{"realdebrid", "torbox"} {
		r := e.download("u1", "HEAD", h, 1, p)
		r.status(t, 202)
		if r.Body.Len() != 0 && r.Header().Get("Location") != "" {
			t.Fatal("HEAD 202 must not redirect")
		}
	}
	if e.rd.calls() != 0 || e.tb.calls() != 0 || e.rd.addCount() != 0 || e.tb.addCount() != 0 {
		t.Fatalf("HEAD called Resolve: rd=%d tb=%d", e.rd.calls(), e.tb.calls())
	}
	if e.app.bud.used(fpRD) != 0 || len(e.store.userAvail) != 0 || len(e.store.shared) != 0 {
		t.Fatal("HEAD spent budget or wrote state")
	}
	// After a GET added the torrent, HEAD sees it (no add: it is in the account) and answers like GET.
	e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
		return debrid.ResolveResult{Pending: true, Progress: 5}, nil
	}
	e.download("u1", "GET", h, 1, "realdebrid").status(t, 202)
	adds := e.rd.addCount()
	e.download("u1", "HEAD", h, 1, "realdebrid").status(t, 202)
	if e.rd.addCount() != adds {
		t.Fatal("HEAD added a torrent")
	}
}

func TestDownloadValidation(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	e.addRelease(h40('a'))
	e.download("u1", "GET", "nothex", 1, "realdebrid").errCode(t, 400, "invalid_request")
	e.download("u1", "GET", h40('a'), -1, "realdebrid").errCode(t, 400, "invalid_request")
	e.download("u1", "GET", h40('a'), 1, "").errCode(t, 400, "invalid_request")
	e.download("u1", "GET", h40('a'), 1, "torbox").errCode(t, 403, "no_debrid_key")
	e.download("u2", "GET", h40('a'), 1, "realdebrid").errCode(t, 403, "no_debrid_key")
	e.download("u1", "GET", h40('f'), 1, "realdebrid").errCode(t, 404, "not_found")
	e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
		return debrid.ResolveResult{}, fmt.Errorf("file 9 not in torrent: %w", debrid.ErrNotFound)
	}
	e.download("u1", "GET", h40('a'), 9, "realdebrid").errCode(t, 404, "not_found")
	e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
		return debrid.ResolveResult{}, debrid.ErrBadKey
	}
	e.download("u1", "GET", h40('a'), 3, "realdebrid").errCode(t, 403, "bad_debrid_key")
	if e.keys.state("u1", "realdebrid") != "invalid" {
		t.Fatal("a rejected key must be marked invalid")
	}
	e.download("u1", "GET", h40('a'), 3, "realdebrid").errCode(t, 403, "bad_debrid_key") // now from the stored state
}

func TestBadKeyEvictsCachedLinks(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	e.addRelease(h40('a'))
	e.download("u1", "GET", h40('a'), 1, "realdebrid").status(t, 302)
	fp := e.fp("u1", "realdebrid")
	if _, ok := e.app.links.get(fp, "realdebrid", h40('a'), 1); !ok {
		t.Fatal("link not cached")
	}
	e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
		return debrid.ResolveResult{}, debrid.ErrBadKey
	}
	e.download("u1", "GET", h40('a'), 2, "realdebrid").errCode(t, 403, "bad_debrid_key")
	if _, ok := e.app.links.get(fp, "realdebrid", h40('a'), 1); ok {
		t.Fatal("links of a rejected key stay cached")
	}
}

func TestConcurrentDownloadsOfOneKeyShareOneProviderCall(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	e.addRelease(h40('a'))
	e.rd.gate = make(chan struct{})

	var wg sync.WaitGroup
	codes := make([]int, 12)
	for i := range codes {
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes[i] = e.download("u1", "GET", h40('a'), 1, "realdebrid").Code
		}()
	}
	// Wait until the one provider call is blocked on the gate, then let every waiter pile up.
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		e.app.dl.mu.Lock()
		n := len(e.app.dl.m)
		e.app.dl.mu.Unlock()
		if n == 1 {
			break
		}
		time.Sleep(time.Millisecond)
	}
	time.Sleep(50 * time.Millisecond)
	close(e.rd.gate)
	wg.Wait()
	for _, c := range codes {
		if c != 302 {
			t.Fatalf("codes = %v", codes)
		}
	}
	if e.rd.calls() != 1 {
		t.Fatalf("provider calls = %d, want 1 (singleflight + link cache)", e.rd.calls())
	}
}

func TestTorrentFilesValidationAndStoreOnce(t *testing.T) {
	e := newRelEnv(t, 5)
	h := h40('a')
	e.addRelease(h)
	post := func(hash string, body any) reply {
		return e.call(e.app.torrentFiles, "u1", "POST", "/v1/releases/"+hash+"/torrent-files", body, map[string]string{"hash": hash})
	}
	file := func(i int, p string, s int64) map[string]any { return map[string]any{"index": i, "path": p, "size": s} }
	many := make([]any, 2001)
	for i := range many {
		many[i] = file(i, "a.mp3", 1)
	}
	bad := []struct {
		name string
		hash string
		body any
	}{
		{"empty list", h, map[string]any{"files": []any{}}},
		{"missing files", h, map[string]any{}},
		{"2001 files", h, map[string]any{"files": many}},
		{"dot dot segment", h, map[string]any{"files": []any{file(0, "a/../../etc/passwd", 1)}}},
		{"dot dot with backslash", h, map[string]any{"files": []any{file(0, `a\..\b.mp3`, 1)}}},
		{"NUL", h, map[string]any{"files": []any{file(0, "a\x00b.mp3", 1)}}},
		{"control char", h, map[string]any{"files": []any{file(0, "a\nb.mp3", 1)}}},
		{"DEL", h, map[string]any{"files": []any{file(0, "a\x7fb.mp3", 1)}}},
		{"empty path", h, map[string]any{"files": []any{file(0, "", 1)}}},
		{"only separators", h, map[string]any{"files": []any{file(0, "//./", 1)}}},
		{"513 bytes", h, map[string]any{"files": []any{file(0, strings.Repeat("a", 513), 1)}}},
		{"negative index", h, map[string]any{"files": []any{file(-1, "a.mp3", 1)}}},
		{"negative size", h, map[string]any{"files": []any{file(0, "a.mp3", -1)}}},
		{"duplicate index", h, map[string]any{"files": []any{file(0, "a.mp3", 1), file(0, "b.mp3", 1)}}},
		{"missing size", h, map[string]any{"files": []any{map[string]any{"index": 0, "path": "a.mp3"}}}},
		{"not json", h, "{"},
		{"trailing brace", h, `{"files":[{"index":0,"path":"a.mp3","size":1}]}}`},
		{"trailing second value", h, `{"files":[{"index":0,"path":"a.mp3","size":1}]} {"files":[]}`},
		{"trailing garbage", h, `{"files":[{"index":0,"path":"a.mp3","size":1}]} x`},
		{"body over the limit", h, `{"files":[{"index":0,"path":"` + strings.Repeat("a", maxTorrentFilesBody) + `","size":1}]}`},
		{"bad hash", "xyz", map[string]any{"files": []any{file(0, "a.mp3", 1)}}},
	}
	for _, c := range bad {
		t.Run(c.name, func(t *testing.T) {
			post(c.hash, c.body).errCode(t, 400, "invalid_request")
			if len(e.store.clientFiles) != 0 {
				t.Fatal("a rejected list was stored")
			}
		})
	}
	post(h40('f'), map[string]any{"files": []any{file(0, "a.mp3", 1)}}).errCode(t, 404, "not_found")

	r := post(h, map[string]any{"files": []any{file(0, `Disc 1\01 - a.mp3`, 10), file(1, "/./Disc 1//02 - b.mp3", 20), file(2, strings.Repeat("a", 512), 0)}})
	r.status(t, 200)
	got := r.json(t)["torrent_files"].([]any)
	if len(got) != 3 || got[0].(map[string]any)["path"] != "Disc 1/01 - a.mp3" || got[1].(map[string]any)["path"] != "Disc 1/02 - b.mp3" {
		t.Fatalf("paths are normalised: %v", got)
	}
	// Stored once: a later report is answered with the stored list and changes nothing.
	r = post(h, map[string]any{"files": []any{file(0, "other.mp3", 1)}})
	r.status(t, 200)
	if got := r.json(t)["torrent_files"].([]any); len(got) != 3 || got[0].(map[string]any)["path"] != "Disc 1/01 - a.mp3" {
		t.Fatalf("overwritten: %v", got)
	}
}

func TestPrepareDTOShapes(t *testing.T) {
	d := toPrepareDTO(prepResult{State: "preparing", HasProgress: true, Progress: 0})
	if d.Progress == nil || *d.Progress != 0 {
		t.Fatal("progress 0 must be present when known")
	}
	if d := toPrepareDTO(prepResult{State: "preparing", RetryAfter: 1500 * time.Millisecond}); d.RetryAfter != 2 {
		t.Fatalf("retry_after_s rounds up: %d", d.RetryAfter)
	}
	if d := toPrepareDTO(prepResult{State: "ready", URL: "https://x.tb-cdn.st/y"}); d.State != "ready" {
		t.Fatal("state")
	}
}
