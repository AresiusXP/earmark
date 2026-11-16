package httpapi

import (
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

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
)

const (
	manifestID   = "xyz.aresius.audiobooks"
	pageSize     = 50
	maxPage      = 20
	maxQueryLen  = 200
	catalogAge   = 120
	manifestAge  = 300
	metaAge      = 300
	catalogStale = 3600
)

var semverRe = regexp.MustCompile(`^\d+\.\d+\.\d+`)

// ---- manifest -----------------------------------------------------------------------------

type extraDef struct {
	Name       string `json:"name"`
	IsRequired bool   `json:"isRequired,omitempty"`
}

type catalogDef struct {
	Type  string     `json:"type"`
	ID    string     `json:"id"`
	Name  string     `json:"name"`
	Extra []extraDef `json:"extra"`
}

type manifestDoc struct {
	ID            string         `json:"id"`
	Version       string         `json:"version"`
	Name          string         `json:"name"`
	Description   string         `json:"description"`
	Logo          string         `json:"logo"`
	Background    string         `json:"background"`
	Types         []string       `json:"types"`
	Catalogs      []catalogDef   `json:"catalogs"`
	Resources     []any          `json:"resources"`
	IDPrefixes    []string       `json:"idPrefixes"`
	BehaviorHints map[string]any `json:"behaviorHints"`
}

func (s *server) manifest(w http.ResponseWriter, r *http.Request) {
	version := s.version
	if !semverRe.MatchString(version) {
		version = "0.0.0"
	}
	m := manifestDoc{
		ID: manifestID, Version: version, Name: "Audiobooks",
		Description: "Audiobooks with Audible metadata, played from your debrid service.",
		Logo:        s.publicURL + "/static/logo.png", Background: s.publicURL + "/static/background.jpg",
		Types:      []string{"audiobook"},
		Catalogs:   []catalogDef{},
		IDPrefixes: []string{idPrefix},
		Resources: []any{
			"catalog",
			map[string]any{"name": "meta", "types": []string{"audiobook"}, "idPrefixes": []string{idPrefix}},
			map[string]any{"name": "stream", "types": []string{"audiobook"}, "idPrefixes": []string{idPrefix}},
		},
		BehaviorHints: map[string]any{"configurable": true},
	}
	if r.PathValue("cfg") == "" {
		m.BehaviorHints["configurationRequired"] = true
	} else {
		cfg, _, ok := s.openCfg(w, r)
		if !ok {
			return
		}
		var shorts []string
		for p := range cfg.Debrid {
			shorts = append(shorts, providerShort(p))
		}
		sort.Strings(shorts)
		if len(shorts) > 0 {
			m.Name += " (" + strings.Join(shorts, ", ") + ")"
		}
		m.Catalogs = []catalogDef{
			{Type: "audiobook", ID: "abk-search", Name: "Audiobooks", Extra: []extraDef{{Name: "search", IsRequired: true}}},
			{Type: "audiobook", ID: "abk-top", Name: "Best sellers", Extra: []extraDef{{Name: "skip"}}},
		}
	}
	setCache(w, manifestAge)
	writeJSON(w, http.StatusOK, m)
}

// ---- catalog ------------------------------------------------------------------------------

type metaPreview struct {
	ID          string   `json:"id"`
	Type        string   `json:"type"`
	Name        string   `json:"name"`
	Poster      string   `json:"poster,omitempty"`
	PosterShape string   `json:"posterShape"`
	ReleaseInfo string   `json:"releaseInfo,omitempty"`
	Description string   `json:"description,omitempty"`
	Genres      []string `json:"genres"`
}

type catalogResponse struct {
	Metas           []metaPreview `json:"metas"`
	CacheMaxAge     int           `json:"cacheMaxAge,omitempty"`
	StaleRevalidate int           `json:"staleRevalidate,omitempty"`
}

func emptyCatalog() catalogResponse { return catalogResponse{Metas: []metaPreview{}} }

