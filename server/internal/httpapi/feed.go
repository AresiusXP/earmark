package httpapi

import (
	"encoding/xml"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"path"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/AresiusXP/earmark/server/internal/model"
	"github.com/AresiusXP/earmark/server/internal/service"
	"github.com/AresiusXP/earmark/server/internal/userconfig"
)

const (
	feedGenerator = "earmark"
	maxFeedRef    = 999
)

// feedEpoch + position minutes is the deterministic pubDate of an item: apps sort by date.
var feedEpoch = time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC)

var (
	langRe      = regexp.MustCompile(`^[a-z]{2}$`)
	trackPrefix = regexp.MustCompile(`^(?:\d{1,3}\s*[-._)\]:]+|\d{2,3}\s+)\s*`)
)

func hasLetterOrDigit(s string) bool {
	for _, r := range s {
		if unicode.IsLetter(r) || unicode.IsDigit(r) {
			return true
		}
	}
	return false
}

// ---- RSS document -------------------------------------------------------------------------

type rssDoc struct {
	XMLName     xml.Name   `xml:"rss"`
	Version     string     `xml:"version,attr"`
	XmlnsItunes string     `xml:"xmlns:itunes,attr"`
	XmlnsContnt string     `xml:"xmlns:content,attr"`
	XmlnsAtom   string     `xml:"xmlns:atom,attr"`
	Channel     rssChannel `xml:"channel"`
}

type rssChannel struct {
	Title       string      `xml:"title"`
	Link        string      `xml:"link"`
	Description string      `xml:"description"`
	Language    string      `xml:"language"`
	Generator   string      `xml:"generator"`
	AtomLink    rssAtomLink `xml:"atom:link"`
	Author      string      `xml:"itunes:author,omitempty"`
	Explicit    string      `xml:"itunes:explicit"`
	Type        string      `xml:"itunes:type"`
	Image       *rssImage   `xml:"itunes:image"`
	Items       []rssItem   `xml:"item"`
}

type rssAtomLink struct {
	Href string `xml:"href,attr"`
	Rel  string `xml:"rel,attr"`
	Type string `xml:"type,attr"`
}

type rssImage struct {
	Href string `xml:"href,attr"`
}

type rssItem struct {
	Title       string       `xml:"title"`
	Description string       `xml:"description,omitempty"`
	GUID        rssGUID      `xml:"guid"`
	PubDate     string       `xml:"pubDate"`
	Enclosure   rssEnclosure `xml:"enclosure"`
	Episode     int          `xml:"itunes:episode"`
	EpisodeType string       `xml:"itunes:episodeType"`
}

type rssGUID struct {
	IsPermaLink string `xml:"isPermaLink,attr"`
	Value       string `xml:",chardata"`
}

type rssEnclosure struct {
	URL    string `xml:"url,attr"`
	Length int64  `xml:"length,attr"`
	Type   string `xml:"type,attr"`
}

// ---- helpers shared with the listen page ---------------------------------------------------

// xmlSafe drops what XML 1.0 cannot carry (control characters, C1, non-characters, bad UTF-8).
func xmlSafe(s string) string {
	s = strings.ToValidUTF8(s, "")
	return strings.Map(func(r rune) rune {
		switch {
		case r == '\t' || r == '\n' || r == '\r':
			return r
		case r < 0x20, r >= 0x7f && r <= 0x9f, r == 0xFFFE, r == 0xFFFF:
			return -1
		}
		return r
	}, s)
}

// resolveURL is the playable URL of one file (idx may be service.AutoFile). name is purely
// cosmetic for players (the extension tells them what they got); /resolve ignores it.
func (s *server) resolveURL(tok, provider, hash string, idx int, name string) string {
	u := s.publicURL + "/resolve/" + tok + "/" + provider + "/" + hash + "/" + strconv.Itoa(idx)
	if name != "" {
		u += "/" + url.PathEscape(name)
	}
	return u
}

func (s *server) feedURL(tok, asin, ref string) string {
	u := s.publicURL + "/" + tok + "/feed/" + asin
	if ref != "" {
		u += "/" + ref
	}
	return u + ".rss"
}

