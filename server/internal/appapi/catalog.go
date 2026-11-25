package appapi

import (
	"context"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/accounts"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/metadata"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

// Catalog responses are per user but cheap to repeat; everything else stays no-store.
const catalogCacheControl = "private, max-age=300"

const (
	minQuery       = 2
	maxQuery       = 120
	maxDiscoverPge = 100
	maxReleases    = 20 // releases per book page
)

type bookDTO struct {
	ASIN           string   `json:"asin"`
	Title          string   `json:"title"`
	Subtitle       string   `json:"subtitle,omitempty"`
	Authors        []string `json:"authors"`
	Narrators      []string `json:"narrators"`
	Series         string   `json:"series,omitempty"`
	SeriesPosition string   `json:"series_position,omitempty"`
	Language       string   `json:"language"`
	RuntimeMin     int      `json:"runtime_min"`
	CoverURL       string   `json:"cover_url,omitempty"`
	Description    string   `json:"description,omitempty"`
	ReleaseYear    int      `json:"release_year,omitempty"`
	Genres         []string `json:"genres,omitempty"`
}

func toBook(b model.Book) bookDTO {
	return bookDTO{
		ASIN: b.ASIN, Title: b.Title, Subtitle: b.Subtitle, Authors: nonNil(b.Authors), Narrators: nonNil(b.Narrators),
		Series: b.Series, SeriesPosition: b.SeriesPosition, Language: b.Language, RuntimeMin: b.RuntimeMin,
		CoverURL: b.CoverURL, Description: b.Description, ReleaseYear: b.ReleaseYear, Genres: b.Genres,
	}
}

type fileDTO struct {
	Index int    `json:"index"`
	Path  string `json:"path"`
	Size  int64  `json:"size"`
}

func toFiles(fs []model.File) []fileDTO {
	out := make([]fileDTO, len(fs))
	for i, f := range fs {
		out[i] = fileDTO{Index: f.Index, Path: f.Path, Size: f.Size}
	}
	return out
}

type usableDTO struct {
	Debrid []string `json:"debrid"`
	P2P    bool     `json:"p2p"`
}

type releaseDTO struct {
	InfoHash     string          `json:"info_hash"`
	Title        string          `json:"title"`
	Source       string          `json:"source"`
	Size         int64           `json:"size"`
	Seeders      int             `json:"seeders"`
	Format       string          `json:"format,omitempty"`
	BitrateKbps  int             `json:"bitrate_kbps,omitempty"`
	Narrator     string          `json:"narrator,omitempty"`
	Language     string          `json:"language,omitempty"`
	Score        float64         `json:"score"`
	Collection   bool            `json:"collection"`
	Magnet       string          `json:"magnet"`
	Files        []fileDTO       `json:"files"`
	TorrentFiles []fileDTO       `json:"torrent_files"`
	Cached       map[string]bool `json:"cached"`
	Usable       usableDTO       `json:"usable"`
}

type bookDetailDTO struct {
	Book       bookDTO      `json:"book"`
	SearchedAt *string      `json:"searched_at"`
	Releases   []releaseDTO `json:"releases"`
}

func (a *app) settings(r *http.Request) (accounts.User, bool, error) {
	p, ok := PrincipalFrom(r)
	if !ok {
		return accounts.User{}, false, accounts.ErrUnauthorized
	}
	u, err := a.acc.Me(r.Context(), p.UserID)
	return u, true, err
}

func (a *app) prefs(w http.ResponseWriter, r *http.Request) (service.Prefs, accounts.User, bool) {
	u, _, err := a.settings(r)
	if err != nil {
		a.fail(w, err)
		return service.Prefs{}, accounts.User{}, false
	}
	return service.Prefs{Region: u.Settings.Region, Languages: u.Settings.Languages}, u, true
}

// catalogFail maps a service/metadata error to the contract's error codes.
func (a *app) catalogFail(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, service.ErrNotFound), errors.Is(err, metadata.ErrNotFound), errors.Is(err, metadata.ErrInvalidASIN):
		WriteError(w, http.StatusNotFound, "not_found", "not found")
	case errors.Is(err, context.Canceled):
		WriteError(w, http.StatusServiceUnavailable, "upstream_failed", "request canceled")
	default:
		a.log.Warn("catalog request failed", "err", err)
		WriteError(w, http.StatusBadGateway, "upstream_failed", "the catalog could not be reached, try again")
	}
}

func writeBooks(w http.ResponseWriter, books []model.Book) {
	out := make([]bookDTO, len(books))
	for i, b := range books {
		out[i] = toBook(b)
	}
	w.Header().Set("Cache-Control", catalogCacheControl)
	WriteJSON(w, http.StatusOK, map[string]any{"books": out})
}

func (a *app) search(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if n := utf8.RuneCountInString(q); n < minQuery || n > maxQuery || !utf8.ValidString(q) {
		WriteError(w, http.StatusBadRequest, "invalid_request", "q must be 2-120 characters")
		return
	}
	p, _, ok := a.prefs(w, r)
	if !ok {
		return
	}
	books, err := a.d.Catalog.SearchFor(r.Context(), p, q)
	if err != nil {
		a.catalogFail(w, err)
		return
	}
	writeBooks(w, books)
}

