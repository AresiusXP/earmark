package service

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/metadata"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

// sharedCacheProviders are the providers whose cache state is the same for every account, so one
// user's check may be stored in the shared `availability` table and shown to everybody. Anything
// else (Real-Debrid: "cached" only means this account has the torrent) is per key and lives in
// UserStore, never in the shared table.
var sharedCacheProviders = []string{"torbox"}

// SharedCache reports whether the provider's cache state is the same for every account.
func SharedCache(provider string) bool { return slices.Contains(sharedCacheProviders, provider) }

// AudioFiles keeps the audio files in natural path order, at most max of them (0 = all).
func AudioFiles(files []model.File, max int) []model.File { return audioFiles(files, max) }

// setAvailability and sharedAvailability are the only users of the shared `availability` table:
// both ignore every provider that is not a shared-cache provider, so a stale row of another
// provider (written before the rule existed) is never read and never written.
func (s *Service) setAvailability(ctx context.Context, provider string, entries []Availability) error {
	if !SharedCache(provider) {
		return nil
	}
	return s.d.Store.SetAvailability(ctx, provider, entries)
}

func (s *Service) sharedAvailability(ctx context.Context, provider string, hashes []string, newerThan time.Time) (map[string]Availability, error) {
	if !SharedCache(provider) {
		return nil, nil
	}
	return s.d.Store.GetAvailability(ctx, provider, hashes, newerThan)
}

// Prefs are the catalog preferences of one user (settings of the /v1 API).
type Prefs struct {
	Region    string
	Languages []string
}

// UserKey is one of the caller's own debrid keys. Fingerprint identifies the account behind it.
type UserKey struct {
	Provider    string
	Fingerprint string
	APIKey      string
}

// BookRequest is a book page computed for one caller.
type BookRequest struct {
	ASIN         string
	Region       string
	Languages    []string
	PreferFormat string
	MaxReleases  int // 0 = default
	// Keys are the caller's working keys, at most one per provider. Cache states of shared-cache
	// providers (TorBox) are checked with the caller's key (APIKey); the others are read from
	// UserStore by Fingerprint only (APIKey stays empty, it is never needed). No key: no cache state, the releases are listed all the same.
	Keys []UserKey
}

// UserStore is the per-user part of the persistence (implemented by store.Postgres). It is
// optional: without it the /v1 book page has no torrent_files and no per-key cache state.
type UserStore interface {
	GetRelease(ctx context.Context, infoHash string) (*model.Release, error)
	// InsertReleaseIfAbsent adds a release known only by its magnet; an existing row is kept.
	InsertReleaseIfAbsent(ctx context.Context, r model.Release) (bool, error)
	ClientFiles(ctx context.Context, infoHashes []string) (map[string][]model.File, error)
	SaveClientFiles(ctx context.Context, infoHash string, files []model.File) (stored []model.File, created bool, err error)
	// GetUserAvailability / SetUserAvailability hold the cache state a single key (by
	// fingerprint) saw at a provider.
	GetUserAvailability(ctx context.Context, fingerprint, provider string, infoHashes []string, newerThan time.Time) (map[string]bool, error)
	SetUserAvailability(ctx context.Context, fingerprint, provider, infoHash string, cached bool, at time.Time) error
}

// BookFor returns the book page for the /v1 API. Like Book it looks for releases when none are
// known or the last search is stale, but it lists every release (nothing is hidden for being
// uncached), adds the phone-reported file lists, and takes the caller's keys explicitly.
func (s *Service) BookFor(ctx context.Context, req BookRequest) (*BookPage, error) {
	var shared []string
	keys := map[string]string{}
	var perKey []UserKey
	for _, k := range req.Keys {
		switch {
		case SharedCache(k.Provider):
			if k.APIKey != "" {
				keys[k.Provider] = k.APIKey
				shared = append(shared, k.Provider)
			}
		case k.Fingerprint != "":
			perKey = append(perKey, k)
		}
	}
	slices.Sort(shared)
	return s.book(ctx, req.ASIN, bookParams{
		region: req.Region, langs: uniqueLanguages(req.Languages), preferFormat: req.PreferFormat,
		showUncached: true, maxReleases: req.MaxReleases, clientFiles: true,
		avail: func(ctx context.Context, hashes []string) map[string]map[string]bool {
			out := s.availability(ctx, keys, shared, hashes)
			if s.d.UserStore == nil {
				return out
			}
			for _, k := range perKey {
				got, err := s.d.UserStore.GetUserAvailability(ctx, k.Fingerprint, k.Provider, hashes, s.now().Add(-s.d.AvailabilityTTL))
				if err != nil {
					s.log.Warn("read user availability failed", "provider", k.Provider, "err", err)
					continue
				}
				for h, cached := range got {
					if out[h] == nil {
						out[h] = map[string]bool{}
					}
					out[h][k.Provider] = cached
				}
			}
			return out
		},
	})
}

