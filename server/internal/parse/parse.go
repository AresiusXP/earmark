// Package parse extracts structured metadata (author, title, narrator, format, ...)
// from audiobook torrent names. It is pure: no I/O, no global state.
//
// Pipeline (see Title): normalise -> narrator -> series groups -> tags -> bracket
// leftovers -> segments -> author/title assignment. The author/title order of a
// "A - B" name is ambiguous; the heuristic is documented on assignAuthorTitle.
package parse

import (
	"html"
	"regexp"
	"strconv"
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/AresiusXP/earmark/server/internal/model"
)

var (
	reTrunc      = regexp.MustCompile(`\s*(?:\.{3}|…)\s*$`)
	reExt        = regexp.MustCompile(`(?i)\.(mp3|m4b|m4a|flac|ogg|aac|opus|wma|wav|rar|zip|7z|torrent)$`)
	reInitialFix = regexp.MustCompile(`(\p{Lu})\.(\p{Lu}\p{Ll})`)
	reSpacedSep  = regexp.MustCompile(`\s[-–—|~]\s`)
	reHalfSepL   = regexp.MustCompile(`(\p{L})-\s+(\S)`)
	reHalfSepR   = regexp.MustCompile(`(\S)\s+-(\p{L})`)
	reDottedSep  = regexp.MustCompile(`\.[-–—]\.`)
	reSpace      = regexp.MustCompile(`\s+`)
	reUnclosed   = regexp.MustCompile(`\s*[(\[{][^()\[\]{}]*$`)

	reNarrator = regexp.MustCompile(`(?i)(?:\b(?:written\s*(?:&|and)\s*)?(?:narrated|read|performed)\s+by|\bread\s+aloud\s+by|\b(?:voor)?gelezen\s+door|\bgesproken\s+door|\bgelesen\s+von|\bgesprochen\s+von|\bnarrator)\s*:?\s*([^()\[\]{}|]*?)\s*(?:$|[()\[\]{}|]|\s[-–—]\s|\s\+\s)`)

	reGroup       = regexp.MustCompile(`[(\[{]([^()\[\]{}]*)[)\]}]`)
	reGroupSeries = regexp.MustCompile(`(?i)^(?:(.+?)\s*[,:]?\s*)?(?:#|(?:books?|bk|vol(?:ume)?|parts?|deel|boek)\.?[\s\-]*#?)\s*(\d+(?:\.\d+)?)$`)
	reGroupPadded = regexp.MustCompile(`^0\d$`)
	reLangCode    = regexp.MustCompile(`[(\[]\s*(NL|EN|DE|FR|ES|SV|NO|PL|FI|IT)\s*[)\]]`)

	reUnabridged = regexp.MustCompile(`(?i)\b(?:complete\s+(?:and|&)\s+)?unabridged\b|\bungek(?:ü|u|ue)rzt|\bonverkort(?:e)?\b`)
	reAbridged   = regexp.MustCompile(`(?i)\babridged\b|\bgek(?:ü|u|ue)rzt\b|\bingekort(?:e)?\b|\bverkort(?:e)?\b`)

	reBitrates = []*regexp.Regexp{
		regexp.MustCompile(`(?i)(\d{2,3})\s*(?:kbps|kbit(?:/s)?|kb/s|kbs)\b`),
		regexp.MustCompile(`@\s*(\d{2,3})\b`),
		regexp.MustCompile(`(?i)\b(\d{2,3})\s?k\b`),
	}
	reFormat = regexp.MustCompile(`(?i)\b(m4b|mp3|m4a|flac|ogg|aac|opus|wma|wav)\b`)

	reNoise = regexp.MustCompile(`(?i)\b(?:full\s+)?audio\s*books?\b|\baudiobooks?\b|\baudiolibros?\b|\baudioboek(?:en)?\b|\bluisterboek(?:en)?\b|\bh(?:ö|oe)rb(?:uch|ücher|uecher)\b|\bchapteri[sz]ed\b|\baudible(?:\s+(?:audio|audiobook|version|edition))?\b|\bgraphic\s*audio\b|\bnlt-release\b|\b2lions-team\b|\buploaded\s+by\s+\S+|\babee\b|\bc[bv]r\b|\d+\s*cd'?s?\b|\bcd\s?\d+(?:-\d+)?\b|\d+(?:\.\d+)?\s*khz\b`)

	reImplyDE = regexp.MustCompile(`(?i)h(?:ö|oe)rb(?:uch|ücher|uecher)|ungek(?:ü|u|ue)rzt|gelesen\s+von`)
	reImplyNL = regexp.MustCompile(`(?i)luisterboek|audioboek|voorgelezen|gelezen\s+door|gesproken\s+door|onverkort|ingekort`)

	reYearTok      = regexp.MustCompile(`\b(19\d\d|20[0-3]\d)\b`)
	reYearOnly     = regexp.MustCompile(`^(19\d\d|20[0-3]\d)$`)
	reYearTrailing = regexp.MustCompile(`^(.*\S)\s+(19\d\d|20[0-3]\d)$`)

	reSegPos     = regexp.MustCompile(`(?i)^(?:(.+?)(\s*[,:]\s*|\s+))?(?:#|(?:books?|bk|vol(?:ume)?|parts?|deel|boek)\.?[\s\-]*#?)\s*(\d+(?:\.\d+)?)$`)
	reSegSeries  = regexp.MustCompile(`^(.+?)\s+(\d{1,2}(?:\.\d)?)$`)
	reSegLeading = regexp.MustCompile(`^(0\d)\s+(.+)$`)
	reBy         = regexp.MustCompile(`(?i)^(.+?)\s+(?:written\s+)?by\s+(.+)$`)
	reColonSep   = regexp.MustCompile(`\s+:\s*(\S)`)
	reSegSplit   = regexp.MustCompile(`\s*\|\s*|\s+[-–—~]+\s+|\s+[-–—]+$|^[-–—]+\s+`)
	reTrailConj  = regexp.MustCompile(`(?i)\s+(?:and|&|en|und|by|door|von|with)$`)
	reLeadConj   = regexp.MustCompile(`(?i)^(?:by|door|von)\s+`)
	reInitialEnd = regexp.MustCompile(`(?:^|[\s.])\p{Lu}\.$`)
	reAuthors    = regexp.MustCompile(`(?i)\s*(?:,|;|&|\+)\s*|\s+(?:and|en|und|with)\s+`)
)