func (s *server) catalog(w http.ResponseWriter, r *http.Request) {
	cfg, _, ok := s.openCfg(w, r)
	if !ok {
		return
	}
	// "/catalog/audiobook/abk-top.json" or "/catalog/audiobook/abk-top/skip=50.json"
	id, extra := r.PathValue("id"), url.Values{}
	if raw := r.PathValue("extra"); raw != "" {
		_, ok = strings.CutSuffix(raw, ".json")
		extra = parseExtra(r)
	} else {
		id, ok = strings.CutSuffix(id, ".json")
	}
	if !ok || (id != "abk-search" && id != "abk-top") {
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, http.StatusNotFound, emptyCatalog())
		return
	}

	var books []model.Book
	var err error
	skip := 0
	if v, e := strconv.Atoi(extra.Get("skip")); e == nil && v > 0 {
		skip = v
	}
	switch id {
	case "abk-search":
		q := strings.TrimSpace(extra.Get("search"))
		if len(q) > maxQueryLen {
			q = q[:maxQueryLen]
		}
		if q != "" && skip == 0 {
			books, err = s.api.Search(r.Context(), cfg, q)
		}
	case "abk-top":
		page := min(skip/pageSize, maxPage)
		books, err = s.api.Discover(r.Context(), cfg, page)
	}
	if err != nil {
		if r.Context().Err() == nil {
			s.log.Warn("catalog failed", "catalog", id, "err", safeErr(err))
		}
		w.Header().Set("Cache-Control", "no-store")
		if isOutage(err) && r.Context().Err() == nil {
			writeError(w, http.StatusBadGateway, "upstream unavailable")
			return
		}
		writeJSON(w, http.StatusOK, emptyCatalog())
		return
	}

	resp := emptyCatalog()
	for _, b := range books {
		if b.ASIN == "" || !isASIN(b.ASIN) {
			continue
		}
		resp.Metas = append(resp.Metas, previewOf(b))
	}
	resp.CacheMaxAge, resp.StaleRevalidate = catalogAge, catalogStale
	setCache(w, catalogAge)
	writeJSON(w, http.StatusOK, resp)
}

// parseExtra reads "search=dune&skip=50.json" from the last (still escaped) path segment so
// encoded "&", "=" and "+" in values survive.
func parseExtra(r *http.Request) url.Values {
	p := r.URL.EscapedPath()
	seg := strings.TrimSuffix(p[strings.LastIndex(p, "/")+1:], ".json")
	v, _ := url.ParseQuery(seg)
	if v == nil {
		v = url.Values{}
	}
	return v
}

func previewOf(b model.Book) metaPreview {
	p := metaPreview{
		ID: idPrefix + b.ASIN, Type: "audiobook", Name: b.Title, Poster: b.CoverURL,
		PosterShape: "square", Genres: nz(slices.Clone(b.Genres)),
	}
	if b.ReleaseYear > 0 {
		p.ReleaseInfo = strconv.Itoa(b.ReleaseYear)
	}
	var parts []string
	if len(b.Authors) > 0 {
		parts = append(parts, strings.Join(b.Authors, ", "))
	}
	if len(b.Narrators) > 0 {
		parts = append(parts, "read by "+strings.Join(b.Narrators, ", "))
	}
	p.Description = strings.Join(parts, " · ")
	return p
}

// ---- meta ---------------------------------------------------------------------------------

type metaLink struct {
	Name     string `json:"name"`
	Category string `json:"category"`
	URL      string `json:"url"`
}

type video struct {
	ID        string `json:"id"`
	Title     string `json:"title"`
	Season    int    `json:"season"`
	Episode   int    `json:"episode"`
	Released  string `json:"released"`
	Thumbnail string `json:"thumbnail,omitempty"`
	Overview  string `json:"overview,omitempty"`
}

