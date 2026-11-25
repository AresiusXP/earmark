package service

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"sync"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/match"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/metadata"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/parse"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/sources"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

const defaultMaxReleases = 5

var (
	hashRe       = regexp.MustCompile(`^[0-9a-f]{40}$`)
	titleBrackRe = regexp.MustCompile(`\s*[(\[][^)\]]*[)\]]`)
)

// Book returns the book and its ranked releases, refreshing metadata and searching sources
// when the stored data is missing or stale.
func (s *Service) Book(ctx context.Context, cfg userconfig.Config, asin string) (*BookPage, error) {
	providers := s.configuredProviders(cfg)
	return s.book(ctx, asin, bookParams{
		region: cfg.Region, langs: uniqueLanguages(cfg.Languages), preferFormat: cfg.PreferFormat,
		showUncached: cfg.ShowUncached, maxReleases: cfg.MaxReleases, providers: providers,
		avail: func(ctx context.Context, hashes []string) map[string]map[string]bool {
			return s.availability(ctx, cfg.Debrid, providers, hashes)
		},
	})
}

// bookParams are the per-caller inputs of the book page: the Stremio path derives them from the
// sealed config, the /v1 API from the user's settings and keys (see BookFor).
type bookParams struct {
	region       string
	langs        []string
	preferFormat string
	showUncached bool
	maxReleases  int
	providers    []string // providers whose cache state may hide uncached releases (filterReleases)
	// avail returns the cache state per hash and provider as the caller may see it.
	avail func(ctx context.Context, hashes []string) map[string]map[string]bool
	// clientFiles also loads the phone-reported file lists (ReleaseView.TorrentFiles) and lets
	// them make a series pack visible.
	clientFiles bool
}

func (s *Service) book(ctx context.Context, asin string, p bookParams) (*BookPage, error) {
	if !metadata.ValidASIN(asin) {
		return nil, ErrNotFound
	}
	region, err := metadata.MarketplaceRegion(p.region)
	if err != nil {
		return nil, fmt.Errorf("service: %w", err)
	}
	book, err := s.prepare(ctx, region, asin)
	if err != nil {
		return nil, err
	}

	cands, err := s.d.Store.Candidates(ctx, asin)
	if err != nil {
		return nil, fmt.Errorf("service: load candidates: %w", err)
	}
	langs := p.langs

	items := make([]scored, len(cands))
	for i, c := range cands {
		items[i] = scored{Candidate: c}
	}
	// Language first, so no availability checks are spent on releases that cannot be shown.
	items = filterReleases(items, langs, nil, true)
	cached := p.avail(ctx, hashesOf(items))
	for i := range items {
		items[i].Cached = cached[items[i].Release.InfoHash]
	}
	items = filterReleases(items, langs, p.providers, p.showUncached)
	var client map[string][]model.File
	if p.clientFiles && s.d.UserStore != nil {
		hs := hashesOf(items)
		if client, err = s.d.UserStore.ClientFiles(ctx, hs); err != nil {
			s.log.Warn("load client files failed", "asin", asin, "err", err)
			client = nil
		}
	}
	items, packFiles := s.filterPacks(ctx, book, items, client)
	// A release whose file list is known but holds no audio (typically one big .zip/.rar: the
	// provider cannot play archives) is never shown. Unknown file lists stay.
	var singles []string
	for _, it := range items {
		if !it.Collection {
			singles = append(singles, it.Release.InfoHash)
		}
	}
	files, err := s.d.Store.Files(ctx, singles)
	if err != nil {
		s.log.Warn("load files failed", "asin", asin, "err", err)
		files = nil
	}
	items = dropUnplayable(items, files)
	items = rankReleases(items, langs, p.preferFormat)
	limit := p.maxReleases
	if limit <= 0 {
		limit = defaultMaxReleases
	}
	if len(items) > limit {
		items = items[:limit]
	}

	page := &BookPage{Book: book, Releases: make([]ReleaseView, len(items))}
	if at, err := s.d.Store.ReleasesSearchedAt(ctx, asin); err != nil {
		s.log.Warn("read search time failed", "asin", asin, "err", err)
	} else {
		page.SearchedAt = at
	}
	for i, it := range items {
		h := it.Release.InfoHash
		v := ReleaseView{
			Season:     i + 1,
			Release:    it.Release,
			Score:      it.Score,
			Collection: it.Collection,
			Cached:     copyCached(it.Cached),
		}
		if it.Collection {
			v.Files = packFiles[h]
			v.TorrentFiles = audioFiles(match.FilterFiles(book, client[h]), maxFilesPerRelease)
		} else {
			v.Files = audioFiles(files[h], maxFilesPerRelease)
			v.TorrentFiles = audioFiles(client[h], maxFilesPerRelease)
		}
		page.Releases[i] = v
	}
	return page, nil
}

