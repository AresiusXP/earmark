package match

import (
	"path"
	"regexp"
	"strconv"
	"strings"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/parse"
)

const minFileCoverage = 0.75 // share of the book's main-title words a file/folder name must contain

var (
	// "Book 2", "Vol. 5", "Volume 3", "Bk 1", "Boek 4", "Deel 2", "#3". "Part N" is left out: it
	// numbers the chapters of one book as often as the books of a series.
	reFileExplicitPos = regexp.MustCompile(`(?i)(?:\b(?:book|bk|boek|deel|vol(?:ume)?)\b\.?|#)[\s._-]*#?\s*(\d{1,3}(?:\.\d{1,2})?)\b`)
	// A name that ends in a chapter marker numbers chapters, not books.
	reFileChapter     = regexp.MustCompile(`(?i)\b(?:chapter|chap|ch|part|pt|track|disc|disk|cd|kapitel|hoofdstuk|section)\b[\s._-]*\d+(?:\s*of\s*\d+)?\s*$`)
	reFileLeadingPos  = regexp.MustCompile(`^\s*0*(\d{1,3})\s*(?:[-–._)]+\s*|\s+)\S`)
	reFileTrailingPos = regexp.MustCompile(`[\s._-]0*(\d{1,2})\s*$`)
)

// verdict is what one path component (file base name or folder name) says about a book.
type verdict int

const (
	verdictNo      verdict = iota // says nothing about the book
	verdictVeto                   // names another book of the series explicitly
	verdictWeakNo                 // carries another (non-explicit) number; only a folder with an equal position can overrule it
	verdictWeakYes                // matches the book by its title alone (no position on either side)
	verdictYes                    // matches the book by title/series and an equal position
	verdictPosOnly                // explicit position equal to the book's, but the name mentions neither title nor series
)

// bookNames holds the per-book data FileMatches needs, computed once per FilterFiles call.
type bookNames struct {
	main   []string // main-title content tokens
	all    []string // main + subtitle tokens
	series []string
	pos    string
	ignore map[string]bool
}

func newBookNames(book model.Book) bookNames {
	b := bookNames{series: seriesTokens(book.Series), pos: strings.TrimSpace(book.SeriesPosition), ignore: map[string]bool{}}
	var sub []string
	b.main, sub = splitBookTitle(book)
	b.all = append(append([]string{}, b.main...), sub...)
	for _, list := range [][]string{book.Authors, book.Narrators, {book.Series}, {b.pos}} {
		for _, s := range list {
			for _, t := range nameTokens(s) {
				b.ignore[t] = true
				b.ignore[t+"s"] = true
			}
		}
	}
	return b
}

// FilterFiles returns the audio files of a (multi-book) torrent that belong to book, in the
// order given: natural (chapter) ordering is the caller's job.
func FilterFiles(book model.Book, files []model.File) []model.File {
	b := newBookNames(book)
	out := make([]model.File, 0, len(files))
	for _, f := range files {
		if b.fileMatches(f.Path) {
			out = append(out, f)
		}
	}
	return out
}

// FileMatches reports whether the audio file at p (a path inside a torrent, any separator)
// belongs to book. The file's base name decides; when it says nothing, the parent folders are
// consulted ("Dungeon Crawler Carl Book 1/Chapter 01.mp3"). A name that carries another
// series position than the book's never matches, even when it contains the book's words.
func FileMatches(book model.Book, p string) bool {
	return newBookNames(book).fileMatches(p)
}

func (b bookNames) fileMatches(p string) bool {
	if !model.IsAudioPath(p) {
		return false
	}
	parts := splitPath(p)
	if len(parts) == 0 {
		return false
	}
	base := b.judge(parts[len(parts)-1])
	switch base {
	case verdictVeto:
		return false
	case verdictYes, verdictWeakYes:
		return true
	}
	for i := len(parts) - 2; i >= 0; i-- {
		v := b.judge(parts[i])
		switch v {
		case verdictVeto:
			return false
		case verdictYes:
			return true
		case verdictNo, verdictWeakNo:
			// "Dungeon Crawler Carl/Book 3.m4b": an explicit equal position plus a folder
			// that names the series (or the book).
			if base == verdictPosOnly && v == verdictNo && b.mentions(parts[i]) {
				return true
			}
		case verdictWeakYes:
			// A folder named after the book only (no number) cannot vouch for a file that is
			// numbered differently ("Series/02 - Other Book.m4b").
			if base == verdictNo || base == verdictPosOnly {
				return true
			}
		}
	}
	return false
}