func (a *app) discover(w http.ResponseWriter, r *http.Request) {
	page := 0
	if v := r.URL.Query().Get("page"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 0 {
			WriteError(w, http.StatusBadRequest, "invalid_request", "page must be a non-negative integer")
			return
		}
		page = min(n, maxDiscoverPge)
	}
	p, _, ok := a.prefs(w, r)
	if !ok {
		return
	}
	books, err := a.d.Catalog.DiscoverFor(r.Context(), p, page)
	if err != nil {
		a.catalogFail(w, err)
		return
	}
	writeBooks(w, books)
}

// workingKeys returns the caller's keys that are usable now (status ok), each with the secret only
// when the provider's cache is shared (TorBox: the caller's own key checks the shared cache).
func (a *app) workingKeys(r *http.Request) (keys []service.UserKey, providers []string, err error) {
	p, _ := PrincipalFrom(r)
	sts, err := a.key.List(r.Context(), p.UserID)
	if err != nil {
		return nil, nil, err
	}
	for _, st := range sts {
		if !st.Configured || st.State != userkeys.StateOK {
			continue
		}
		k, err := a.DebridKey(r, st.Provider)
		if err != nil {
			if errors.Is(err, userkeys.ErrBadKey) || errors.Is(err, userkeys.ErrNoKey) || errors.Is(err, userkeys.ErrUnreadable) {
				continue
			}
			return nil, nil, err
		}
		uk := service.UserKey{Provider: st.Provider, Fingerprint: k.Fingerprint}
		if service.SharedCache(st.Provider) {
			uk.APIKey = k.APIKey()
		}
		keys = append(keys, uk)
		providers = append(providers, st.Provider)
	}
	return keys, providers, nil
}

func (a *app) book(w http.ResponseWriter, r *http.Request) {
	asin := r.PathValue("asin")
	if !metadata.ValidASIN(asin) {
		WriteError(w, http.StatusNotFound, "not_found", "not found")
		return
	}
	u, _, err := a.settings(r)
	if err != nil {
		a.fail(w, err)
		return
	}
	keys, providers, err := a.workingKeys(r)
	if err != nil {
		a.fail(w, err)
		return
	}
	page, err := a.d.Catalog.BookFor(r.Context(), service.BookRequest{
		ASIN: asin, Region: u.Settings.Region, Languages: u.Settings.Languages, PreferFormat: u.Settings.PreferFormat,
		MaxReleases: maxReleases, Keys: keys,
	})
	if err != nil {
		a.catalogFail(w, err)
		return
	}
	p2p := u.Settings.P2PEnabled && u.Settings.SourceMode != "debrid"
	out := bookDetailDTO{Book: toBook(page.Book), Releases: make([]releaseDTO, len(page.Releases))}
	if at := page.SearchedAt; !at.IsZero() {
		s := at.UTC().Format("2006-01-02T15:04:05Z07:00")
		out.SearchedAt = &s
	}
	for i, v := range page.Releases {
		rel := v.Release
		cached := map[string]bool{}
		for k, c := range v.Cached {
			cached[k] = c
		}
		out.Releases[i] = releaseDTO{
			InfoHash: rel.InfoHash, Title: rel.Title, Source: rel.Source, Size: rel.Size, Seeders: rel.Seeders,
			Format: rel.Parsed.Format, BitrateKbps: rel.Parsed.BitrateKbps, Narrator: rel.Parsed.Narrator,
			Language: rel.Parsed.Language, Score: v.Score, Collection: v.Collection, Magnet: rel.Magnet,
			Files: toFiles(v.Files), TorrentFiles: toFiles(v.TorrentFiles), Cached: cached,
			Usable: usableDTO{Debrid: nonNil(providers), P2P: rel.Magnet != "" && p2p},
		}
	}
	w.Header().Set("Cache-Control", catalogCacheControl)
	WriteJSON(w, http.StatusOK, out)
}

func (a *app) chapters(w http.ResponseWriter, r *http.Request) {
	asin := r.PathValue("asin")
	if !metadata.ValidASIN(asin) {
		WriteError(w, http.StatusNotFound, "not_found", "not found")
		return
	}
	p, _, ok := a.prefs(w, r)
	if !ok {
		return
	}
	runtime, chs, err := a.d.Catalog.Chapters(r.Context(), p.Region, asin)
	if err != nil {
		a.catalogFail(w, err)
		return
	}
	type chapterDTO struct {
		Title    string `json:"title"`
		StartMs  int64  `json:"start_ms"`
		LengthMs int64  `json:"length_ms"`
	}
	out := make([]chapterDTO, len(chs))
	for i, c := range chs {
		out[i] = chapterDTO{Title: c.Title, StartMs: int64(c.StartMs), LengthMs: int64(c.LengthMs)}
	}
	w.Header().Set("Cache-Control", catalogCacheControl)
	WriteJSON(w, http.StatusOK, map[string]any{"asin": asin, "runtime_ms": runtime, "chapters": out})
}
