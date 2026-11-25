// Package service holds the application logic: it ties metadata, sources, parsing, matching,
// debrid and storage together. This file is the contract between the layers:
//
//	httpapi (Stremio protocol)  --uses-->  API   (implemented by Service in service.go)
//	Service                     --uses-->  Store (implemented by internal/store, Postgres)
package service

import (
	"context"
	"errors"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

var (
	// ErrNotFound: unknown book (ASIN) or release.
	ErrNotFound = errors.New("service: not found")
	// ErrNoProvider: the user config has no usable debrid provider for the request.
	ErrNoProvider = errors.New("service: no debrid provider configured")
)

// ---- API: what the HTTP layer calls --------------------------------------------------------

// API is the application surface used by the Stremio HTTP layer. All methods are safe for
// concurrent use. cfg is the already-opened, normalized user config.
type API interface {
	// Search finds books by free text (Audible), honoring cfg.Region and cfg.Languages.
	Search(ctx context.Context, cfg userconfig.Config, query string) ([]model.Book, error)
	// Discover lists best sellers for cfg.Region / cfg.Languages. page is 0-based.
	Discover(ctx context.Context, cfg userconfig.Config, page int) ([]model.Book, error)
	// Book returns the book and its ranked releases (at most cfg.MaxReleases), looking for
	// releases when none are known or the last search is stale. Returns ErrNotFound for
	// unknown ASINs.
	Book(ctx context.Context, cfg userconfig.Config, asin string) (*BookPage, error)
	// Streams returns the playable options for one video (one file of one release).
	Streams(ctx context.Context, cfg userconfig.Config, ref VideoRef) ([]Stream, error)
	// Resolve turns (provider, release, file) into a playable URL using the user's key.
	// fileIndex == AutoFile lets the service pick the file (largest audio file).
	Resolve(ctx context.Context, cfg userconfig.Config, provider, infoHash string, fileIndex int) (debrid.ResolveResult, error)
	// ValidateKeys checks every key against its provider; the map holds an error per
	// provider that failed (missing entry = ok). Unknown provider names yield an error.
	ValidateKeys(ctx context.Context, keys map[string]string) map[string]error
	// Providers lists the supported provider names (e.g. "torbox").
	Providers() []string
}

// AutoFile asks Resolve/VideoRef to pick the file itself (the largest audio file).
const AutoFile = -1

// BookPage is the data behind a Stremio meta response.
type BookPage struct {
	Book     model.Book
	Releases []ReleaseView // ranked best first; Season = index+1
	// SearchedAt is when the sources were last queried for the book (zero = never).
	SearchedAt time.Time
}

// ReleaseView is one release as shown on the book page.
type ReleaseView struct {
	Season  int // 1-based, rank at the time of the call
	Release model.Release
	Score   float64
	// Collection: the torrent is a multi-book pack (e.g. a whole series). Files holds only the
	// files that belong to THIS book (never empty for a shown collection).
	Collection bool
	// Cached[provider] is true when that provider has it cached; absent = unknown.
	Cached map[string]bool
	// Files are the audio files in natural (chapter) order; empty when unknown. When empty
	// the HTTP layer exposes one video with FileIndex AutoFile.
	Files []model.File
	// TorrentFiles are the audio files reported by phones (metainfo index); only the /v1 API
	// fills them. For a pack only this book's files.
	TorrentFiles []model.File
}

// VideoRef identifies one playable video. Its Stremio id is "abk:<ASIN>:<infohash>:<fileIndex>".
type VideoRef struct {
	ASIN      string
	InfoHash  string // lowercase 40 hex
	FileIndex int    // File.Index or AutoFile
}

// Stream is one playable option: one provider's way to play a video.
type Stream struct {
	Provider  string
	Cached    bool // provider has it ready (instant)
	Release   model.Release
	File      *model.File // nil when unknown
	FileIndex int
}

// ---- Store: what the Postgres layer implements ----------------------------------------------

// Candidate is a release matched to a book.
type Candidate struct {
	Release    model.Release
	Score      float64
	Collection bool // multi-book pack that mentions the book's series; verify with its files
}

// BookRecord is a stored book with the time its metadata was fetched.
type BookRecord struct {
	Book      model.Book
	FetchedAt time.Time
}

// Availability is the last known cache state of one hash at one provider.
type Availability struct {
	InfoHash  string
	Cached    bool
	CheckedAt time.Time
}

// Store is the persistence port. Implementations must be safe for concurrent use and return
// (nil, nil) / zero values, not errors, for "missing".
type Store interface {
	// GetBook returns nil when unknown.
	GetBook(ctx context.Context, asin string) (*BookRecord, error)
	// UpsertBook inserts or replaces the book and sets FetchedAt = now.
	UpsertBook(ctx context.Context, b model.Book) error

	// UpsertReleases inserts or updates releases by InfoHash (title, size, seeders, magnet,
	// published, parsed fields). Existing rows keep their stored files.
	UpsertReleases(ctx context.Context, rs []model.Release) error
	// SetMatches replaces the release links of a book with the given scored candidates.
	SetMatches(ctx context.Context, asin string, ms []Match) error
	// Candidates returns the stored releases linked to the book, best score first.
	Candidates(ctx context.Context, asin string) ([]Candidate, error)
	// ReleasesSearchedAt returns when sources were last queried for the book (zero = never).
	ReleasesSearchedAt(ctx context.Context, asin string) (time.Time, error)
	MarkReleasesSearched(ctx context.Context, asin string, at time.Time) error

	// SaveFilesIfAbsent stores the file list of a torrent unless one is stored already (first
	// writer wins, atomically: indexes are provider specific, a stored list is never replaced).
	// It reports whether this call stored the list.
	SaveFilesIfAbsent(ctx context.Context, infoHash string, files []model.File) (bool, error)
	// Files returns stored files per hash; hashes without files are absent from the map.
	Files(ctx context.Context, infoHashes []string) (map[string][]model.File, error)

	// SetAvailability upserts cache states for a provider.
	SetAvailability(ctx context.Context, provider string, entries []Availability) error
	// GetAvailability returns entries checked at or after newerThan.
	GetAvailability(ctx context.Context, provider string, infoHashes []string, newerThan time.Time) (map[string]Availability, error)
}

// Match links a release to a book with a score.
type Match struct {
	InfoHash   string
	Score      float64
	Collection bool
}