func splitPath(p string) []string {
	var out []string
	for _, s := range strings.Split(strings.ReplaceAll(p, "\\", "/"), "/") {
		if s = strings.TrimSpace(s); s != "" && s != "." {
			out = append(out, s)
		}
	}
	return out
}

// judge rates one path component against the book.
func (b bookNames) judge(name string) verdict {
	if model.IsAudioPath(name) {
		name = strings.TrimSuffix(name, path.Ext(name))
	}
	if looksLikeCollection(name) {
		return verdictNo
	}
	toks := nameTokens(name)
	pos, explicit := b.namePos(name)
	titleCov := coverage(b.main, toks)
	mentionsTitle := len(b.main) > 0 && titleCov >= minFileCoverage
	mentionsSeries := len(b.series) > 0 && coverage(b.series, toks) >= minSeriesCov

	posAgree := false
	explicitAgree := false
	if b.pos != "" && pos != "" {
		if !posEqual(b.pos, pos) {
			if explicit {
				return verdictVeto
			}
			return verdictWeakNo
		}
		posAgree, explicitAgree = true, explicit
	}
	switch {
	case mentionsTitle && posAgree, mentionsSeries && posAgree:
		return verdictYes
	case mentionsTitle && b.f1(toks, titleCov, pos) >= minTitleSim:
		return verdictWeakYes
	case explicitAgree:
		return verdictPosOnly
	}
	return verdictNo
}

// mentions reports whether a name contains the book's title or its series name.
func (b bookNames) mentions(name string) bool {
	toks := nameTokens(name)
	return len(b.main) > 0 && coverage(b.main, toks) >= minFileCoverage ||
		len(b.series) > 0 && coverage(b.series, toks) >= minSeriesCov
}

// f1 is the title similarity of a name without usable position: coverage of the book title
// words and precision (name words that belong to the book, its series, authors or are noise).
func (b bookNames) f1(toks []string, cov float64, pos string) float64 {
	good, bad := 0, 0
	for _, t := range toks {
		switch {
		case contains(b.all, t):
			good++
		case b.ignore[t] || noiseTokens[t] || reGroupTag.MatchString(t):
		case pos != "" && strings.TrimLeft(t, "0") == strings.TrimLeft(pos, "0"):
		default:
			bad++
		}
	}
	prec := 1.0
	if good+bad > 0 {
		prec = float64(good) / float64(good+bad)
	}
	if cov+prec == 0 {
		return 0
	}
	return 2 * cov * prec / (cov + prec)
}

// namePos extracts a series position from a file or folder name. explicit is true for an
// unambiguous marker ("Book 2", "Vol. 5", "#3"); a leading ("03 - Title") or trailing
// ("Series 03") number is a weaker hint. A number that is part of the book title ("Area 51")
// is not a position.
func (b bookNames) namePos(name string) (pos string, explicit bool) {
	if m := reFileExplicitPos.FindStringSubmatch(name); m != nil {
		return m[1], true
	}
	if reFileChapter.MatchString(name) {
		return "", false
	}
	if p := parse.Title(name).SeriesPosition; p != "" && !b.inTitle(p) {
		return p, false
	}
	for _, re := range []*regexp.Regexp{reFileLeadingPos, reFileTrailingPos} {
		if m := re.FindStringSubmatch(name); m != nil && !b.inTitle(m[1]) {
			return m[1], false
		}
	}
	return "", false
}

func (b bookNames) inTitle(num string) bool {
	return contains(b.all, strings.TrimLeft(num, "0"))
}

// nameTokens is tokens() with leading zeros of numbers removed ("02" -> "2").
func nameTokens(s string) []string {
	ts := tokens(s)
	for i, t := range ts {
		if n, err := strconv.Atoi(t); err == nil {
			ts[i] = strconv.Itoa(n)
		}
	}
	return ts
}

// IsPack reports whether the audio files look like several books of a series: at least two
// different explicit book numbers ("Book 1", "Vol. 2", "#3") in file or folder names. It is
// deliberately conservative: chapter numbers ("Part 3", "03 - Intro") never count.
func IsPack(files []model.File) bool {
	seen := map[float64]bool{}
	for _, f := range files {
		if !f.IsAudio() {
			continue
		}
		parts := splitPath(f.Path)
		for i := len(parts) - 1; i >= 0; i-- {
			name := parts[i]
			if model.IsAudioPath(name) {
				name = strings.TrimSuffix(name, path.Ext(name))
			}
			if looksLikeCollection(name) {
				continue
			}
			if m := reFileExplicitPos.FindStringSubmatch(name); m != nil {
				if n, err := strconv.ParseFloat(m[1], 64); err == nil {
					seen[n] = true
					break
				}
			}
		}
		if len(seen) >= 2 {
			return true
		}
	}
	return false
}