type metaDoc struct {
	ID          string     `json:"id"`
	Type        string     `json:"type"`
	Name        string     `json:"name"`
	Poster      string     `json:"poster,omitempty"`
	PosterShape string     `json:"posterShape"`
	Background  string     `json:"background,omitempty"`
	Description string     `json:"description,omitempty"`
	Genres      []string   `json:"genres"`
	ReleaseInfo string     `json:"releaseInfo,omitempty"`
	Runtime     string     `json:"runtime,omitempty"`
	Links       []metaLink `json:"links"`
	Videos      []video    `json:"videos"`
}

type metaResponse struct {
	Meta        *metaDoc `json:"meta"`
	CacheMaxAge int      `json:"cacheMaxAge,omitempty"`
}

func (s *server) meta(w http.ResponseWriter, r *http.Request) {
	cfg, tok, ok := s.openCfg(w, r)
	if !ok {
		return
	}
	notFound := func() {
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, http.StatusNotFound, metaResponse{})
	}
	raw, ok := strings.CutSuffix(r.PathValue("id"), ".json")
	if !ok {
		notFound()
		return
	}
	asin, ok := parseBookID(raw)
	if !ok {
		notFound()
		return
	}
	page, err := s.api.Book(r.Context(), cfg, asin)
	switch {
	case errors.Is(err, service.ErrNotFound):
		notFound()
		return
	case err != nil:
		if r.Context().Err() == nil {
			s.log.Warn("meta failed", "err", safeErr(err))
		}
		w.Header().Set("Cache-Control", "no-store")
		if isOutage(err) && r.Context().Err() == nil {
			writeError(w, http.StatusBadGateway, "upstream unavailable")
			return
		}
		writeJSON(w, http.StatusOK, metaResponse{})
		return
	case page == nil:
		notFound()
		return
	}
	// The response carries a link with the sealed config: never let a shared cache keep it.
	w.Header().Set("Cache-Control", "private, max-age="+strconv.Itoa(metaAge))
	m := buildMeta(page)
	if len(m.Videos) > 0 { // nothing to listen to without a playable release
		m.Links = append(m.Links, metaLink{
			Name: "Listen in a podcast app", Category: "Podcast", URL: s.publicURL + "/" + tok + "/listen/" + asin,
		})
	}
	writeJSON(w, http.StatusOK, metaResponse{Meta: m, CacheMaxAge: metaAge})
}

func stremioSearch(q string) string {
	return "stremio:///search?search=" + strings.ReplaceAll(url.QueryEscape(q), "+", "%20")
}

func buildMeta(p *service.BookPage) *metaDoc {
	b := p.Book
	m := &metaDoc{
		ID: idPrefix + b.ASIN, Type: "audiobook", Name: b.Title, Poster: b.CoverURL, PosterShape: "square",
		Background: b.CoverURL, Description: b.Description, Genres: nz(slices.Clone(b.Genres)),
		Links: []metaLink{},
	}
	if b.ReleaseYear > 0 {
		m.ReleaseInfo = strconv.Itoa(b.ReleaseYear)
	}
	if b.RuntimeMin > 0 {
		m.Runtime = fmt.Sprintf("%dh %02dm", b.RuntimeMin/60, b.RuntimeMin%60)
	}
	for _, a := range b.Authors {
		m.Links = append(m.Links, metaLink{Name: a, Category: "author", URL: stremioSearch(a)})
	}
	for _, n := range b.Narrators {
		m.Links = append(m.Links, metaLink{Name: n, Category: "narrator", URL: stremioSearch(n)})
	}
	if b.Series != "" {
		m.Links = append(m.Links, metaLink{Name: b.Series, Category: "series", URL: stremioSearch(b.Series)})
	}
	m.Videos = buildVideos(b, p.Releases)
	return m
}

var releasedBase = time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC)

// releasedAt is deterministic: Stremio orders episodes by date.
func releasedAt(season, episode int) string {
	return releasedBase.AddDate(0, 0, season*400+episode).Format("2006-01-02T15:04:05.000Z")
}

