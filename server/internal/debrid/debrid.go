// Package debrid defines the interface every debrid service (TorBox, Real-Debrid...) implements.
package debrid

import (
	"context"
	"errors"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

var (
	ErrBadKey      = errors.New("debrid: invalid or expired api key")
	ErrRateLimited = errors.New("debrid: rate limited")
	ErrNotFound    = errors.New("debrid: torrent or file not found")
	ErrFailed      = errors.New("debrid: download failed on the provider")
)

// Cached describes a torrent the provider already has available for instant streaming.
type Cached struct {
	Files []model.File // may be empty when the provider does not report files
}

// AutoFile asks the provider to pick the file itself: the largest audio file of the torrent.
const AutoFile = -1

// ResolveRequest asks for a streamable URL of one file of one torrent.
type ResolveRequest struct {
	InfoHash  string
	Magnet    string
	FileIndex int // File.Index as returned by CheckCached / Files, or AutoFile
}

// ResolveResult is either a ready URL or a "still downloading" state.
type ResolveResult struct {
	URL      string
	Pending  bool // true: not ready, URL empty; caller should show a placeholder
	Progress int  // 0-100 when Pending and known
}

// Provider is one debrid service. API keys are passed per call: the instance is
// multi-user and keys come from each user's sealed addon config.
type Provider interface {
	Name() string // stable key: "torbox", "realdebrid"
	// Validate checks the key is accepted by the service.
	Validate(ctx context.Context, key string) error
	// CheckCached returns the subset of hashes (lowercase) that are cached. Providers
	// without a cache-check API return (nil, nil) and callers fall back to learned availability.
	CheckCached(ctx context.Context, key string, hashes []string) (map[string]Cached, error)
	// Resolve returns a streamable URL for one file, adding the torrent to the account if needed.
	Resolve(ctx context.Context, key string, req ResolveRequest) (ResolveResult, error)
}

// FileLister is optionally implemented by providers that can list the files of a torrent that
// is already in the user's account (and never add one). Index values are the same as in
// Resolve. Real-Debrid has no batch cache check, so this is how the app API shows the files
// of a prepared torrent. A hash that is not in the account answers ErrNotFound.
type FileLister interface {
	Files(ctx context.Context, key, hash string) ([]model.File, error)
}

// Deleter is optionally implemented by providers that can remove a torrent from the user's
// account (for example to retry a failed one). A hash that is not there is not an error.
type Deleter interface {
	Delete(ctx context.Context, key, hash string) error
}

// RetryAfterer is optionally implemented by the error behind ErrRateLimited when the provider
// said how long the key is blocked (errors.As to find it).
type RetryAfterer interface {
	RetryAfterDuration() time.Duration
}
