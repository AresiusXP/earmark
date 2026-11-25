package appapi

import (
	"context"
	"fmt"
	"sync"
	"testing"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

// statusProbe adds the read-only debrid.StatusReader to a fake provider.
type statusProbe struct {
	mu    sync.Mutex
	st    debrid.Status
	err   error
	calls int
}

func (s *statusProbe) Status(context.Context, string, string) (debrid.Status, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.calls++
	return s.st, s.err
}
func (s *statusProbe) set(st debrid.Status, err error) {
	s.mu.Lock()
	s.st, s.err = st, err
	s.mu.Unlock()
}
func (s *statusProbe) n() int { s.mu.Lock(); defer s.mu.Unlock(); return s.calls }

type rdWithStatus struct {
	*rdFake
	*statusProbe
}
type tbWithStatus struct {
	*tbFake
	*statusProbe
}

// HEAD must be answerable without any side effect: whatever the provider reports, the add, the
// file selection (both inside Resolve), the delete, the cache check, the budget and the stored
// availability stay untouched.
func TestHeadIsReadOnlyWhateverTheProviderReports(t *testing.T) {
	two := []model.File{{Index: 1, Path: "B/01.mp3", Size: 1}, {Index: 2, Path: "B/02.mp3", Size: 2}}
	cases := []struct {
		name     string
		st       debrid.Status
		err      error
		idx      int
		wantCode int
		wantErr  string
		wantProg float64
	}{
		{"not in the account", debrid.Status{}, fmt.Errorf("x: %w", debrid.ErrNotFound), 1, 202, "", -1},
		{"pending with progress", debrid.Status{State: debrid.StatePending, Progress: 30}, nil, 1, 202, "", 30},
		{"waiting for the selection is still pending", debrid.Status{State: debrid.StatePending}, nil, 1, 202, "", 0},
		{"ready, file present", debrid.Status{State: debrid.StateReady, Progress: 100, Files: two}, nil, 2, 200, "", -1},
		{"ready, no file list", debrid.Status{State: debrid.StateReady, Progress: 100}, nil, 2, 200, "", -1},
		{"ready, file missing", debrid.Status{State: debrid.StateReady, Progress: 100, Files: two}, nil, 7, 404, "not_found", -1},
		{"failed at the provider", debrid.Status{}, fmt.Errorf("x: %w", debrid.ErrFailed), 1, 502, "upstream_failed", -1},
		{"key rejected", debrid.Status{}, debrid.ErrBadKey, 1, 403, "bad_debrid_key", -1},
		{"rate limited", debrid.Status{}, debrid.ErrRateLimited, 1, 202, "", -1},
	}
	for _, provider := range []string{"realdebrid", "torbox"} {
		for _, c := range cases {
			t.Run(provider+"/"+c.name, func(t *testing.T) {
				e := newRelEnv(t, 5)
				e.setKey("u1", provider, map[string]string{"realdebrid": rdKey1, "torbox": tbKey1}[provider])
				h := h40('a')
				e.addRelease(h)
				fp := e.fp("u1", provider)
				probe := &statusProbe{st: c.st, err: c.err}
				rd, tb := &rdWithStatus{rdFake: e.rd, statusProbe: probe}, &tbWithStatus{tbFake: e.tb, statusProbe: probe}
				e.app.d.Providers = debrid.NewRegistry(rd, tb)

				r := e.download("u1", "HEAD", h, c.idx, provider)
				if c.wantErr != "" {
					r.errCode(t, c.wantCode, c.wantErr)
				} else {
					r.status(t, c.wantCode)
				}
				if r.Header().Get("Location") != "" {
					t.Fatal("HEAD must not redirect without a cached link")
				}
				if c.wantCode == 200 && r.Body.Len() != 0 {
					t.Fatalf("ready HEAD body = %q", r.Body.String())
				}
				if c.wantProg >= 0 {
					if got, ok := r.json(t)["progress"].(float64); !ok || got != c.wantProg {
						t.Fatalf("progress = %v (%s)", got, r.Body.String())
					}
				}
				if probe.n() != 1 {
					t.Fatalf("Status calls = %d, want 1", probe.n())
				}
				if e.rd.calls() != 0 || e.tb.calls() != 0 || e.rd.addCount() != 0 || e.tb.addCount() != 0 {
					t.Fatalf("HEAD called Resolve: rd=%d tb=%d", e.rd.calls(), e.tb.calls())
				}
				if len(e.rd.deleted) != 0 || e.tb.checks != 0 {
					t.Fatalf("HEAD deleted (%v) or ran a cache check (%d)", e.rd.deleted, e.tb.checks)
				}
				if e.app.bud.used(fp) != 0 || len(e.store.userAvail) != 0 || len(e.store.shared) != 0 || e.store.saveFiles != 0 {
					t.Fatal("HEAD spent budget or stored state")
				}
			})
		}
	}
}

// A provider that can only list files (no Status) can never prove readiness: 202, never Resolve.
func TestHeadWithoutStatusNeverResolves(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	e.setKey("u1", "torbox", tbKey1)
	h := h40('a')
	e.addRelease(h)
	e.rd.acct[rdKey1] = map[string]bool{h: true} // the torrent is in the account and listable
	for _, p := range []string{"realdebrid", "torbox"} {
		e.download("u1", "HEAD", h, 1, p).status(t, 202)
	}
	if e.rd.calls() != 0 || e.tb.calls() != 0 || e.rd.addCount() != 0 {
		t.Fatalf("HEAD called Resolve: rd=%d tb=%d", e.rd.calls(), e.tb.calls())
	}
	if e.app.bud.used(e.fp("u1", "realdebrid")) != 0 {
		t.Fatal("HEAD spent budget")
	}
}

// A prepare that proved the torrent ready is remembered for a while: HEAD answers from that without asking again.
func TestHeadUsesTheReadyStateOfAPrepare(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	h := h40('a')
	e.addRelease(h)
	e.prepare("u1", h, "realdebrid", nil).status(t, 200)
	before := e.rd.calls()
	probe := &statusProbe{err: fmt.Errorf("x: %w", debrid.ErrNotFound)}
	e.app.d.Providers = debrid.NewRegistry(&rdWithStatus{rdFake: e.rd, statusProbe: probe}, e.tb)
	r := e.download("u1", "HEAD", h, 1, "realdebrid")
	r.status(t, 200)
	if probe.n() != 0 || e.rd.calls() != before {
		t.Fatalf("status calls = %d, resolves %d -> %d", probe.n(), before, e.rd.calls())
	}
}

// A failed torrent is answered from memory and a HEAD never retries it (no delete, no add).
func TestHeadOnAFailedTorrentDoesNotRetry(t *testing.T) {
	e := newRelEnv(t, 5)
	e.setKey("u1", "realdebrid", rdKey1)
	h := h40('a')
	e.addRelease(h)
	e.rd.answer = func(string, debrid.ResolveRequest, bool) (debrid.ResolveResult, error) {
		return debrid.ResolveResult{}, fmt.Errorf("status dead: %w", debrid.ErrFailed)
	}
	e.download("u1", "GET", h, 1, "realdebrid").errCode(t, 502, "upstream_failed")
	e.clk.Advance(failedTTL + 1)
	calls := e.rd.calls()
	e.download("u1", "HEAD", h, 1, "realdebrid").errCode(t, 502, "upstream_failed")
	if e.rd.calls() != calls || len(e.rd.deleted) != 0 {
		t.Fatal("HEAD retried a failed torrent")
	}
}
