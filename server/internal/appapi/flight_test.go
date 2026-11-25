package appapi

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(time.Millisecond)
	}
}

func flightCount[T any](g *flights[T]) int { g.mu.Lock(); defer g.mu.Unlock(); return len(g.m) }

func TestFlightCallerCancelDoesNotCancelWorkOthersWaitFor(t *testing.T) {
	var g flights[int]
	release := make(chan struct{})
	started := make(chan struct{})
	var workCtx context.Context
	fn := func(ctx context.Context) (int, error) { workCtx = ctx; close(started); <-release; return 7, nil }

	ctx1, cancel1 := context.WithCancel(context.Background())
	first := make(chan error, 1)
	go func() { _, err := g.do(ctx1, "k", time.Minute, nil, fn); first <- err }()
	<-started
	second := make(chan int, 1)
	go func() {
		v, _ := g.do(context.Background(), "k", time.Minute, nil, func(context.Context) (int, error) { return 99, nil })
		second <- v
	}()
	waitFor(t, "second waiter", func() bool { g.mu.Lock(); defer g.mu.Unlock(); return g.m["k"].waiters == 2 })
	cancel1()
	if err := <-first; !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v", err)
	}
	if workCtx.Err() != nil {
		t.Fatal("the work was cancelled while a waiter was left")
	}
	close(release)
	if v := <-second; v != 7 {
		t.Fatalf("second waiter got %d, want the shared result 7", v)
	}
}

func TestFlightIsCancelledWhenTheLastWaiterLeaves(t *testing.T) {
	var g flights[int]
	started := make(chan struct{})
	ended := make(chan error, 1)
	var released atomic.Int32
	admit := func() (func(), error) { return func() { released.Add(1) }, nil }
	ctx, cancel := context.WithCancel(context.Background())
	got := make(chan error, 1)
	go func() {
		_, err := g.do(ctx, "k", time.Minute, admit, func(wctx context.Context) (int, error) {
			close(started)
			<-wctx.Done()
			ended <- wctx.Err()
			return 0, wctx.Err()
		})
		got <- err
	}()
	<-started
	cancel()
	if err := <-got; !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v", err)
	}
	select {
	case err := <-ended:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("work ended with %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("detached work kept running with nobody waiting")
	}
	waitFor(t, "release of the work slot", func() bool { return released.Load() == 1 })
	if n := flightCount(&g); n != 0 {
		t.Fatalf("%d flights left", n)
	}
	// A later caller starts fresh work instead of joining the cancelled one.
	v, err := g.do(context.Background(), "k", time.Minute, nil, func(context.Context) (int, error) { return 5, nil })
	if err != nil || v != 5 {
		t.Fatalf("fresh flight: %d %v", v, err)
	}
}

func TestFlightTimeoutBoundsTheWork(t *testing.T) {
	var g flights[int]
	_, err := g.do(context.Background(), "k", 20*time.Millisecond, nil, func(ctx context.Context) (int, error) {
		<-ctx.Done()
		return 0, ctx.Err()
	})
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("err = %v", err)
	}
}

func TestFlightAdmitRefusalStartsNothing(t *testing.T) {
	var g flights[int]
	busy := errors.New("busy")
	var ran atomic.Bool
	_, err := g.do(context.Background(), "k", time.Minute, func() (func(), error) { return nil, busy }, func(context.Context) (int, error) {
		ran.Store(true)
		return 1, nil
	})
	if !errors.Is(err, busy) || ran.Load() || flightCount(&g) != 0 {
		t.Fatalf("err=%v ran=%v flights=%d", err, ran.Load(), flightCount(&g))
	}
	// Joining a running flight is never refused: only a NEW flight needs room.
	release := make(chan struct{})
	started := make(chan struct{})
	go g.do(context.Background(), "k", time.Minute, nil, func(context.Context) (int, error) { close(started); <-release; return 2, nil })
	<-started
	done := make(chan int, 1)
	go func() {
		v, _ := g.do(context.Background(), "k", time.Minute, func() (func(), error) { return nil, busy }, nil)
		done <- v
	}()
	waitFor(t, "joined waiter", func() bool { g.mu.Lock(); defer g.mu.Unlock(); return g.m["k"].waiters == 2 })
	close(release)
	if v := <-done; v != 2 {
		t.Fatalf("joined result = %d", v)
	}
}

func TestFlightPanicBecomesAnError(t *testing.T) {
	var g flights[int]
	_, err := g.do(context.Background(), "k", time.Minute, nil, func(context.Context) (int, error) { panic("boom") })
	if err == nil || flightCount(&g) != 0 {
		t.Fatalf("err=%v flights=%d", err, flightCount(&g))
	}
}

func TestWorkGateLimitsGloballyAndPerKey(t *testing.T) {
	g := newWorkGate(5, 2)
	var rel []func()
	take := func(fp string) error {
		r, err := g.acquire(fp)
		if err == nil {
			rel = append(rel, r)
		}
		return err
	}
	for range 2 {
		if err := take("a"); err != nil {
			t.Fatal(err)
		}
	}
	var be *busyError
	if err := take("a"); !errors.As(err, &be) || be.retryAfter <= 0 {
		t.Fatalf("third unit of one key: %v", err)
	}
	for _, fp := range []string{"b", "b", "c"} {
		if err := take(fp); err != nil {
			t.Fatal(err)
		}
	}
	if err := take("d"); !errors.As(err, &be) {
		t.Fatalf("global limit: %v", err)
	}
	if g.inUse() != 5 || g.inUseBy("a") != 2 {
		t.Fatalf("in use %d / %d", g.inUse(), g.inUseBy("a"))
	}
	rel[0]()
	rel[0]() // idempotent
	if g.inUse() != 4 || g.inUseBy("a") != 1 {
		t.Fatalf("after release: %d / %d", g.inUse(), g.inUseBy("a"))
	}
	if err := take("d"); err != nil {
		t.Fatalf("a released unit must free room: %v", err)
	}
	for _, r := range rel {
		r()
	}
	if g.inUse() != 0 || len(g.byKey) != 0 {
		t.Fatalf("leak: %d %v", g.inUse(), g.byKey)
	}
	if g := newWorkGate(0, 0); g.global != DefaultMaxWork || g.perKey != DefaultMaxWorkPerKey {
		t.Fatal("defaults")
	}
}

func TestWorkGateIsRaceFree(t *testing.T) {
	g := newWorkGate(8, 3)
	var wg sync.WaitGroup
	for i := range 64 {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if r, err := g.acquire([]string{"a", "b", "c"}[i%3]); err == nil {
				time.Sleep(time.Millisecond)
				r()
			}
		}()
	}
	wg.Wait()
	if g.inUse() != 0 {
		t.Fatalf("leak %d", g.inUse())
	}
}