// EnsureBook returns the stored book, fetching it into the catalog first when it is unknown (or
// stale and refetchable). ErrNotFound when the metadata sources do not know the ASIN.
func (s *Service) EnsureBook(ctx context.Context, region, asin string) (model.Book, error) {
	if !metadata.ValidASIN(asin) {
		return model.Book{}, ErrNotFound
	}
	mr, err := metadata.MarketplaceRegion(region)
	if err != nil {
		return model.Book{}, fmt.Errorf("service: %w", err)
	}
	return s.flights.do(ctx, mr+"\x00"+asin+"\x00book", func() (model.Book, error) {
		pctx, cancel := context.WithTimeout(context.WithoutCancel(ctx), s.d.PrepareTimeout)
		defer cancel()
		return s.ensureBook(pctx, mr, asin)
	})
}

// ChapterSource is implemented by *metadata.Client; the metadata dependency may implement it.
type ChapterSource interface {
	ChapterList(ctx context.Context, region, asin string) (metadata.ChapterList, error)
}

// Chapters is ChapterList without the branding lengths.
func (s *Service) Chapters(ctx context.Context, region, asin string) (runtimeMs int64, chapters []metadata.Chapter, err error) {
	cl, err := s.ChapterList(ctx, region, asin)
	return cl.RuntimeMs, cl.Chapters, err
}

// ChapterList is the chapter marks of a book: the stored book's region first (a book usually exists in
// one Audnexus region), then the caller's. Unknown (or no chapter source) gives an empty list;
// RuntimeMs falls back to the catalog runtime.
func (s *Service) ChapterList(ctx context.Context, region, asin string) (metadata.ChapterList, error) {
	if !metadata.ValidASIN(asin) {
		return metadata.ChapterList{}, ErrNotFound
	}
	var regions []string
	var fallback int64
	if rec, err := s.d.Store.GetBook(ctx, asin); err != nil {
		s.log.Warn("load book failed", "asin", asin, "err", err)
	} else if rec != nil {
		fallback = int64(rec.Book.RuntimeMin) * 60_000
		if rec.Book.Region != "" {
			regions = append(regions, rec.Book.Region)
		}
	}
	regions = append(regions, region)
	empty := metadata.ChapterList{RuntimeMs: fallback, Chapters: []metadata.Chapter{}}
	src, ok := s.d.Metadata.(ChapterSource)
	if !ok {
		return empty, nil
	}
	var lastErr error
	tried := map[string]bool{}
	for _, r := range regions {
		mr, merr := metadata.MarketplaceRegion(r)
		if merr != nil || tried[mr] {
			continue
		}
		tried[mr] = true
		cl, cerr := src.ChapterList(ctx, mr, asin)
		switch {
		case cerr == nil:
			if cl.RuntimeMs <= 0 {
				cl.RuntimeMs = fallback
			}
			cl.Chapters = nonNilChapters(cl.Chapters)
			return cl, nil
		case errors.Is(cerr, metadata.ErrNotFound):
		default:
			lastErr = cerr
		}
	}
	if lastErr != nil {
		return metadata.ChapterList{}, fmt.Errorf("service: chapters: %w", lastErr)
	}
	return empty, nil
}

func nonNilChapters(c []metadata.Chapter) []metadata.Chapter {
	if c == nil {
		return []metadata.Chapter{}
	}
	return c
}