func buildVideos(b model.Book, views []service.ReleaseView) []video {
	vs := slices.Clone(views)
	for i := range vs {
		if vs[i].Season <= 0 {
			vs[i].Season = i + 1
		}
	}
	sort.SliceStable(vs, func(i, j int) bool { return vs[i].Season < vs[j].Season })

	out := []video{}
	for _, rv := range vs {
		if rv.Collection && len(rv.Files) == 0 {
			continue // AutoFile on a pack would pick another book; the service never sends this
		}
		hash := rv.Release.InfoHash
		overview := releaseOverview(rv)
		vid := func(idx, episode int, title string) video {
			return video{
				ID:     fmt.Sprintf("%s%s:%s:%d", idPrefix, b.ASIN, hash, idx),
				Season: rv.Season, Episode: episode, Title: title,
				Released: releasedAt(rv.Season, episode), Thumbnail: b.CoverURL, Overview: overview,
			}
		}
		switch len(rv.Files) {
		case 0:
			out = append(out, vid(service.AutoFile, 1, "Play full release"))
		case 1:
			title := "Full book"
			if f := formatOf(rv.Release, &rv.Files[0]); f != "" {
				title += " (" + f + ")"
			}
			out = append(out, vid(rv.Files[0].Index, 1, title))
		default:
			for i, f := range rv.Files {
				out = append(out, vid(f.Index, i+1, fileTitle(f.Path, i+1)))
			}
		}
	}
	return out
}

func fileTitle(p string, n int) string {
	base := path.Base(strings.ReplaceAll(p, "\\", "/"))
	base = strings.TrimSuffix(base, path.Ext(base))
	base = strings.Join(strings.Fields(strings.ReplaceAll(base, "_", " ")), " ")
	if base == "" || base == "." || base == "/" {
		return fmt.Sprintf("Chapter %d", n)
	}
	if r := []rune(base); len(r) > 100 {
		base = string(r[:100]) + "…"
	}
	return base
}

// formatOf returns the upper-case audio format, preferring the file extension.
func formatOf(rel model.Release, f *model.File) string {
	if f != nil {
		if ext := strings.TrimPrefix(strings.ToLower(path.Ext(f.Path)), "."); ext != "" && model.IsAudioPath(f.Path) {
			return strings.ToUpper(ext)
		}
	}
	return strings.ToUpper(rel.Parsed.Format)
}

func humanSize(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit && exp < 4; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTP"[exp])
}

// releaseLabel: "M4B 64k · 1.2 GB · 12 seeders".
func releaseLabel(rel model.Release, f *model.File) string {
	var parts []string
	fm := formatOf(rel, f)
	if rel.Parsed.BitrateKbps > 0 {
		fm = strings.TrimSpace(fm + " " + strconv.Itoa(rel.Parsed.BitrateKbps) + "k")
	}
	if fm != "" {
		parts = append(parts, fm)
	}
	if rel.Size > 0 {
		parts = append(parts, humanSize(rel.Size))
	}
	if rel.Seeders > 0 {
		parts = append(parts, strconv.Itoa(rel.Seeders)+" seeders")
	}
	return strings.Join(parts, " · ")
}

func releaseOverview(rv service.ReleaseView) string {
	parts := []string{"S" + strconv.Itoa(rv.Season)}
	rel := rv.Release
	if rv.Collection {
		// The torrent size is the whole pack; show what this book weighs.
		var n int64
		for _, f := range rv.Files {
			n += f.Size
		}
		rel.Size = n
	}
	if l := releaseLabel(rel, nil); l != "" {
		parts = append(parts, l)
	}
	if rv.Collection {
		parts = append(parts, "from a series pack")
	}
	var cached []string
	for p, ok := range rv.Cached {
		if ok {
			cached = append(cached, providerShort(p))
		}
	}
	sort.Strings(cached)
	switch {
	case len(cached) > 0:
		parts = append(parts, "⚡ "+strings.Join(cached, " "))
	case len(rv.Cached) > 0:
		parts = append(parts, "not cached")
	}
	o := strings.Join(parts, " · ")
	if rv.Release.Title != "" {
		o += "\n" + rv.Release.Title
	}
	return o
}

