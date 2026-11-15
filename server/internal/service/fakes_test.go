package service

import (
	"bytes"
	"context"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/metadata"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/sources"
)

// ---- clock ---------------------------------------------------------------------------------

type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newClock() *fakeClock { return &fakeClock{t: time.Date(2026, 11, 16, 12, 0, 0, 0, time.UTC)} }

func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *fakeClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.t = c.t.Add(d)
}

// ---- log buffer ----------------------------------------------------------------------------

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

func newLogger(buf *syncBuffer) *slog.Logger {
	return slog.New(slog.NewTextHandler(buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
}

// ---- store ---------------------------------------------------------------------------------

// fakeStore mirrors the documented Store semantics in memory.
type fakeStore struct {
	mu       sync.Mutex
	now      func() time.Time
	books    map[string]BookRecord
	releases map[string]model.Release
	matches  map[string][]Match
	searched map[string]time.Time
	files    map[string][]model.File
	avail    map[string]map[string]Availability

	setMatchesCalls int
	saveFilesCalls  int
	failCandidates  error
}

func newFakeStore(now func() time.Time) *fakeStore {
	return &fakeStore{
		now:      now,
		books:    map[string]BookRecord{},
		releases: map[string]model.Release{},
		matches:  map[string][]Match{},
		searched: map[string]time.Time{},
		files:    map[string][]model.File{},
		avail:    map[string]map[string]Availability{},
	}
}

func (s *fakeStore) GetBook(_ context.Context, asin string) (*BookRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	r, ok := s.books[asin]
	if !ok {
		return nil, nil
	}
	return &r, nil
}

func (s *fakeStore) UpsertBook(_ context.Context, b model.Book) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.books[b.ASIN] = BookRecord{Book: b, FetchedAt: s.now()}
	return nil
}

func (s *fakeStore) UpsertReleases(_ context.Context, rs []model.Release) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, r := range rs {
		s.releases[r.InfoHash] = r
	}
	return nil
}

func (s *fakeStore) SetMatches(_ context.Context, asin string, ms []Match) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.setMatchesCalls++
	s.matches[asin] = append([]Match(nil), ms...)
	return nil
}

func (s *fakeStore) Candidates(_ context.Context, asin string) ([]Candidate, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.failCandidates != nil {
		return nil, s.failCandidates
	}
	var out []Candidate
	for _, m := range s.matches[asin] {
		if r, ok := s.releases[m.InfoHash]; ok {
			out = append(out, Candidate{Release: r, Score: m.Score})
		}
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Score > out[j].Score })
	return out, nil
}

func (s *fakeStore) ReleasesSearchedAt(_ context.Context, asin string) (time.Time, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.searched[asin], nil
}

func (s *fakeStore) MarkReleasesSearched(_ context.Context, asin string, at time.Time) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.searched[asin] = at
	return nil
}

func (s *fakeStore) SaveFiles(_ context.Context, hash string, files []model.File) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.saveFilesCalls++
	s.files[hash] = append([]model.File(nil), files...)
	return nil
}

func (s *fakeStore) Files(_ context.Context, hashes []string) (map[string][]model.File, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := map[string][]model.File{}
	for _, h := range hashes {
		if f := s.files[h]; len(f) > 0 {
			out[h] = append([]model.File(nil), f...)
		}
	}
	return out, nil
}

func (s *fakeStore) SetAvailability(_ context.Context, provider string, entries []Availability) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.avail[provider] == nil {
		s.avail[provider] = map[string]Availability{}
	}
	for _, e := range entries {
		s.avail[provider][e.InfoHash] = e
	}
	return nil
}

func (s *fakeStore) GetAvailability(_ context.Context, provider string, hashes []string, newerThan time.Time) (map[string]Availability, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := map[string]Availability{}
	for _, h := range hashes {
		if e, ok := s.avail[provider][h]; ok && !e.CheckedAt.Before(newerThan) {
			out[h] = e
		}
	}
	return out, nil
}

// seed stores releases and links them to asin with the given scores (bypassing sources).
func (s *fakeStore) seed(asin string, rels ...model.Release) {
	var ms []Match
	for i, r := range rels {
		s.releases[r.InfoHash] = r
		ms = append(ms, Match{InfoHash: r.InfoHash, Score: 0.9 - float64(i)*0.01})
	}
	s.matches[asin] = ms
}

func (s *fakeStore) availOf(provider, hash string) (Availability, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	a, ok := s.avail[provider][hash]
	return a, ok
}

func (s *fakeStore) filesOf(hash string) []model.File {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]model.File(nil), s.files[hash]...)
}

// ---- metadata ------------------------------------------------------------------------------

type fakeMeta struct {
	mu       sync.Mutex
	books    map[string]model.Book // by ASIN
	bookErr  error
	search   map[string][]model.Book // key region|lang|query
	discover map[string][]model.Book // key region|lang|page
	errs     map[string]error        // by lang for Search/Discover
	calls    map[string]int
	regions  []string
	gate     chan struct{} // when set, Book blocks until closed
}