var langWords = map[string]string{
	"english": "en", "engels": "en", "eng": "en",
	"dutch": "nl", "nederlands": "nl", "nederlandstalig": "nl", "nld": "nl", "nl": "nl", "flemish": "nl",
	"german": "de", "deutsch": "de",
	"french": "fr", "francais": "fr", "français": "fr",
	"spanish": "es", "español": "es", "espanol": "es",
	"swedish": "sv", "svensk": "sv", "svenska": "sv",
	"norwegian": "no", "norsk": "no",
	"polish": "pl", "polski": "pl",
	"finnish": "fi", "suomi": "fi",
	"italian": "it", "italiano": "it",
	"russian": "ru",
}

// Words that are also common in titles ("The Dutch House") only count as a language tag
// in a bracket, as a whole segment or at the end of the name.
var ambiguousLang = map[string]bool{
	"english": true, "dutch": true, "german": true, "french": true, "spanish": true,
	"swedish": true, "norwegian": true, "polish": true, "finnish": true, "italian": true, "russian": true,
}

var reLang = func() *regexp.Regexp {
	words := make([]string, 0, len(langWords))
	for w := range langWords {
		words = append(words, regexp.QuoteMeta(w))
	}
	return regexp.MustCompile(`(?i)\b(?:` + strings.Join(words, "|") + `)\b`)
}()

var particles = map[string]bool{
	"van": true, "von": true, "de": true, "der": true, "den": true, "ter": true, "ten": true,
	"di": true, "da": true, "du": true, "la": true, "le": true, "del": true, "dos": true,
	"bin": true, "al": true, "el": true,
}

var articles = map[string]bool{
	"the": true, "a": true, "an": true, "de": true, "het": true, "een": true,
	"der": true, "die": true, "das": true, "den": true, "le": true, "la": true, "les": true,
	"el": true, "los": true, "las": true, "il": true,
}

var nameNoise = map[string]bool{
	"series": true, "book": true, "books": true, "collection": true, "complete": true,
	"saga": true, "trilogy": true, "novel": true, "thriller": true, "edition": true,
	"audio": true, "audiobook": true, "version": true, "unabridged": true, "abridged": true,
	"vol": true, "volume": true, "part": true, "mystery": true, "fantasy": true,
	"fiction": true, "stories": true, "tales": true, "graphic": true, "radio": true,
	"drama": true, "bible": true, "box": true, "set": true, "team": true, "release": true,
}

