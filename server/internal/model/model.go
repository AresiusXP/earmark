// Package model holds the plain domain types shared by every other package.
package model

import (
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

// Book is one audiobook work, identified by its Audible ASIN.
type Book struct {
	ASIN           string
	Title          string
	Subtitle       string
	Authors        []string
	Narrators      []string
	Series         string // empty when standalone
	SeriesPosition string // "1", "2.5"; empty when unknown
	Language       string // ISO 639-1: "en", "nl", "es"
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
	// DurationMs is the audio duration a phone reported (0 = unknown); only the /v1 book page fills it.
	DurationMs int64
}

// MaxPathBytes bounds a file path of the /v1 API.
const MaxPathBytes = 512

// NormalizePath is the canonical form of a torrent file path, the key by which reports from phones
// are matched with the file lists: separators become "/", empty and "." segments go, and NUL/control
// characters, ".." segments, invalid UTF-8, empty and over-long (> MaxPathBytes) paths are rejected.
func NormalizePath(p string) (string, bool) {
	if !utf8.ValidString(p) || strings.IndexFunc(p, unicode.IsControl) >= 0 {
		return "", false
	}
	var segs []string
	for _, s := range strings.Split(strings.ReplaceAll(p, "\\", "/"), "/") {
		switch s {
		case "", ".":
		case "..":
			return "", false
		default:
			segs = append(segs, s)
		}
	}
	out := strings.Join(segs, "/")
	if out == "" || len(out) > MaxPathBytes {
		return "", false
	}
	return out, true
}

// IsAudio reports whether the file looks like playable audio.
func (f File) IsAudio() bool { return IsAudioPath(f.Path) }
