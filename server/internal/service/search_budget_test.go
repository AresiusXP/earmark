package service

import (
	"context"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/sources"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

// ctxStore fails the write calls of a release search when their context is already done, like
// a database driver would.
type ctxStore struct{ *fakeStore }

func (s ctxStore) UpsertReleases(ctx context.Context, rs []model.Release) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return s.fakeStore.UpsertReleases(ctx, rs)
}

func (s ctxStore) SetMatches(ctx context.Context, asin string, ms []Match) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return s.fakeStore.SetMatches(ctx, asin, ms)
}

func (s ctxStore) MarkReleasesSearched(ctx context.Context, asin string, at time.Time) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	return s.fakeStore.MarkReleasesSearched(ctx, asin, at)
}

// slowEnv has a fast source (the default fake) and a "slow" one that never answers.
func slowEnv(t *testing.T, mut func(*Deps)) (*env, *fakeSource) {
	t.Helper()
	slow := &fakeSource{name: "slow", gate: make(chan struct{})} // never opened
	e := newEnv(t, func(e *env, d *Deps) {
		d.Store = ctxStore{e.store}
		d.Sources = []sources.Source{e.src, slow}
		d.SearchTimeout = 80 * time.Millisecond
		if mut != nil {
			mut(d)
		}
	})
	return e, slow
}

func TestSearchStoresFastSourceWhenSlowSourceTimesOut(t *testing.T) {
	e, slow := slowEnv(t, nil)
	start := time.Now()
	p := e.mustBook(cfgFor(func(c *userconfig.Config) { c.ShowUncached = true }))
	if time.Since(start) > 3*time.Second {
		t.Fatalf("took %v", time.Since(start))
	}
	if !eq(hashesOfPage(p), []string{h(2), h(0xa1)}) && !eq(hashesOfPage(p), []string{h(0xa1), h(2)}) {
		t.Fatalf("page = %v", hashesOfPage(p))
	}
	if slow.calls() != 1 {
		t.Fatalf("slow source calls = %d", slow.calls())
	}
	if e.store.searched[testASIN].IsZero() {
		t.Fatal("book must be marked searched when another source answered")
	}
	if len(e.store.releases) == 0 || e.store.setMatchesCalls != 1 {
		t.Fatalf("releases=%d setMatches=%d", len(e.store.releases), e.store.setMatchesCalls)
	}
}

func TestSearchPersistsAfterSearchBudgetIsSpent(t *testing.T) {
	// The search sub-deadline is capped by PrepareTimeout, which expires while the slow source
	// is still blocked: the stores below must still succeed on their own fresh context.
	e, _ := slowEnv(t, func(d *Deps) {
		d.PrepareTimeout = 120 * time.Millisecond
		d.SearchTimeout = time.Minute
	})
	_ = e.mustBook(cfgFor(nil))
	e.store.mu.Lock()
	defer e.store.mu.Unlock()
	if len(e.store.releases) == 0 || e.store.setMatchesCalls != 1 || e.store.searched[testASIN].IsZero() {
		t.Fatalf("nothing persisted after the budget ran out: releases=%d setMatches=%d searched=%v",
			len(e.store.releases), e.store.setMatchesCalls, e.store.searched[testASIN])
	}
}

func TestSearchTimeoutWithoutMatchRetriesWithinTheHour(t *testing.T) {
	e, slow := slowEnv(t, nil)
	e.src.rels = []model.Release{{InfoHash: h(3), Title: "Stephen King - The Stand mp3", Source: "fake"}}
	e.mustBook(cfgFor(nil))
	if slow.calls() != 1 || e.src.calls() != 1 {
		t.Fatalf("calls: slow=%d fast=%d", slow.calls(), e.src.calls())
	}
	if e.store.searched[testASIN].IsZero() || len(e.store.matches[testASIN]) != 0 {
		t.Fatal("expected a stored, empty search")
	}
	e.clock.Advance(30 * time.Minute)
	e.mustBook(cfgFor(nil))
	if e.src.calls() != 1 {
		t.Fatalf("retried too early: %d", e.src.calls())
	}
	e.clock.Advance(31 * time.Minute)
	e.mustBook(cfgFor(nil))
	if e.src.calls() != 2 {
		t.Fatalf("expected a retry after EmptyReleaseTTL: %d", e.src.calls())
	}
}

func TestSearchRunsTitleAndSeriesQueriesConcurrently(t *testing.T) {
	e := newEnv(t)
	e.meta.books[testASIN] = func() model.Book {
		b := dune()
		b.Series = "Dune Chronicles"
		return b
	}()
	e.src.gate = make(chan struct{})
	done := make(chan struct{})
	go func() {
		defer close(done)
		e.mustBook(cfgFor(nil))
	}()
	// Both queries reach the (blocked) source before either returns.
	waitFor(t, "both queries in flight", func() bool { return e.src.calls() == 2 })
	close(e.src.gate)
	<-done
	titles := map[string]bool{}
	for _, q := range e.src.queries {
		titles[q.Title] = true
	}
	if !titles["Dune"] || !titles["Dune Chronicles"] {
		t.Fatalf("queries = %+v", e.src.queries)
	}
}
