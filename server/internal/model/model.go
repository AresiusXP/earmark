// Package model holds the plain domain types shared by every other package.
package model

import "time"

// Book is one audiobook work, identified by its Audible ASIN.
type Book struct {
	ASIN           string
	Title          string
	Subtitle       string
	Authors        []string
	Narrators      []string
	Series         string // empty when standalone
	SeriesPosition string // "1", "2.5"; empty when unknown
	Language       string // ISO 639-1: "en", "nl"
	RuntimeMin     int
	CoverURL       string
	Description    string
	Region         string // Audible marketplace: "us", "uk", "nl", "de"...
	ReleaseYear    int
	Genres         []string
}

// ParsedTitle is what parse.Title extracts from a raw torrent name.
// Zero values mean "unknown".
type ParsedTitle struct {
	Authors        []string // best-effort guess
	Title          string   // cleaned, without author/narrator/format noise
	Narrator       string
	Year           int
	Format         string // "m4b", "mp3", "flac", "m4a", "ogg"... lowercase
	BitrateKbps    int
	Language       string // ISO 639-1, "" unknown
	Unabridged     *bool  // nil unknown
	Series         string
	SeriesPosition string
}

// Release is one torrent that may contain a book.
type Release struct {
	InfoHash    string // lowercase 40-char hex
	Title       string // raw torrent name
	Source      string // "knaben", "torznab:<name>"...
	Magnet      string
	Size        int64
	Seeders     int
	PublishedAt time.Time
	Parsed      ParsedTitle
}

// File is one file inside a torrent as reported by the debrid service or the torrent metadata.
type File struct {
	Index int // index as used by the debrid service when requesting a link
	Path  string
	Size  int64
}

// IsAudio reports whether the file looks like playable audio.
func (f File) IsAudio() bool { return IsAudioPath(f.Path) }
