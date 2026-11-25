package service

import (
	"context"
	"fmt"
	"slices"
	"strconv"
	"strings"
	"sync"

	"github.com/AresiusXP/earmark/server/internal/metadata"
	"github.com/AresiusXP/earmark/server/internal/model"
	"github.com/AresiusXP/earmark/server/internal/userconfig"
)

// Search finds books by free text for every language of cfg and interleaves the results.
func (s *Service) Search(ctx context.Context, cfg userconfig.Config, query string) ([]model.Book, error) {
	return s.SearchFor(ctx, Prefs{Region: cfg.Region, Languages: cfg.Languages}, query)
}

// SearchFor is Search with explicit per-user parameters (the /v1 API).
func (s *Service) SearchFor(ctx context.Context, p Prefs, query string) ([]model.Book, error) {
	query = strings.TrimSpace(query)
	if query == "" {
		return []model.Book{}, nil
	}
	return s.catalogLookup(ctx, p, "search\x00"+strings.ToLower(query),
		func(ctx context.Context, region, lang string) ([]model.Book, error) {
			return s.d.Metadata.Search(ctx, region, lang, query)
		})
}

// Discover lists best sellers for cfg.Region / cfg.Languages (page is 0-based).
func (s *Service) Discover(ctx context.Context, cfg userconfig.Config, page int) ([]model.Book, error) {
	return s.DiscoverFor(ctx, Prefs{Region: cfg.Region, Languages: cfg.Languages}, page)
}

// DiscoverFor is Discover with explicit per-user parameters (the /v1 API).
func (s *Service) DiscoverFor(ctx context.Context, p Prefs, page int) ([]model.Book, error) {
	if page < 0 {
		page = 0
	}
	return s.catalogLookup(ctx, p, "discover\x00"+strconv.Itoa(page),
		func(ctx context.Context, region, lang string) ([]model.Book, error) {
			return s.d.Metadata.Discover(ctx, region, lang, page)
		})
}

// catalogLookup runs fetch once per language (cached by region+language+what), then merges.
// A failing language is skipped as long as another one answered.
func (s *Service) catalogLookup(ctx context.Context, cfg Prefs, what string,
	fetch func(ctx context.Context, region, lang string) ([]model.Book, error)) ([]model.Book, error) {
	region, err := metadata.MarketplaceRegion(cfg.Region)
	if err != nil {
		return nil, fmt.Errorf("service: %w", err)
	}
	langs := uniqueLanguages(cfg.Languages)

	lists := make([][]model.Book, len(langs))
	errs := make([]error, len(langs))
	var wg sync.WaitGroup
	for i, lang := range langs {
		key := cacheKey(region, lang, what)
		if books, ok := s.catalog.get(key); ok {
			lists[i] = books
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs[i] = safely(func() error {
				books, err := fetch(ctx, region, lang)
				if err != nil {
					return err
				}
				s.catalog.put(key, books, s.d.MetadataTTL)
				lists[i] = books
				return nil
			})
		}()
	}
	wg.Wait()

	failed := 0
	var firstErr error
	for i, err := range errs {
		if err == nil {
			continue
		}
		failed++
		if firstErr == nil {
			firstErr = err
		}
		s.log.Warn("catalog lookup failed", "language", langs[i], "what", strings.SplitN(what, "\x00", 2)[0], "err", err)
	}
	if failed == len(langs) {
		return nil, firstErr
	}
	return interleave(lists), nil
}

func uniqueLanguages(in []string) []string {
	var out []string
	for _, l := range in {
		l = strings.ToLower(strings.TrimSpace(l))
		if l != "" && !slices.Contains(out, l) {
			out = append(out, l)
		}
	}
	if len(out) == 0 {
		out = []string{"en"}
	}
	return out
}

// interleave merges the lists round-robin (first of each, second of each...) and drops books
// whose ASIN was already taken. The result is never nil.
func interleave(lists [][]model.Book) []model.Book {
	out := []model.Book{}
	seen := map[string]bool{}
	for i := 0; ; i++ {
		more := false
		for _, l := range lists {
			if i >= len(l) {
				continue
			}
			more = true
			if b := l[i]; !seen[b.ASIN] {
				seen[b.ASIN] = true
				out = append(out, b)
			}
		}
		if !more {
			return out
		}
	}
}