// Title parses a raw torrent name. Unknown fields are left at their zero value.
func Title(raw string) model.ParsedTitle {
	var p model.ParsedTitle
	s := normalise(raw, &p)

	impliedLang := ""
	if reImplyDE.MatchString(s) {
		impliedLang = "de"
	} else if reImplyNL.MatchString(s) {
		impliedLang = "nl"
	}

	s = extractNarrator(s, &p)
	s = extractSeriesGroups(s, &p)
	s = reUnclosed.ReplaceAllString(s, " ")
	s = stripTags(s, &p)
	if p.Language == "" {
		p.Language = impliedLang
	}
	s, parenPerson := extractLeftoverGroups(s, &p)

	segs := splitSegments(s)
	segs = extractYears(segs, &p)
	segs = extractSeries(segs, &p)

	authors, title, extraNarrator := assignAuthorTitle(segs)
	if p.Narrator == "" {
		p.Narrator = extraNarrator
	}
	if len(authors) == 0 && parenPerson != "" {
		authors = splitNames(parenPerson)
		parenPerson = ""
	}
	if p.Narrator == "" && parenPerson != "" {
		p.Narrator = parenPerson
	}
	if title == "" && len(authors) == 0 {
		title = cleanSeg(s)
	}
	p.Authors = authors
	p.Title = title
	return p
}

func normalise(raw string, p *model.ParsedTitle) string {
	s := html.UnescapeString(raw)
	s = strings.NewReplacer(`\'`, "'", `\"`, `"`, "’", "'", "\u00a0", " ").Replace(s)
	s = strings.TrimSpace(s)
	s = reTrunc.ReplaceAllString(s, "")
	if m := reExt.FindStringSubmatch(s); m != nil {
		s = s[:len(s)-len(m[0])]
		if ext := strings.ToLower(m[1]); isAudioFormat(ext) {
			p.Format = ext
		}
	}
	s = strings.ReplaceAll(s, "_", " ")
	s = reDottedSep.ReplaceAllString(s, " - ")
	s = replaceDots(s)
	s = reInitialFix.ReplaceAllString(s, "$1. $2")
	s = splitGluedHyphens(s)
	return strings.TrimSpace(s)
}

func isAudioFormat(ext string) bool {
	switch ext {
	case "mp3", "m4b", "m4a", "flac", "ogg", "aac", "opus", "wma", "wav":
		return true
	}
	return false
}

func isAlnum(r rune) bool { return unicode.IsLetter(r) || unicode.IsDigit(r) }

// replaceDots turns "Andy.Weir.-.Project" into "Andy Weir - Project" while keeping
// initials ("J.R.R."), decimals ("2.5") and sentence dots followed by a space.
func replaceDots(s string) string {
	rs := []rune(s)
	var b strings.Builder
	for i, r := range rs {
		if r != '.' {
			b.WriteRune(r)
			continue
		}
		var prev, next rune
		if i > 0 {
			prev = rs[i-1]
		}
		if i+1 < len(rs) {
			next = rs[i+1]
		}
		loneInitial := i > 0 && unicode.IsLetter(prev) && (i == 1 || !unicode.IsLetter(rs[i-2]))
		switch {
		case isAlnum(prev) && isAlnum(next) && !(unicode.IsDigit(prev) && unicode.IsDigit(next)) && !loneInitial:
			b.WriteByte(' ')
		case (prev == ' ' || i == 0) && next == ' ':
			b.WriteByte(' ')
		default:
			b.WriteRune(r)
		}
	}
	return b.String()
}

// splitGluedHyphens turns "Stephen King-The Regulators" into "Stephen King - The Regulators".
// A hyphen without spaces is only a separator when no spaced separator exists, the next
// letter is upper-case and the text before it looks like a person (first split) or has several
// words (later splits); this keeps "Half-Blood" and "Spider-Man" intact.
func splitGluedHyphens(s string) string {
	if reSpacedSep.MatchString(s) {
		s = reHalfSepL.ReplaceAllString(s, "$1 - $2")
		return reHalfSepR.ReplaceAllString(s, "$1 - $2")
	}
	s = reHalfSepL.ReplaceAllString(s, "$1 - $2")
	s = reHalfSepR.ReplaceAllString(s, "$1 - $2")
	if reSpacedSep.MatchString(s) {
		return s
	}
	rs := []rune(s)
	var b, cur strings.Builder
	split := false
	for i, r := range rs {
		if r == '-' && i > 0 && i+1 < len(rs) && unicode.IsLetter(rs[i-1]) && unicode.IsUpper(rs[i+1]) {
			stretch := strings.TrimSpace(cur.String())
			ok := strings.ContainsRune(stretch, ' ')
			if !split {
				ok = ok && looksLikePerson(stretch)
			}
			if ok {
				b.WriteString(" - ")
				cur.Reset()
				split = true
				continue
			}
		}
		b.WriteRune(r)
		cur.WriteRune(r)
	}
	return b.String()
}

