// Package sources fetches candidate audiobook releases from torrent indexes.
package sources

import (
	"context"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

// Query is a book search. Title is required; Author helps narrow results.
type Query struct {
	Title    string
	Author   string // surname or full name, may be empty
	Language string // "en", "nl", "" any
}

// Source is one torrent index. Releases come back with Parsed left empty
// (the caller runs parse.Title) and InfoHash lowercase.
type Source interface {
	Name() string
	Search(ctx context.Context, q Query) ([]model.Release, error)
}
