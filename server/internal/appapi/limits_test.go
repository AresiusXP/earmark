package appapi

import (
	"context"
	"fmt"
	"net/http/httptest"
	"sync"
	"testing"

	"github.com/AresiusXP/earmark/server/internal/accounts"
)

func TestProviderWorkIsBoundedPerKeyAndHeadCounts(t *testing.T) {
	e := newRelEnv(t, 50)
	e.setKey("u1", "realdebrid", rdKey1)
	e.setKey("u2", "realdebrid", rdKey2)
	h := h40('a')
	e.addRelease(h)
	e.rd.gate = make(chan struct{})
	fp1 := e.fp("u1", "realdebrid")

	var wg sync.WaitGroup
	codes := make([]int, DefaultMaxWorkPerKey)
	for i := range codes {
		wg.Add(1)
		go func() {
			defer wg.Done()
			codes[i] = e.download("u1", "GET", h, i+1, "realdebrid").Code
		}()
	}
	waitFor(t, "per-key work in flight", func() bool { return e.app.work.inUseBy(fp1) == DefaultMaxWorkPerKey })

	over := e.download("u1", "GET", h, 99, "realdebrid")
	over.errCode(t, 429, "rate_limited")
	if over.Header().Get("Retry-After") == "" {
		t.Fatal("429 without Retry-After")
	}
	if got, _ := over.json(t)["error"].(map[string]any)["retry_after_s"].(float64); got < 1 {
		t.Fatalf("retry_after_s = %v", got)
	}
	e.download("u1", "HEAD", h, 98, "realdebrid").status(t, 429) // HEAD counts too
	e.prepare("u1", h, "realdebrid", nil).errCode(t, 429, "rate_limited")

	// Another key is not affected by u1's burst.
	other := make(chan int, 1)
	go func() { other <- e.download("u2", "GET", h, 1, "realdebrid").Code }()
	waitFor(t, "second key in flight", func() bool { return e.app.work.inUse() == DefaultMaxWorkPerKey+1 })

	close(e.rd.gate)
	wg.Wait()
	for _, c := range codes {
		if c != 302 {
			t.Fatalf("codes = %v", codes)
		}
	}
	if c := <-other; c != 302 {
		t.Fatalf("u2 = %d", c)
	}
	waitFor(t, "slots released", func() bool { return e.app.work.inUse() == 0 })
	e.download("u1", "GET", h, 99, "realdebrid").status(t, 302)
}

func TestProviderWorkGlobalLimit(t *testing.T) {
	e := newRelEnv(t, 50)
	e.app.work = newWorkGate(2, 2)
	h := h40('a')
	e.addRelease(h)
	e.rd.gate = make(chan struct{})
	keys := []string{rdKey1, rdKey2, "rd-key-number-three"}
	for i, k := range keys {
		e.setKey(fmt.Sprint("u", i+1), "realdebrid", k)
	}
	var wg sync.WaitGroup
	for i := range 2 {
		wg.Add(1)
		go func() { defer wg.Done(); e.download(fmt.Sprint("u", i+1), "GET", h, 1, "realdebrid") }()
	}
	waitFor(t, "global work in flight", func() bool { return e.app.work.inUse() == 2 })
	e.download("u3", "GET", h, 1, "realdebrid").errCode(t, 429, "rate_limited")
	close(e.rd.gate)
	wg.Wait()
}

func TestAbandonedProviderWorkIsCancelledAndItsSlotFreed(t *testing.T) {
	e := newRelEnv(t, 50)
	e.setKey("u1", "realdebrid", rdKey1)
	h := h40('a')
	e.addRelease(h)
	e.rd.gate = make(chan struct{}) // never opened: only the cancellation ends the call
	fp := e.fp("u1", "realdebrid")

	ctx, cancel := context.WithCancel(accounts.WithPrincipal(context.Background(), accounts.Principal{UserID: "u1", DeviceID: "d", Role: accounts.RoleUser}))
	req := httptest.NewRequest("GET", "/v1/releases/"+h+"/files/1/download?provider=realdebrid", nil).WithContext(ctx)
	req.SetPathValue("hash", h)
	req.SetPathValue("index", "1")
	done := make(chan struct{})
	go func() { defer close(done); e.app.download(httptest.NewRecorder(), req) }()
	waitFor(t, "provider call", func() bool { return e.rd.gated() == 1 })
	cancel()
	<-done
	waitFor(t, "provider call cancelled", func() bool { return e.rd.cancels() == 1 })
	waitFor(t, "slot released", func() bool { return e.app.work.inUseBy(fp) == 0 })
	if n := flightCount(&e.app.dl); n != 0 {
		t.Fatalf("%d flights left", n)
	}
}