// listenableReleases are the releases a podcast app can play, in season order. A pack without
// its book's files is never playable (AutoFile would pick another book).
func listenableReleases(p *service.BookPage) []service.ReleaseView {
	var out []service.ReleaseView
	for i, rv := range p.Releases {
		if rv.Season <= 0 {
			rv.Season = i + 1
		}
		if rv.Collection && len(rv.Files) == 0 {
			continue
		}
		if !isInfoHash(rv.Release.InfoHash) {
			continue
		}
		out = append(out, rv)
	}
	sort.SliceStable(out, func(i, j int) bool { return out[i].Season < out[j].Season })
	return out
}

func audioFiles(fs []model.File) []model.File {
	var out []model.File
	for _, f := range fs {
		if f.IsAudio() {
			out = append(out, f)
		}
	}
	return out
}

// feedProvider picks the provider of the enclosure URLs: the first configured one (by name)
// that the service knows.
func (s *server) feedProvider(debrid map[string]string) (string, bool) {
	known := s.api.Providers()
	names := make([]string, 0, len(debrid))
	for n := range debrid {
		names = append(names, n)
	}
	sort.Strings(names)
	for _, n := range names {
		if isProviderName(n) && slices.Contains(known, n) {
			return n, true
		}
	}
	return "", false
}

func audioType(name string) string {
	switch strings.ToLower(path.Ext(name)) {
	case ".m4b", ".m4a":
		return "audio/mp4"
	case ".aac":
		return "audio/aac"
	case ".flac":
		return "audio/flac"
	case ".ogg", ".opus":
		return "audio/ogg"
	case ".wav":
		return "audio/wav"
	case ".wma":
		return "audio/x-ms-wma"
	}
	return "audio/mpeg"
}

func baseName(p string) string {
	b := path.Base(strings.ReplaceAll(p, "\\", "/"))
	if b == "." || b == "/" {
		return ""
	}
	return b
}

// itemTitle is the file name without extension and without a leading track number ("01 - ").
func itemTitle(p string, n int) string {
	t := fileTitle(p, n)
	if c := strings.TrimSpace(trackPrefix.ReplaceAllString(t, "")); c != "" && hasLetterOrDigit(c) {
		return c
	}
	return t
}

// parseFeedRef: "" (default), a 40-hex info hash or a 1-based season number.
func parseFeedRef(ref string) (hash string, season int, ok bool) {
	if ref == "" {
		return "", 0, true
	}
	if l := strings.ToLower(ref); isInfoHash(l) {
		return l, 0, true
	}
	n, ok := parseIndex(ref)
	if !ok || n < 1 || n > maxFeedRef {
		return "", 0, false
	}
	return "", n, true
}

// bookPage fetches the book for the feed and listen handlers and answers the error cases.
func (s *server) bookPage(w http.ResponseWriter, r *http.Request, cfgASIN string, cfg userconfig.Config) (*service.BookPage, bool) {
	page, err := s.api.Book(r.Context(), cfg, cfgASIN)
	switch {
	case errors.Is(err, service.ErrNotFound), err == nil && page == nil:
		plainError(w, http.StatusNotFound, "book not found")
	case err != nil:
		if r.Context().Err() == nil {
			s.log.Warn("book page failed", "err", safeErr(err))
		}
		plainError(w, http.StatusBadGateway, "upstream unavailable")
	default:
		return page, true
	}
	return nil, false
}

func plainError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(msg + "\n"))
}

// ---- feed handler --------------------------------------------------------------------------