// dropUnplayable removes non-pack releases whose stored file list is non-empty but contains no
// audio file (e.g. a single .zip of FLACs).
func dropUnplayable(items []scored, files map[string][]model.File) []scored {
	out := items[:0:0]
	for _, it := range items {
		if fs := files[it.Release.InfoHash]; !it.Collection && len(fs) > 0 && len(audioFiles(fs, 1)) == 0 {
			continue
		}
		out = append(out, it)
	}
	return out
}

// filterPacks verifies the multi-book packs among items: a pack stays only when its files are
// known and some of them belong to this book. It returns the surviving items and, per pack,
// the book's own audio files (natural order, capped). A pack whose files are unknown (never
// seen cached) cannot be shown: playing "the largest file" would pick another book.
func (s *Service) filterPacks(ctx context.Context, book model.Book, items []scored, client map[string][]model.File) ([]scored, map[string][]model.File) {
	var hashes []string
	for _, it := range items {
		if it.Collection {
			hashes = append(hashes, it.Release.InfoHash)
		}
	}
	if len(hashes) == 0 {
		return items, nil
	}
	stored, err := s.d.Store.Files(ctx, hashes)
	if err != nil {
		s.log.Warn("load files failed", "asin", book.ASIN, "err", err)
		stored = nil
	}
	own := make(map[string][]model.File, len(hashes))
	out := make([]scored, 0, len(items))
	for _, it := range items {
		if !it.Collection {
			out = append(out, it)
			continue
		}
		h := it.Release.InfoHash
		fs := audioFiles(match.FilterFiles(book, stored[h]), maxFilesPerRelease)
		// A pack whose debrid file list is unknown can still be shown when a phone reported
		// the files and some of them belong to this book (never for the Stremio path: nil).
		if len(fs) == 0 && len(audioFiles(match.FilterFiles(book, client[h]), 1)) == 0 {
			s.log.Debug("pack dropped: no known file belongs to the book", "asin", book.ASIN, "hash", h, "files", len(stored[h]))
			continue
		}
		own[h] = fs
		out = append(out, it)
	}
	return out, own
}

func hashesOf(items []scored) []string {
	out := make([]string, len(items))
	for i, it := range items {
		out[i] = it.Release.InfoHash
	}
	return out
}

func copyCached(m map[string]bool) map[string]bool {
	out := make(map[string]bool, len(m))
	for k, v := range m {
		out[k] = v
	}
	return out
}

// prepare makes sure the book metadata and its releases are fresh. Concurrent calls for the
// same book share one run, which uses a detached context so that the first caller going away
// does not cancel the work the others wait for.
func (s *Service) prepare(ctx context.Context, region, asin string) (model.Book, error) {
	return s.flights.do(ctx, region+"\x00"+asin, func() (model.Book, error) {
		pctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), s.d.PrepareTimeout)
		defer cancel()
		book, err := s.ensureBook(pctx, region, asin)
		if err != nil {
			return model.Book{}, err
		}
		s.ensureReleases(pctx, book)
		return book, nil
	})
}

func (s *Service) ensureBook(ctx context.Context, region, asin string) (model.Book, error) {
	rec, err := s.d.Store.GetBook(ctx, asin)
	if err != nil {
		return model.Book{}, fmt.Errorf("service: load book: %w", err)
	}
	if rec != nil && s.now().Sub(rec.FetchedAt) <= s.d.BookTTL {
		return rec.Book, nil
	}
	b, err := s.d.Metadata.Book(ctx, region, asin)
	if err != nil {
		if rec != nil {
			s.log.Warn("book refresh failed, serving stored metadata", "asin", asin, "err", err)
			return rec.Book, nil
		}
		if errors.Is(err, metadata.ErrNotFound) {
			return model.Book{}, ErrNotFound
		}
		return model.Book{}, fmt.Errorf("service: fetch book: %w", err)
	}
	if b.ASIN == "" {
		b.ASIN = asin
	}
	if err := s.d.Store.UpsertBook(ctx, b); err != nil {
		s.log.Warn("store book failed", "asin", asin, "err", err)
	}
	return b, nil
}

