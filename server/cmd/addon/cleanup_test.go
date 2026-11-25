package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"sync"
	"testing"
	"time"
)

type fakePruner struct {
	mu   sync.Mutex
	cuts []time.Time
	err  error
	ch   chan struct{}
}

func (f *fakePruner) PruneUserAvailability(ctx context.Context, before time.Time) (int64, error) {
	f.mu.Lock()
	f.cuts = append(f.cuts, before)
	err := f.err
	f.mu.Unlock()
	select {
	case f.ch <- struct{}{}:
	default:
	}
	return 3, err
}

func (f *fakePruner) calls() []time.Time {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]time.Time(nil), f.cuts...)
}

func TestPruneUserAvailabilityRunsAtStartAndEveryTickUntilCancelled(t *testing.T) {
	f := &fakePruner{ch: make(chan struct{}, 16), err: errors.New("db down")} // a failing run must not stop the loop
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		pruneUserAvailability(ctx, f, slog.New(slog.NewTextHandler(io.Discard, nil)), 10*time.Millisecond, userAvailabilityMaxAge, func() time.Time { return now })
	}()
	for range 3 {
		select {
		case <-f.ch:
		case <-time.After(5 * time.Second):
			t.Fatal("the job did not keep running")
		}
	}
	cancel()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("the job ignored the cancelled context")
	}
	for _, cut := range f.calls() {
		if want := now.Add(-30 * 24 * time.Hour); !cut.Equal(want) {
			t.Fatalf("cutoff = %v, want %v", cut, want)
		}
	}
	n := len(f.calls())
	time.Sleep(50 * time.Millisecond)
	if len(f.calls()) != n {
		t.Fatal("the job ran after cancel")
	}
}

func TestCleanupConstants(t *testing.T) {
	if cleanupInterval != 6*time.Hour || userAvailabilityMaxAge != 30*24*time.Hour {
		t.Fatal("documented values changed (docs/DESIGN.md)")
	}
}
