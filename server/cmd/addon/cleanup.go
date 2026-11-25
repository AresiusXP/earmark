package main

import (
	"context"
	"log/slog"
	"time"
)

const (
	// userAvailabilityMaxAge is how long a per-key cache state is kept (readers ignore anything
	// older than the availability TTL anyway; this only bounds the table).
	userAvailabilityMaxAge = 30 * 24 * time.Hour
	cleanupInterval        = 6 * time.Hour
)

// userAvailabilityPruner is implemented by *store.Postgres.
type userAvailabilityPruner interface {
	PruneUserAvailability(ctx context.Context, olderThan time.Time) (int64, error)
}

// pruneUserAvailability deletes per-key cache states older than maxAge once at start and then
// every interval, until ctx ends. It blocks, so run it in a goroutine; a failed run is logged and
// retried at the next tick.
func pruneUserAvailability(ctx context.Context, p userAvailabilityPruner, logger *slog.Logger, interval, maxAge time.Duration, now func() time.Time) {
	run := func() {
		rctx, cancel := context.WithTimeout(ctx, time.Minute)
		defer cancel()
		n, err := p.PruneUserAvailability(rctx, now().Add(-maxAge))
		switch {
		case ctx.Err() != nil:
		case err != nil:
			logger.Warn("prune user availability failed", "err", err)
		case n > 0:
			logger.Info("pruned user availability", "rows", n)
		}
	}
	run()
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			run()
		}
	}
}