func newFakeMeta() *fakeMeta {
	return &fakeMeta{
		books:    map[string]model.Book{},
		search:   map[string][]model.Book{},
		discover: map[string][]model.Book{},
		errs:     map[string]error{},
		calls:    map[string]int{},
	}
}

func (m *fakeMeta) count(name string) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.calls[name]
}

func (m *fakeMeta) Search(_ context.Context, region, language, query string) ([]model.Book, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.calls["search"]++
	m.regions = append(m.regions, region)
	if err := m.errs[language]; err != nil {
		return nil, err
	}
	return m.search[region+"|"+language+"|"+strings.ToLower(query)], nil
}

func (m *fakeMeta) Discover(_ context.Context, region, language string, page int) ([]model.Book, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.calls["discover"]++
	m.regions = append(m.regions, region)
	if err := m.errs[language]; err != nil {
		return nil, err
	}
	return m.discover[fmt.Sprintf("%s|%s|%d", region, language, page)], nil
}

func (m *fakeMeta) Book(_ context.Context, region, asin string) (model.Book, error) {
	m.mu.Lock()
	m.calls["book"]++
	m.regions = append(m.regions, region)
	gate, bookErr := m.gate, m.bookErr
	b, ok := m.books[asin]
	m.mu.Unlock()
	if gate != nil {
		<-gate
	}
	if bookErr != nil {
		return model.Book{}, bookErr
	}
	if !ok {
		return model.Book{}, fmt.Errorf("fake: %w", metadata.ErrNotFound)
	}
	return b, nil
}

// ---- sources -------------------------------------------------------------------------------

type fakeSource struct {
	name string
	mu   sync.Mutex
	rels []model.Release
	err  error
	gate chan struct{} // when set, Search blocks until closed or ctx ends
	// recorded
	queries []querySeen
}

type querySeen struct {
	Title, Author, Language string
	ctxErrAtReturn          error
}

func (s *fakeSource) Name() string { return s.name }

func (s *fakeSource) Search(ctx context.Context, q sources.Query) ([]model.Release, error) {
	s.mu.Lock()
	gate, rels, err := s.gate, append([]model.Release(nil), s.rels...), s.err
	idx := len(s.queries)
	s.queries = append(s.queries, querySeen{Title: q.Title, Author: q.Author, Language: q.Language})
	s.mu.Unlock()
	if gate != nil {
		select {
		case <-gate:
		case <-ctx.Done():
		}
	}
	s.mu.Lock()
	s.queries[idx].ctxErrAtReturn = ctx.Err()
	s.mu.Unlock()
	if ctx.Err() != nil {
		return nil, ctx.Err()
	}
	if err != nil {
		return nil, err
	}
	return rels, nil
}

func (s *fakeSource) calls() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.queries)
}

func (s *fakeSource) setErr(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.err = err
}

// ---- debrid --------------------------------------------------------------------------------

type fakeProvider struct {
	name string
	mu   sync.Mutex

	cached   map[string]debrid.Cached
	noAPI    bool // CheckCached returns (nil, nil)
	checkErr error

	resolveRes debrid.ResolveResult
	resolveErr error
	validate   error
	validateFn func(ctx context.Context, key string) error

	checks   [][]string
	resolves []debrid.ResolveRequest
	keysSeen []string
	gate     chan struct{} // when set, CheckCached blocks until closed
}

func (p *fakeProvider) Name() string { return p.name }

func (p *fakeProvider) Validate(ctx context.Context, key string) error {
	p.mu.Lock()
	fn, err := p.validateFn, p.validate
	p.mu.Unlock()
	if fn != nil {
		return fn(ctx, key)
	}
	return err
}

func (p *fakeProvider) CheckCached(_ context.Context, key string, hashes []string) (map[string]debrid.Cached, error) {
	p.mu.Lock()
	p.checks = append(p.checks, append([]string(nil), hashes...))
	p.keysSeen = append(p.keysSeen, key)
	gate, noAPI, err := p.gate, p.noAPI, p.checkErr
	out := map[string]debrid.Cached{}
	for _, h := range hashes {
		if c, ok := p.cached[h]; ok {
			out[h] = c
		}
	}
	p.mu.Unlock()
	if gate != nil {
		<-gate
	}
	if err != nil {
		return nil, err
	}
	if noAPI {
		return nil, nil
	}
	return out, nil
}

func (p *fakeProvider) Resolve(_ context.Context, key string, req debrid.ResolveRequest) (debrid.ResolveResult, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.resolves = append(p.resolves, req)
	p.keysSeen = append(p.keysSeen, key)
	return p.resolveRes, p.resolveErr
}

func (p *fakeProvider) checkCount() int {
	p.mu.Lock()
	defer p.mu.Unlock()
	return len(p.checks)
}

func (p *fakeProvider) lastCheck() []string {
	p.mu.Lock()
	defer p.mu.Unlock()
	if len(p.checks) == 0 {
		return nil
	}
	return p.checks[len(p.checks)-1]
}
