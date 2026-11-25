// Package library holds the types and the storage port of the per-user library: saved/listening/
// finished books with their progress, and bookmarks (api/openapi.yaml, tag "library"). The Postgres
// implementation is store.Postgres; every method is scoped by the user id it receives, so a user
// can only ever reach their own rows.
package library

import (
	"context"
	"errors"
	"time"

	"github.com/AresiusXP/earmark/server/internal/model"
)

// Limits of the contract.
const (
	MaxEntries   = 2000 // library entries per user
	MaxBookmarks = 500  // bookmarks per book
	MaxPathBytes = 512
	MaxNoteChars = 280

	MaxDurations  = 2000                // entries of one durations report
	MinDurationMs = 1000                // 1 s
	MaxDurationMs = 48 * 60 * 60 * 1000 // 48 h
)

// Errors returned by the Store.
var (
	ErrNotFound       = errors.New("library: not found")
	ErrLimitReached   = errors.New("library: limit reached")
	ErrUnknownRelease = errors.New("library: unknown release")
	ErrUnknownBook    = errors.New("library: unknown book")
)

// Statuses of an entry.
const (
	StatusSaved     = "saved"
	StatusListening = "listening"
	StatusFinished  = "finished"
)

// ValidStatus reports whether s is a library status.
func ValidStatus(s string) bool {
	return s == StatusSaved || s == StatusListening || s == StatusFinished
}

// Entry is one book of a user's library with its progress.
type Entry struct {
	Book           model.Book
	Status         string
	ReleaseHash    *string
	PositionMs     int64
	FilePath       *string
	FilePositionMs int64
	Speed          float64
	UpdatedAt      int64 // client clock, ms since epoch
	// FinishedAt is when the status became finished (server clock); nil unless Status is finished.
	FinishedAt *time.Time
}

// Write is the content of a PUT. The book must already be in the catalog.
type Write struct {
	ASIN           string
	Status         string
	ReleaseHash    *string
	PositionMs     int64
	FilePath       *string
	FilePositionMs int64
	Speed          float64
	UpdatedAt      int64
	// Now is the server time stored as FinishedAt when this write turns the entry finished (zero:
	// time.Now). An entry that is already finished keeps its FinishedAt.
	Now time.Time
}

// Bookmark is a position the user marked.
type Bookmark struct {
	ID         string
	PositionMs int64
	Note       string
	CreatedAt  time.Time
	ClientID   string // "" when the client sent none
}

// FileDuration is the audio duration of one file of a release, by normalised path.
type FileDuration struct {
	Path       string
	DurationMs int64
}

// Store is the persistence port.
type Store interface {
	// ListEntries returns the user's entries, most recently updated first; status "" means all.
	ListEntries(ctx context.Context, userID, status string) ([]Entry, error)
	// GetEntry returns ErrNotFound when the user has no such entry.
	GetEntry(ctx context.Context, userID, asin string) (Entry, error)
	// PutEntry upserts the entry. Last write wins on UpdatedAt: a write older than the stored entry
	// is ignored and the stored entry is returned. ErrLimitReached when the entry is new and
	// the user already has MaxEntries; ErrUnknownBook / ErrUnknownRelease for dangling references.
	PutEntry(ctx context.Context, userID string, w Write) (Entry, error)
	// DeleteEntry removes the entry with its bookmarks (idempotent).
	DeleteEntry(ctx context.Context, userID, asin string) error
	// ListBookmarks returns the bookmarks of one book ordered by position (empty when none).
	ListBookmarks(ctx context.Context, userID, asin string) ([]Bookmark, error)
	// CreateBookmark: ErrNotFound when the book is not in the user's library, ErrLimitReached
	// at MaxBookmarks. With a clientID, a bookmark the user already has for this book under that id is
	// returned unchanged (nothing is created, the limit is not consulted).
	CreateBookmark(ctx context.Context, userID, asin string, positionMs int64, note, clientID string) (Bookmark, error)
	// EnsureSaved adds the book to the user's library as `saved` when it is not there (an existing entry,
	// whatever its status, is left alone) and reports whether it was added. ErrLimitReached at
	// MaxEntries, ErrUnknownBook when the catalog does not have the book. The entry gets updated_at 0
	// so that any later write from any device wins last-write-wins.
	EnsureSaved(ctx context.Context, userID, asin string) (added bool, err error)
	// DeleteBookmark is idempotent.
	DeleteBookmark(ctx context.Context, userID, asin, id string) error
}