// ensureReleases searches the sources when the last search is missing or stale. Failures are
// logged, never returned: the page is served from what is stored.
func (s *Service) ensureReleases(ctx context.Context, book model.Book) {
	at, err := s.d.Store.ReleasesSearchedAt(ctx, book.ASIN)
	if err != nil {
		s.log.Warn("read search time failed", "asin", book.ASIN, "err", err)
		return
	}
	if !at.IsZero() {
		ttl := s.d.ReleaseTTL
		cands, err := s.d.Store.Candidates(ctx, book.ASIN)
		if err != nil {
			s.log.Warn("load candidates failed", "asin", book.ASIN, "err", err)
			return
		}
		if len(cands) == 0 {
			ttl = s.d.EmptyReleaseTTL
		}
		if s.now().Sub(at) <= ttl {
			return
		}
	}
	if s.inSearchCooldown(book.ASIN) {
		return
	}
	s.searchReleases(ctx, book)
}

func (s *Service) searchReleases(ctx context.Context, book model.Book) {
	q := sources.Query{Title: searchTitle(book.Title)}
	if len(book.Authors) > 0 {
		q.Author = book.Authors[0]
	}
	if q.Title == "" {
		return
	}
	queries := []sources.Query{q}
	// Books of a series are often shared in one pack named after the series, which a search for
	// the book's own title would never find.
	if series := searchTitle(book.Series); series != "" && !strings.EqualFold(series, q.Title) {
		queries = append(queries, sources.Query{Title: series, Author: q.Author})
	}
	nSources := 0
	for _, src := range s.d.Sources {
		if src != nil {
			nSources++
		}
	}
	// The queries run concurrently under one sub-deadline of their own (never longer than what
	// remains of ctx), so a slow source cannot eat the time the storing below needs.
	sctx, cancel := context.WithTimeout(ctx, s.d.SearchTimeout)
	defer cancel()
	type round struct {
		rels []model.Release
		errs []error
	}
	rounds := make([]round, len(queries))
	var wg sync.WaitGroup
	for i, qq := range queries {
		wg.Add(1)
		go func() {
			defer wg.Done()
			rounds[i].rels, rounds[i].errs = sources.MultiSearch(sctx, s.d.Sources, qq)
		}()
	}
	wg.Wait()

	var batches [][]model.Release
	allFailed := nSources > 0
	for _, r := range rounds {
		for _, err := range r.errs {
			s.log.Warn("source search failed", "asin", book.ASIN, "err", err)
		}
		if len(r.errs) < nSources {
			allFailed = false
		}
		batches = append(batches, r.rels)
	}
	if allFailed {
		s.noteSearchFailure(book.ASIN)
		return // every source failed: leave the book unsearched so the next visit retries
	}
	var rels []model.Release
	if len(batches) == 1 {
		rels = batches[0]
	} else {
		var all []model.Release
		for _, b := range batches {
			all = append(all, b...)
		}
		rels = sources.DedupeByHash(all)
	}

	var accepted []model.Release
	var matches []Match
	for _, rel := range rels {
		rel.InfoHash = strings.ToLower(rel.InfoHash)
		if !hashRe.MatchString(rel.InfoHash) {
			continue
		}
		rel.Parsed = parse.Title(rel.Title)
		if rel.Magnet == "" {
			rel.Magnet = sources.BuildMagnet(rel.InfoHash, rel.Title)
		}
		accepted = append(accepted, rel)
		if res := match.Score(book, rel); res.Match {
			matches = append(matches, Match{InfoHash: rel.InfoHash, Score: res.Score, Collection: res.Collection})
		}
	}
	// Storing must not depend on the budget the search just spent: a detached, fresh deadline.
	// A book left without candidates (e.g. the only slow source timed out) is retried after
	// EmptyReleaseTTL, see ensureReleases.
	pctx, pcancel := context.WithTimeout(context.WithoutCancel(ctx), s.d.PersistTimeout)
	defer pcancel()
	if len(accepted) > 0 {
		if err := s.d.Store.UpsertReleases(pctx, accepted); err != nil {
			s.log.Warn("store releases failed", "asin", book.ASIN, "err", err)
			return
		}
	}
	if err := s.d.Store.SetMatches(pctx, book.ASIN, matches); err != nil {
		s.log.Warn("store matches failed", "asin", book.ASIN, "err", err)
		return
	}
	if err := s.d.Store.MarkReleasesSearched(pctx, book.ASIN, s.now()); err != nil {
		s.log.Warn("mark searched failed", "asin", book.ASIN, "err", err)
	}
}