func extractNarrator(s string, p *model.ParsedTitle) string {
	m := reNarrator.FindStringSubmatchIndex(s)
	if m == nil {
		return s
	}
	name := s[m[2]:m[3]]
	var scratch model.ParsedTitle
	name = cleanSeg(stripTags(name, &scratch))
	mergeTags(p, scratch)
	if strings.EqualFold(name, "author") {
		name = ""
	}
	if name != "" && p.Narrator == "" {
		p.Narrator = name
	}
	return s[:m[0]] + " " + s[m[3]:]
}

func mergeTags(p *model.ParsedTitle, o model.ParsedTitle) {
	if p.Format == "" {
		p.Format = o.Format
	}
	if p.BitrateKbps == 0 {
		p.BitrateKbps = o.BitrateKbps
	}
	if p.Unabridged == nil {
		p.Unabridged = o.Unabridged
	}
	if p.Language == "" {
		p.Language = o.Language
	}
}

// extractSeriesGroups consumes bracket groups such as "(Mistborn #6)" or "(Series, Book 1)".
func extractSeriesGroups(s string, p *model.ParsedTitle) string {
	return reGroup.ReplaceAllStringFunc(s, func(g string) string {
		c := strings.TrimSpace(g[1 : len(g)-1])
		if reGroupPadded.MatchString(c) {
			if p.SeriesPosition == "" {
				p.SeriesPosition = normPos(c)
			}
			return " "
		}
		m := reGroupSeries.FindStringSubmatch(c)
		if m == nil {
			return g
		}
		if p.SeriesPosition == "" {
			p.SeriesPosition = normPos(m[2])
			if p.Series == "" {
				p.Series = cleanSeg(m[1])
			}
		}
		return " "
	})
}

func normPos(n string) string {
	if i := strings.IndexByte(n, '.'); i >= 0 {
		whole := strings.TrimLeft(n[:i], "0")
		if whole == "" {
			whole = "0"
		}
		return whole + n[i:]
	}
	n = strings.TrimLeft(n, "0")
	if n == "" {
		return "0"
	}
	return n
}

func stripTags(s string, p *model.ParsedTitle) string {
	if m := reLangCode.FindStringSubmatch(s); m != nil {
		if p.Language == "" {
			p.Language = strings.ToLower(m[1])
		}
		s = reLangCode.ReplaceAllString(s, " ")
	}
	if p.Unabridged == nil {
		if reUnabridged.MatchString(s) {
			t := true
			p.Unabridged = &t
		} else if reAbridged.MatchString(s) {
			f := false
			p.Unabridged = &f
		}
	}
	s = reUnabridged.ReplaceAllString(s, " ")
	s = reAbridged.ReplaceAllString(s, " ")

	for _, re := range reBitrates {
		if m := re.FindStringSubmatch(s); m != nil {
			if n, _ := strconv.Atoi(m[1]); p.BitrateKbps == 0 && n >= 16 && n <= 512 {
				p.BitrateKbps = n
			}
			s = re.ReplaceAllString(s, " ")
		}
	}
	if m := reFormat.FindStringSubmatch(s); m != nil {
		if p.Format == "" {
			p.Format = strings.ToLower(m[1])
		}
		s = reFormat.ReplaceAllString(s, " ")
	}
	s = reNoise.ReplaceAllString(s, " ")
	return stripLanguage(s, p)
}

func stripLanguage(s string, p *model.ParsedTitle) string {
	locs := reLang.FindAllStringIndex(s, -1)
	if locs == nil {
		return s
	}
	var b strings.Builder
	last := 0
	for _, loc := range locs {
		w := strings.ToLower(s[loc[0]:loc[1]])
		if ambiguousLang[w] && !atBoundary(s, loc[0], loc[1]) {
			continue
		}
		b.WriteString(s[last:loc[0]])
		b.WriteByte(' ')
		last = loc[1]
		if p.Language == "" {
			p.Language = langWords[w]
		}
	}
	b.WriteString(s[last:])
	return b.String()
}