func (s *server) feed(w http.ResponseWriter, r *http.Request) {
	cfg, tok, ok := s.openCfg(w, r)
	if !ok {
		return
	}
	w.Header().Set("Cache-Control", "no-store")

	asinSeg, refSeg := r.PathValue("asin"), r.PathValue("ref")
	var asin, ref string
	if refSeg == "" {
		asin, ok = strings.CutSuffix(asinSeg, ".rss")
	} else {
		asin = asinSeg
		ref, ok = strings.CutSuffix(refSeg, ".rss")
		ok = ok && ref != ""
	}
	if !ok || !isASIN(asin) {
		plainError(w, http.StatusNotFound, "not found")
		return
	}
	wantHash, wantSeason, ok := parseFeedRef(ref)
	if !ok {
		plainError(w, http.StatusNotFound, "not found")
		return
	}
	provider, ok := s.feedProvider(cfg.Debrid)
	if !ok {
		writeError(w, http.StatusBadRequest, "no debrid provider configured")
		return
	}

	page, ok := s.bookPage(w, r, asin, cfg)
	if !ok {
		return
	}
	views := listenableReleases(page)
	var rv *service.ReleaseView
	for i := range views {
		switch {
		case wantHash != "" && views[i].Release.InfoHash == wantHash,
			wantSeason != 0 && views[i].Season == wantSeason,
			wantHash == "" && wantSeason == 0:
			rv = &views[i]
		}
		if rv != nil {
			break
		}
	}
	if rv == nil {
		plainError(w, http.StatusNotFound, "release not found")
		return
	}

	doc := s.buildFeed(page.Book, *rv, len(views) > 1 && ref != "", tok, provider, s.feedURL(tok, asin, ref))
	body, err := xml.MarshalIndent(doc, "", "  ")
	if err != nil {
		s.log.Error("feed marshal failed", "err", err.Error())
		plainError(w, http.StatusInternalServerError, "internal error")
		return
	}
	w.Header().Set("Content-Type", "application/rss+xml; charset=utf-8")
	_, _ = w.Write([]byte(xml.Header))
	_, _ = w.Write(body)
}

func (s *server) buildFeed(b model.Book, rv service.ReleaseView, suffix bool, tok, provider, selfURL string) rssDoc {
	hash := rv.Release.InfoHash
	files := audioFiles(rv.Files)

	title := b.Title
	if title == "" {
		title = "Audiobook " + b.ASIN
	}
	if suffix {
		var first *model.File
		if len(files) > 0 {
			first = &files[0]
		}
		if f := formatOf(rv.Release, first); f != "" {
			title += " [" + f + "]"
		}
	}
	desc := b.Description
	if len(b.Narrators) > 0 {
		if desc != "" {
			desc += "\n\n"
		}
		desc += "Narrated by " + strings.Join(b.Narrators, ", ")
	}
	if desc == "" {
		desc = title
	}
	lang := b.Language
	if !langRe.MatchString(lang) {
		lang = "en"
	}

	ch := rssChannel{
		Title: xmlSafe(title), Link: s.publicURL + "/" + tok + "/listen/" + b.ASIN, Description: xmlSafe(desc),
		Language: lang, Generator: feedGenerator,
		AtomLink: rssAtomLink{Href: selfURL, Rel: "self", Type: "application/rss+xml"},
		Author:   xmlSafe(strings.Join(b.Authors, ", ")), Explicit: "false", Type: "serial",
	}
	if strings.HasPrefix(b.CoverURL, "https://") {
		ch.Image = &rssImage{Href: b.CoverURL}
	}

	item := func(n, idx int, title, name string, size int64) rssItem {
		return rssItem{
			Title:       xmlSafe(title),
			GUID:        rssGUID{IsPermaLink: "false", Value: fmt.Sprintf("abk:%s:%s:%d", b.ASIN, hash, idx)},
			PubDate:     feedEpoch.Add(time.Duration(n) * time.Minute).Format(time.RFC1123Z),
			Enclosure:   rssEnclosure{URL: s.resolveURL(tok, provider, hash, idx, xmlSafe(name)), Length: max(size, 0), Type: audioType(name)},
			Episode:     n + 1,
			EpisodeType: "full",
		}
	}
	if len(files) == 0 {
		name := "release"
		if f := strings.ToLower(rv.Release.Parsed.Format); f != "" && model.IsAudioPath("x."+f) {
			name += "." + f
		}
		ch.Items = []rssItem{item(0, service.AutoFile, "Play full release", name, 0)}
	}
	for i, f := range files {
		name := baseName(f.Path)
		if name == "" {
			name = fmt.Sprintf("chapter-%d.mp3", i+1)
		}
		ch.Items = append(ch.Items, item(i, f.Index, itemTitle(f.Path, i+1), name, f.Size))
	}
	return rssDoc{
		Version: "2.0", XmlnsItunes: "http://www.itunes.com/dtds/podcast-1.0.dtd",
		XmlnsContnt: "http://purl.org/rss/1.0/modules/content/", XmlnsAtom: "http://www.w3.org/2005/Atom",
		Channel: ch,
	}
}
