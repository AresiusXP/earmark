package sources

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/AresiusXP/earmark/server/internal/model"
)

// PerSourceTimeout bounds each source inside MultiSearch.
const PerSourceTimeout = 15 * time.Second

// MultiSearch queries every source concurrently, merges the releases and dedupes them by
// info hash (highest seeders wins). A failing source never fails the call: its error is
// returned (wrapped with the source name) in the second slice. The result is never nil.
func MultiSearch(ctx context.Context, srcs []Source, q Query) ([]model.Release, []error) {
	return multiSearch(ctx, srcs, q, PerSourceTimeout)
}

func multiSearch(ctx context.Context, srcs []Source, q Query, timeout time.Duration) ([]model.Release, []error) {
	type result struct {
		rels []model.Release
		err  error
	}
	results := make([]result, len(srcs))
	var wg sync.WaitGroup
	for i, s := range srcs {
		if s == nil {
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() {
				if r := recover(); r != nil {
					results[i].err = fmt.Errorf("%s: panic: %v", s.Name(), r)
				}
			}()
			sctx, cancel := context.WithTimeout(ctx, timeout)
			defer cancel()
			rels, err := s.Search(sctx, q)
			if err != nil {
				results[i].err = fmt.Errorf("%s: %w", s.Name(), err)
				return
			}
			results[i].rels = rels
		}()
	}
	wg.Wait()

	var all []model.Release
	errs := []error{}
	for _, r := range results {
		if r.err != nil {
			errs = append(errs, r.err)
		}
		for _, rel := range r.rels {
			if rel.InfoHash != "" {
				all = append(all, rel)
			}
		}
	}
	return DedupeByHash(all), errs
}