func atBoundary(s string, start, end int) bool {
	left := strings.TrimRightFunc(s[:start], unicode.IsSpace)
	right := strings.TrimLeftFunc(s[end:], unicode.IsSpace)
	leftOK := left == ""
	if !leftOK {
		r, _ := utf8.DecodeLastRuneInString(left)
		leftOK = strings.ContainsRune("([{-–—|~", r)
	}
	rightOK := right == ""
	if !rightOK {
		r, _ := utf8.DecodeRuneInString(right)
		rightOK = strings.ContainsRune(")]}-–—|~", r)
	}
	return leftOK && rightOK || (rightOK && right == "")
}

// extractLeftoverGroups drops remaining bracket groups. It still harvests a year and,
// for a person-like group that follows some text, returns it as a narrator/author candidate.
func extractLeftoverGroups(s string, p *model.ParsedTitle) (string, string) {
	person := ""
	s = reGroup.ReplaceAllStringFunc(s, func(g string) string {
		c := strings.TrimSpace(g[1 : len(g)-1])
		if m := reYearTok.FindString(c); m != "" && p.Year == 0 {
			p.Year, _ = strconv.Atoi(m)
		}
		if person == "" && looksLikePerson(c) {
			person = c
		}
		return " "
	})
	s = strings.NewReplacer("(", " ", ")", " ", "[", " ", "]", " ", "{", " ", "}", " ").Replace(s)
	return s, person
}

func splitSegments(s string) []string {
	s = reColonSep.ReplaceAllString(s, " | $1")
	var out []string
	for _, seg := range reSegSplit.Split(s, -1) {
		if seg = cleanSeg(seg); seg != "" {
			out = append(out, seg)
		}
	}
	return out
}

func cleanSeg(s string) string {
	s = reSpace.ReplaceAllString(strings.TrimSpace(s), " ")
	for {
		prev := s
		s = strings.Trim(s, ` -–—_,:;|+~"`)
		if strings.HasSuffix(s, ".") && !reInitialEnd.MatchString(s) {
			s = strings.TrimRight(s, ".")
		}
		s = strings.TrimLeft(s, ".")
		s = reTrailConj.ReplaceAllString(s, "")
		s = reLeadConj.ReplaceAllString(s, "")
		if s == prev {
			return s
		}
	}
}

// extractYears pulls a year out of a pure-year segment (only when at least two other
// segments remain, so "George Orwell - 1984" keeps its title) and from a trailing year.
func extractYears(segs []string, p *model.ParsedTitle) []string {
	var out []string
	for _, s := range segs {
		if reYearOnly.MatchString(s) && len(segs) >= 3 {
			if p.Year == 0 {
				p.Year, _ = strconv.Atoi(s)
			}
			continue
		}
		if m := reYearTrailing.FindStringSubmatch(s); m != nil {
			if p.Year == 0 {
				p.Year, _ = strconv.Atoi(m[2])
			}
			s = cleanSeg(m[1])
		}
		if s != "" {
			out = append(out, s)
		}
	}
	return out
}

// extractSeries recognises "Series Book 3", "Series #3", "Title, Book 3", "Series 03 - Title"
// (middle segment or zero-padded number) and "01 Title".
func extractSeries(segs []string, p *model.ParsedTitle) []string {
	var out []string
	for i, s := range segs {
		if m := reSegPos.FindStringSubmatch(s); m != nil && p.SeriesPosition == "" {
			prefix, sep, pos := cleanSeg(m[1]), m[2], normPos(m[3])
			p.SeriesPosition = pos
			switch {
			case prefix == "":
				continue
			case strings.TrimSpace(sep) != "" || len(segs) == 1:
				out = append(out, prefix)
			default:
				if p.Series == "" {
					p.Series = prefix
				}
				if hasTitleSegment(segs, i) {
					continue
				}
				out = append(out, prefix)
			}
			continue
		}
		if m := reSegSeries.FindStringSubmatch(s); m != nil && p.SeriesPosition == "" {
			padded := strings.HasPrefix(m[2], "0")
			if (len(segs) >= 3 && i == 1) || (padded && len(segs) >= 2) {
				p.Series = m[1]
				p.SeriesPosition = normPos(m[2])
				continue
			}
		}
		if m := reSegLeading.FindStringSubmatch(s); m != nil && p.SeriesPosition == "" {
			p.SeriesPosition = normPos(m[1])
			s = cleanSeg(m[2])
		}
		out = append(out, s)
	}
	return out
}

