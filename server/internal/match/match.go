// Package match decides whether a torrent release is plausibly the audiobook that was asked
// for. It is pure: no I/O. Release.Parsed must already be filled (see package parse).
package match

import (
	"fmt"
	"math"
	"regexp"
	"strconv"
	"strings"
	"unicode"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

// Threshold is the minimum Score for Result.Match. Hard gates (title, author, collection)
// additionally cap the score below it, so Match == (Score >= Threshold) always holds.
const Threshold = 0.70

const (
	minTitleSim  = 0.75 // hard gate on title F1 (coverage of the book title x precision of the release title)
	gateCap      = 0.49
	collectCap   = 0.20
	weightTitle  = 0.60
	weightAuthor = 0.25
	mb           = 1 << 20
)

// Result is the outcome of scoring one release against one book.
type Result struct {
	Score   float64 // 0..1
	Match   bool
	Reasons []string
}

var stopwords = map[string]bool{
	"the": true, "a": true, "an": true, "and": true, "of": true, "in": true, "to": true,
	"de": true, "het": true, "een": true, "en": true, "der": true, "die": true, "das": true,
	"und": true, "le": true, "la": true, "les": true, "el": true, "von": true, "van": true,
}

// Tokens that carry no title information when they show up in a release title.
var noiseTokens = map[string]bool{
	"audiobook": true, "audiobooks": true, "audio": true, "book": true, "books": true,
	"unabridged": true, "abridged": true, "novel": true, "series": true, "edition": true,
	"mp3": true, "m4b": true, "m4a": true, "flac": true, "ogg": true,
}

var accentFold = map[rune]string{
	'à': "a", 'á': "a", 'â': "a", 'ã': "a", 'ä': "a", 'å': "a", 'ā': "a", 'ą': "a",
	'ç': "c", 'ć': "c", 'č': "c",
	'è': "e", 'é': "e", 'ê': "e", 'ë': "e", 'ē': "e", 'ę': "e", 'ě': "e",
	'ì': "i", 'í': "i", 'î': "i", 'ï': "i", 'ī': "i",
	'ł': "l", 'ñ': "n", 'ń': "n", 'ň': "n",
	'ò': "o", 'ó': "o", 'ô': "o", 'õ': "o", 'ö': "o", 'ø': "o", 'ō': "o", 'œ': "oe",
	'ř': "r", 'š': "s", 'ś': "s", 'ß': "ss", 'ť': "t",
	'ù': "u", 'ú': "u", 'û': "u", 'ü': "u", 'ū': "u", 'ů': "u",
	'ý': "y", 'ÿ': "y", 'ž': "z", 'ź': "z", 'ż': "z", 'æ': "ae",
}

var (
	reBookParens   = regexp.MustCompile(`[(\[][^)\]]*[)\]]`)
	reBookSuffix   = regexp.MustCompile(`(?i)[,:\-–]?\s*\b(?:book|volume|vol\.?|part)\s*\d+\s*$`)
	reBookUnabr    = regexp.MustCompile(`(?i)\bunabridged\b`)
	reCollections  = regexp.MustCompile(`(?i)\bbox\s?-?sets?\b|\bboxed\s+set\b|\bcollections?\b|\banthology\b|\bomnibus\b|\bcompendium\b|\bbundel\b|\bverzameling\b|\bpack\b|\b(?:tri|du|tetra|penta|hexa|quadri)logy\b|\btrilogie\b|\bcomplete\b.{0,30}\b(?:series|serie|saga|collection|audiobooks?|books|set|works|cycle)\b|\bseries\s*\(?\s*complete\b|\balle\s+delen\b|\b(?:all|alle)\s+\d+\b|\b\d+\s+(?:books|boeken|audiobooks|delen)\b|\b(?:books?|boeken|deel|delen|vol(?:umes?)?)\s*\d+\s*(?:-|–|to|tot|t/m|through|&)\s*\d+`)
	reNumberRange  = regexp.MustCompile(`(?i)(?:^|[^\d-])(\d{1,2})\s*(?:-|–|to|tot|t/m|through)\s*(\d{1,2})(?:[^\d]|$)`)
	reSeriesWord   = regexp.MustCompile(`(?i)\bseries\b`)
	reSurnameNoise = regexp.MustCompile(`^(?:jr|sr|ii|iii|phd|dr|md)$`)
)

func fold(s string) string {
	var b strings.Builder
	for _, r := range strings.ToLower(s) {
		if f, ok := accentFold[r]; ok {
			b.WriteString(f)
			continue
		}
		switch {
		case r == '\'' || r == '’' || r == '`':
			// "philosopher's" -> "philosophers"
		case r == '&':
			b.WriteString(" and ")
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			b.WriteRune(r)
		default:
			b.WriteByte(' ')
		}
	}
	return b.String()
}

func tokens(s string) []string { return strings.Fields(fold(s)) }

// contentTokens drops articles and conjunctions; if nothing is left the plain tokens are used.
func contentTokens(s string) []string {
	all := tokens(s)
	var out []string
	for _, t := range all {
		if !stopwords[t] {
			out = append(out, t)
		}
	}
	if len(out) == 0 {
		return all
	}
	return out
}

func tokEq(a, b string) bool {
	if a == b {
		return true
	}
	if len(a) < 5 || len(b) < 5 {
		return false
	}
	return levenshtein(a, b) <= 1
}

func levenshtein(a, b string) int {
	ra, rb := []rune(a), []rune(b)
	prev := make([]int, len(rb)+1)
	cur := make([]int, len(rb)+1)
	for j := range prev {
		prev[j] = j
	}
	for i := 1; i <= len(ra); i++ {
		cur[0] = i
		for j := 1; j <= len(rb); j++ {
			cost := 1
			if ra[i-1] == rb[j-1] {
				cost = 0
			}
			cur[j] = min(prev[j]+1, cur[j-1]+1, prev[j-1]+cost)
		}
		prev, cur = cur, prev
	}
	return prev[len(rb)]
}

func count(set []string, t string) int {
	n := 0
	for _, x := range set {
		if x == t {
			n++
		}
	}
	return n
}

func contains(set []string, t string) bool {
	for _, s := range set {
		if tokEq(s, t) {
			return true
		}
	}
	return false
}

// coverage is the fraction of want that is present (fuzzily) in have.
func coverage(want, have []string) float64 {
	if len(want) == 0 {
		return 0
	}
	n := 0
	for _, w := range want {
		if contains(have, w) {
			n++
		}
	}
	return float64(n) / float64(len(want))
}

// splitBookTitle returns the main title and subtitle tokens of the book; Audible style
// decorations ("(Unabridged)", ", Book 1") are removed and "Main: Sub" is split.
func splitBookTitle(b model.Book) (main, sub []string) {
	clean := func(s string) string {
		s = reBookParens.ReplaceAllString(s, " ")
		s = reBookUnabr.ReplaceAllString(s, " ")
		return strings.TrimSpace(reBookSuffix.ReplaceAllString(strings.TrimSpace(s), ""))
	}
	t := clean(b.Title)
	st := clean(b.Subtitle)
	if i := strings.Index(t, ":"); i > 0 && len(contentTokens(t[:i])) > 0 && st == "" {
		t, st = t[:i], t[i+1:]
	}
	return contentTokens(t), contentTokens(st)
}

func surname(name string) string {
	toks := tokens(name)
	for i := len(toks) - 1; i >= 0; i-- {
		if len(toks[i]) >= 2 && !reSurnameNoise.MatchString(toks[i]) {
			return toks[i]
		}
	}
	return ""
}

func looksLikeCollection(s string) bool {
	if reCollections.MatchString(s) {
		return true
	}
	for _, m := range reNumberRange.FindAllStringSubmatch(s, -1) {
		a, _ := strconv.Atoi(m[1])
		b, _ := strconv.Atoi(m[2])
		if b > a {
			return true
		}
	}
	return false
}

func posEqual(a, b string) bool {
	fa, ea := strconv.ParseFloat(strings.TrimSpace(a), 64)
	fb, eb := strconv.ParseFloat(strings.TrimSpace(b), 64)
	if ea != nil || eb != nil {
		return strings.EqualFold(strings.TrimSpace(a), strings.TrimSpace(b))
	}
	return math.Abs(fa-fb) < 1e-9
}

func sameSeries(bookSeries, relSeries string, raw []string) bool {
	bt := contentTokens(bookSeries)
	if len(bt) == 0 {
		return true
	}
	if rt := contentTokens(relSeries); len(rt) > 0 {
		common := 0
		for _, t := range bt {
			if contains(rt, t) {
				common++
			}
		}
		if float64(common)/float64(min(len(bt), len(rt))) >= 0.6 {
			return true
		}
	}
	return coverage(bt, raw) == 1
}

// Score rates how well rel matches book.
//
// Required (hard gates, each caps the score below Threshold): title similarity >= 0.75, at
// least one book-author surname in the raw name or parsed authors, and the release is not a
// collection (unless the book itself is one). Soft factors: narrator (+), language match (+) or
// explicit mismatch (-), plausible size for the runtime (+) or sample/huge size (-), series
// position equal (+) or different book of the same series (-).
func Score(book model.Book, rel model.Release) Result {
	var res Result
	why := func(format string, a ...any) { res.Reasons = append(res.Reasons, fmt.Sprintf(format, a...)) }
	p := rel.Parsed
	raw := tokens(rel.Title)

	// Title: F1 of coverage (book title words found in the release) and precision (release
	// title words that belong to the book). Words of authors, narrators and the series are
	// ignored for precision, so "Author - Series 3 - Title" does not hurt.
	bMain, bSub := splitBookTitle(book)
	parsedTitle := contentTokens(p.Title)
	if p.Title == "" {
		parsedTitle = nil
	}
	cov := math.Max(coverage(bMain, raw), coverage(bMain, parsedTitle))
	ignore := map[string]bool{}
	for _, list := range [][]string{book.Authors, p.Authors, book.Narrators, {p.Narrator}, {book.Series}, {p.Series}} {
		for _, s := range list {
			for _, t := range tokens(s) {
				ignore[t] = true
			}
		}
	}
	for _, pos := range []string{book.SeriesPosition, p.SeriesPosition} {
		if pos != "" {
			ignore[strings.TrimSpace(pos)] = true
		}
	}
	bAll := append(append([]string{}, bMain...), bSub...)
	prec := 0.7 // parser found no title: neutral-ish
	if len(parsedTitle) > 0 {
		good, bad := 0, 0
		for _, t := range parsedTitle {
			switch {
			case contains(bAll, t):
				good++
			case ignore[t] || noiseTokens[t]:
			default:
				bad++
			}
		}
		prec = 1
		if good+bad > 0 {
			prec = float64(good) / float64(good+bad)
		}
	}
	titleSim := 0.0
	if cov+prec > 0 {
		titleSim = 2 * cov * prec / (cov + prec)
	}
	why("title similarity %.2f (coverage %.2f, precision %.2f)", titleSim, cov, prec)

	// Authors: any book-author surname in the raw name or parsed authors.
	have := append([]string{}, raw...)
	for _, a := range p.Authors {
		have = append(have, tokens(a)...)
	}
	var parsedAuthorTokens []string
	for _, a := range p.Authors {
		parsedAuthorTokens = append(parsedAuthorTokens, tokens(a)...)
	}
	matched := 0
	for _, a := range book.Authors {
		s := surname(a)
		if s == "" || !contains(have, s) {
			continue
		}
		// A surname that is also a word of the book title ("The King" by Stephen King) is no
		// proof of the author on its own: it must be in the parsed authors or appear again.
		if contains(bAll, s) && !contains(parsedAuthorTokens, s) && count(raw, s) < 2 {
			continue
		}
		matched++
	}
	authorScore := 0.0
	if matched > 0 {
		authorScore = 0.6 + 0.4*float64(matched)/float64(len(book.Authors))
		why("author match %d/%d", matched, len(book.Authors))
	} else {
		why("no book author found in release name")
	}

	score := weightTitle*titleSim + weightAuthor*authorScore

	for _, n := range book.Narrators {
		if s := surname(n); s != "" && (contains(tokens(p.Narrator), s) || contains(raw, s)) {
			score += 0.07
			why("narrator match (+0.07)")
			break
		}
	}

	if book.Language != "" && p.Language != "" {
		if strings.EqualFold(book.Language, p.Language) {
			score += 0.04
			why("language %s matches (+0.04)", p.Language)
		} else {
			score -= 0.40
			why("language mismatch: book %s, release %s (-0.40)", book.Language, p.Language)
		}
	}

	d, reason := sizeDelta(book, rel)
	if reason != "" {
		score += d
		why("%s (%+.2f)", reason, d)
	}

	if book.SeriesPosition != "" && p.SeriesPosition != "" && sameSeries(book.Series, p.Series, raw) {
		if posEqual(book.SeriesPosition, p.SeriesPosition) {
			score += 0.05
			why("series position %s matches (+0.05)", p.SeriesPosition)
		} else {
			score -= 0.40
			why("different book of the same series: release #%s, book #%s (-0.40)", p.SeriesPosition, book.SeriesPosition)
		}
	}

	capTo := 1.0
	if titleSim < minTitleSim {
		capTo = gateCap
		why("title gate failed (< %.2f)", minTitleSim)
	}
	if matched == 0 {
		capTo = gateCap
	}
	bookIsCollection := looksLikeCollection(book.Title + " " + book.Subtitle)
	relIsCollection := looksLikeCollection(rel.Title) ||
		(reSeriesWord.MatchString(rel.Title) && p.SeriesPosition == "")
	if relIsCollection && !bookIsCollection {
		capTo = math.Min(capTo, collectCap)
		why("release looks like a collection / box set")
	}

	res.Score = math.Max(0, math.Min(math.Min(score, capTo), 1))
	res.Match = capTo >= 1 && res.Score+1e-9 >= Threshold
	return res
}

// sizeDelta compares the release size with what the book runtime implies. 10 h of audio is about
// 290-580 MB at 64-128 kbps; anything below ~16 kbps average is a sample or the wrong file and
// anything far above 320 kbps (1500 for FLAC) is typically a multi-book pack.
func sizeDelta(book model.Book, rel model.Release) (float64, string) {
	if rel.Size <= 0 {
		return 0, ""
	}
	if rel.Size < 5*mb {
		return -0.35, "tiny release, looks like a sample"
	}
	if book.RuntimeMin <= 0 {
		return 0, ""
	}
	kbps := float64(rel.Size) * 8 / 1000 / (float64(book.RuntimeMin) * 60)
	hi := 320.0
	if strings.EqualFold(rel.Parsed.Format, "flac") {
		hi = 1500
	}
	switch {
	case kbps < 16:
		return -0.35, fmt.Sprintf("size implies %.0f kbps: too small for the runtime", kbps)
	case kbps < 40:
		return -0.10, fmt.Sprintf("size implies %.0f kbps: low", kbps)
	case kbps <= hi:
		return 0.03, fmt.Sprintf("size plausible (%.0f kbps)", kbps)
	case kbps <= 2*hi:
		return -0.10, fmt.Sprintf("size implies %.0f kbps: large", kbps)
	}
	return -0.30, fmt.Sprintf("size implies %.0f kbps: far too large for the runtime", kbps)
}