// ---- stream -------------------------------------------------------------------------------

type streamHints struct {
	BingeGroup string `json:"bingeGroup"`
	Filename   string `json:"filename,omitempty"`
	VideoSize  int64  `json:"videoSize,omitempty"`
}

type streamDoc struct {
	Name  string `json:"name"`
	Title string `json:"title"`
	// Exactly one of URL (playable) and ExternalURL (opened in the browser) is set.
	URL           string       `json:"url,omitempty"`
	ExternalURL   string       `json:"externalUrl,omitempty"`
	BehaviorHints *streamHints `json:"behaviorHints,omitempty"`
}

type streamResponse struct {
	Streams []streamDoc `json:"streams"`
}

func (s *server) stream(w http.ResponseWriter, r *http.Request) {
	cfg, tok, ok := s.openCfg(w, r)
	if !ok {
		return
	}
	w.Header().Set("Cache-Control", "no-store")
	raw, ok := strings.CutSuffix(r.PathValue("id"), ".json")
	var ref service.VideoRef
	if ok {
		ref, ok = parseVideoID(raw)
	}
	if !ok {
		writeJSON(w, http.StatusOK, streamResponse{Streams: []streamDoc{}})
		return
	}
	sts, err := s.api.Streams(r.Context(), cfg, ref)
	if err != nil {
		if errors.Is(err, service.ErrNotFound) || errors.Is(err, service.ErrNoProvider) {
			writeJSON(w, http.StatusOK, streamResponse{Streams: []streamDoc{}})
			return
		}
		if r.Context().Err() == nil {
			s.log.Warn("streams failed", "err", safeErr(err))
		}
		if isOutage(err) && r.Context().Err() == nil {
			writeError(w, http.StatusBadGateway, "upstream unavailable")
			return
		}
		writeJSON(w, http.StatusOK, streamResponse{Streams: []streamDoc{}})
		return
	}
	resp := streamResponse{Streams: []streamDoc{}}
	for _, st := range sts {
		if !isProviderName(st.Provider) || st.FileIndex < service.AutoFile {
			continue
		}
		hash := ref.InfoHash
		if isInfoHash(st.Release.InfoHash) {
			hash = st.Release.InfoHash
		}
		resp.Streams = append(resp.Streams, s.streamDoc(tok, hash, st))
	}
	if len(resp.Streams) > 0 && isASIN(ref.ASIN) {
		// Stremio only shows meta links of its own categories, so the podcast option also lives
		// here, where every client lists it: it opens the landing page in the browser.
		resp.Streams = append(resp.Streams, streamDoc{
			Name:        "ABK 🎧",
			Title:       "Listen in a podcast app\n(background playback, screen off)",
			ExternalURL: s.publicURL + "/" + tok + "/listen/" + ref.ASIN,
		})
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *server) streamDoc(tok, hash string, st service.Stream) streamDoc {
	name := "ABK"
	if st.Cached {
		name += " ⚡"
	}
	name += " " + providerShort(st.Provider)

	var filename string
	var size int64
	if st.File != nil {
		filename = path.Base(strings.ReplaceAll(st.File.Path, "\\", "/"))
		size = st.File.Size
	}
	if filename == "" || filename == "." || filename == "/" {
		filename = st.Release.Title
	}
	if size <= 0 {
		size = st.Release.Size
	}
	rel := st.Release
	if size > 0 {
		rel.Size = size
	}
	title := releaseLabel(rel, st.File)
	if !st.Cached {
		if title != "" {
			title += " · "
		}
		title += "not cached"
	}
	if filename != "" {
		title += "\n" + filename
	}
	return streamDoc{
		Name: name, Title: title,
		URL: s.resolveURL(tok, st.Provider, hash, st.FileIndex, ""),
		BehaviorHints: &streamHints{
			BingeGroup: "abk-" + hash, Filename: filename, VideoSize: size,
		},
	}
}