// hasTitleSegment reports whether another non-person segment exists, i.e. the "Series Book N"
// segment at index skip is not the only candidate for the title.
func hasTitleSegment(segs []string, skip int) bool {
	for i, s := range segs {
		if i != skip && !looksLikePerson(s) {
			return true
		}
	}
	return false
}

// assignAuthorTitle decides which segment is the author and which the title.
//
// Heuristic: the default order is "Author - Title". A segment is "person-like" when it is
// 2-5 capitalised alphabetic tokens (lower-case particles such as "van" allowed), optionally
// a list separated by comma/&/and, without digits, article starts or words such as "Series".
// With two segments we swap to "Title - Author" only if the right side is person-like and the
// left side is not (or starts with an article: "The Martian - Andy Weir"). When both look like
// persons ("Project Hail Mary - Andy Weir") the default order wins; that is a known weak
// spot, so callers that need robustness (package match) also look at the raw name.
// An explicit "Title by Author" always wins.
func assignAuthorTitle(segs []string) (authors []string, title, narrator string) {
	for _, s := range segs {
		if m := reBy.FindStringSubmatch(s); m != nil && looksLikePerson(m[2]) {
			return splitNames(m[2]), cleanSeg(m[1]), ""
		}
	}
	switch n := len(segs); {
	case n == 0:
		return nil, "", ""
	case n == 1:
		return nil, segs[0], ""
	case n == 2:
		a, b := segs[0], segs[1]
		aP, bP := looksLikePerson(a), looksLikePerson(b)
		switch {
		case bP && (!aP || startsWithArticle(a)):
			return splitNames(b), a, ""
		case aP:
			return splitNames(a), b, ""
		default:
			return nil, a + " - " + b, ""
		}
	}
	idx := -1
	for i, s := range segs {
		if looksLikePerson(s) && !startsWithArticle(s) {
			idx = i
			break
		}
	}
	if idx < 0 {
		return nil, segs[0], ""
	}
	authors = splitNames(segs[idx])
	tIdx := idx - 1
	switch {
	case idx == 0:
		tIdx = 1
	case titleLike(segs[0]):
		tIdx = 0
	case idx+1 < len(segs):
		tIdx = idx + 1
	}
	for i, s := range segs {
		if i != idx && i != tIdx && looksLikePerson(s) && !startsWithArticle(s) {
			narrator = s
			break
		}
	}
	return authors, segs[tIdx], narrator
}

func startsWithArticle(s string) bool {
	f := strings.Fields(s)
	return len(f) > 0 && articles[strings.ToLower(f[0])]
}

// titleLike is true for a segment that has article/lower-case words, so it is unlikely to
// be a release-group tag or an author.
func titleLike(s string) bool {
	if startsWithArticle(s) {
		return true
	}
	for _, t := range strings.Fields(s) {
		r, _ := utf8.DecodeRuneInString(t)
		if unicode.IsLower(r) && len(t) >= 3 && !particles[t] {
			return true
		}
	}
	return false
}

func splitNames(s string) []string {
	var out []string
	for _, n := range reAuthors.Split(s, -1) {
		if n = cleanSeg(n); n != "" {
			out = append(out, n)
		}
	}
	return out
}

func looksLikePerson(s string) bool {
	s = strings.TrimSpace(s)
	if s == "" {
		return false
	}
	names := reAuthors.Split(s, -1)
	if len(names) > 4 {
		return false
	}
	for _, n := range names {
		if !isName(strings.TrimSpace(n)) {
			return false
		}
	}
	return true
}

func isName(n string) bool {
	toks := strings.Fields(n)
	if len(toks) < 2 || len(toks) > 5 {
		return false
	}
	caps := 0
	for i, t := range toks {
		lt := strings.ToLower(t)
		if nameNoise[lt] || (i == 0 && articles[lt]) {
			return false
		}
		if i > 0 && i < len(toks)-1 && particles[t] {
			continue
		}
		if r, _ := utf8.DecodeRuneInString(t); !unicode.IsUpper(r) {
			return false
		}
		for _, r := range t {
			if !unicode.IsLetter(r) && !strings.ContainsRune(`'-.`, r) {
				return false
			}
		}
		caps++
	}
	return caps >= 2
}