// searchTitle is the main title: no subtitle after ':' and no "(Unabridged)" style groups.
func searchTitle(title string) string {
	t := strings.TrimSpace(title)
	if i := strings.Index(t, ":"); i > 0 {
		t = t[:i]
	}
	if c := strings.TrimSpace(titleBrackRe.ReplaceAllString(t, "")); c != "" {
		t = c
	}
	return strings.TrimSpace(t)
}

// availability returns, per info hash, the cache state per configured provider. Entries that
// are unknown (no cache API, failed check) are absent. Fresh stored states are reused; the
// rest is asked from the provider in one batch. A failing provider never fails the page.
func (s *Service) availability(ctx context.Context, keys map[string]string, providers, hashes []string) map[string]map[string]bool {
	out := map[string]map[string]bool{}
	if len(providers) == 0 || len(hashes) == 0 {
		return out
	}
	results := make([]providerCheck, len(providers))
	var wg sync.WaitGroup
	for i, name := range providers {
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() {
				if r := recover(); r != nil {
					s.log.Error("availability check panicked", "provider", name, "panic", fmt.Sprint(r))
				}
			}()
			results[i] = s.checkProvider(ctx, name, keys[name], hashes)
		}()
	}
	wg.Wait()

	// Persist sequentially in provider order. File indexes are provider specific and the
	// store keeps one list per hash, so the first provider to report files wins and stored
	// lists are never replaced.
	var withFiles []string
	for _, r := range results {
		for h := range r.files {
			withFiles = append(withFiles, h)
		}
	}
	have := map[string][]model.File{}
	if len(withFiles) > 0 {
		if f, err := s.d.Store.Files(ctx, withFiles); err != nil {
			s.log.Warn("load files failed", "err", err)
		} else {
			have = f
		}
	}
	for i, name := range providers {
		r := results[i]
		if len(r.entries) > 0 {
			if err := s.setAvailability(ctx, name, r.entries); err != nil {
				s.log.Warn("store availability failed", "provider", name, "err", err)
			}
		}
		for h, files := range r.files {
			if len(have[h]) > 0 {
				continue
			}
			if err := s.d.Store.SaveFiles(ctx, h, files); err != nil {
				s.log.Warn("store files failed", "err", err)
				continue
			}
			have[h] = files
		}
		for h, cached := range r.known {
			if out[h] == nil {
				out[h] = map[string]bool{}
			}
			out[h][name] = cached
		}
	}
	return out
}

type providerCheck struct {
	known   map[string]bool
	entries []Availability
	files   map[string][]model.File
}

func (s *Service) checkProvider(ctx context.Context, name, key string, hashes []string) providerCheck {
	p, ok := s.d.Providers.Get(name)
	r := providerCheck{known: map[string]bool{}, files: map[string][]model.File{}}
	if !ok {
		return r
	}
	now := s.now()
	fresh, err := s.sharedAvailability(ctx, name, hashes, now.Add(-s.d.AvailabilityTTL))
	if err != nil {
		s.log.Warn("read availability failed", "provider", name, "err", err)
		fresh = nil
	}
	var missing []string
	for _, h := range hashes {
		if a, ok := fresh[h]; ok {
			r.known[h] = a.Cached
		} else {
			missing = append(missing, h)
		}
	}
	if len(missing) == 0 {
		return r
	}
	var res map[string]debrid.Cached
	err = safely(func() error {
		cctx, cancel := context.WithTimeout(ctx, s.d.CheckTimeout)
		defer cancel()
		var err error
		res, err = p.CheckCached(cctx, key, missing)
		return err
	})
	if err != nil {
		s.log.Warn("cache check failed", "provider", name, "err", redact(err, key))
		return r
	}
	if res == nil {
		return r // no cache API: stays unknown
	}
	for _, h := range missing {
		c, cached := res[h]
		r.known[h] = cached
		r.entries = append(r.entries, Availability{InfoHash: h, Cached: cached, CheckedAt: now})
		if cached && len(c.Files) > 0 {
			r.files[h] = c.Files
		}
	}
	return r
}
