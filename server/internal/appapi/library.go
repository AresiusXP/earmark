package appapi

import (
	"errors"
	"math"
	"net/http"
	"regexp"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/AresiusXP/earmark/server/internal/library"
	"github.com/AresiusXP/earmark/server/internal/metadata"
)

// maxClockSkew bounds how far in the future a client's updated_at may be: last-write-wins on it, so
// a wild value would lock every other device out of the entry.
const maxClockSkew = 24 * time.Hour

type libraryEntryDTO struct {
	Book           bookDTO `json:"book"`
	Status         string  `json:"status"`
	ReleaseHash    *string `json:"release_hash"`
	PositionMs     int64   `json:"position_ms"`
	FilePath       *string `json:"file_path"`
	FilePositionMs int64   `json:"file_position_ms"`
	Speed          float64 `json:"speed"`
	UpdatedAt      int64   `json:"updated_at"`
	// FinishedAt is the server time the book became finished; null otherwise.
	FinishedAt *time.Time `json:"finished_at"`
}

func toEntry(e library.Entry) libraryEntryDTO {
	var fin *time.Time
	if e.FinishedAt != nil {
		t := e.FinishedAt.UTC()
		fin = &t
	}
	return libraryEntryDTO{FinishedAt: fin,
		Book: toBook(e.Book), Status: e.Status, ReleaseHash: e.ReleaseHash, PositionMs: e.PositionMs,
		FilePath: e.FilePath, FilePositionMs: e.FilePositionMs, Speed: e.Speed, UpdatedAt: e.UpdatedAt,
	}
}

type bookmarkDTO struct {
	ID         string    `json:"id"`
	PositionMs int64     `json:"position_ms"`
	Note       string    `json:"note"`
	CreatedAt  time.Time `json:"created_at"`
	ClientID   string    `json:"client_id,omitempty"`
}

func toBookmark(b library.Bookmark) bookmarkDTO {
	return bookmarkDTO{ID: b.ID, PositionMs: b.PositionMs, Note: b.Note, CreatedAt: b.CreatedAt.UTC(), ClientID: b.ClientID}
}

var clientIDRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func (a *app) userID(r *http.Request) string {
	p, _ := PrincipalFrom(r)
	return p.UserID
}

// libraryASIN validates the path ASIN (400 when malformed).
func libraryASIN(w http.ResponseWriter, r *http.Request) (string, bool) {
	asin := r.PathValue("asin")
	if !metadata.ValidASIN(asin) {
		WriteError(w, http.StatusBadRequest, "invalid_request", "invalid ASIN")
		return "", false
	}
	return asin, true
}

func (a *app) libraryFail(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, library.ErrNotFound):
		WriteError(w, http.StatusNotFound, "not_found", "not found")
	case errors.Is(err, library.ErrLimitReached):
		WriteError(w, http.StatusConflict, "limit_reached", "limit reached")
	case errors.Is(err, library.ErrUnknownRelease):
		WriteError(w, http.StatusBadRequest, "invalid_request", "release_hash is not a known release")
	default:
		a.log.Error("library request failed", "err", err)
		WriteError(w, http.StatusInternalServerError, "upstream_failed", "internal error")
	}
}

func (a *app) listLibrary(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	if status != "" && !library.ValidStatus(status) {
		WriteError(w, http.StatusBadRequest, "invalid_request", "status must be saved, listening or finished")
		return
	}
	es, err := a.d.Library.ListEntries(r.Context(), a.userID(r), status)
	if err != nil {
		a.libraryFail(w, err)
		return
	}
	out := make([]libraryEntryDTO, len(es))
	for i, e := range es {
		out[i] = toEntry(e)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"entries": out})
}

func (a *app) getLibrary(w http.ResponseWriter, r *http.Request) {
	asin, ok := libraryASIN(w, r)
	if !ok {
		return
	}
	e, err := a.d.Library.GetEntry(r.Context(), a.userID(r), asin)
	if err != nil {
		a.libraryFail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toEntry(e))
}

func cleanText(s string, maxBytes int) bool {
	return len(s) <= maxBytes && utf8.ValidString(s) && strings.IndexFunc(s, unicode.IsControl) < 0
}

func (a *app) putLibrary(w http.ResponseWriter, r *http.Request) {
	asin, ok := libraryASIN(w, r)
	if !ok {
		return
	}
	var in struct {
		Status         *string  `json:"status"`
		ReleaseHash    *string  `json:"release_hash"`
		PositionMs     *int64   `json:"position_ms"`
		FilePath       *string  `json:"file_path"`
		FilePositionMs *int64   `json:"file_position_ms"`
		Speed          *float64 `json:"speed"`
		UpdatedAt      *int64   `json:"updated_at"`
	}
	if !decode(w, r, &in) {
		return
	}
	bad := func(msg string) { WriteError(w, http.StatusBadRequest, "invalid_request", msg) }
	switch {
	case in.Status == nil || !library.ValidStatus(*in.Status):
		bad("status must be saved, listening or finished")
		return
	case in.UpdatedAt == nil || *in.UpdatedAt < 0 || *in.UpdatedAt > a.now().Add(maxClockSkew).UnixMilli():
		bad("updated_at must be the client clock in ms since epoch (not in the future)")
		return
	}
	wr := library.Write{ASIN: asin, Status: *in.Status, UpdatedAt: *in.UpdatedAt, Speed: 1.0, Now: a.now()}
	if in.PositionMs != nil {
		wr.PositionMs = *in.PositionMs
	}
	if in.FilePositionMs != nil {
		wr.FilePositionMs = *in.FilePositionMs
	}
	if in.Speed != nil {
		wr.Speed = *in.Speed
	}
	if wr.PositionMs < 0 || wr.FilePositionMs < 0 || math.IsNaN(wr.Speed) || wr.Speed < 0.5 || wr.Speed > 3.0 {
		bad("position_ms and file_position_ms must be >= 0 and speed within 0.5-3.0")
		return
	}
	if in.FilePath != nil {
		if !cleanText(*in.FilePath, library.MaxPathBytes) {
			bad("file_path must be at most 512 bytes without control characters")
			return
		}
		wr.FilePath = in.FilePath
	}
	if in.ReleaseHash != nil && *in.ReleaseHash != "" {
		if !hashRe.MatchString(*in.ReleaseHash) {
			bad("release_hash must be a lowercase 40-hex info hash")
			return
		}
		rel, err := a.d.Store.GetRelease(r.Context(), *in.ReleaseHash)
		if err != nil {
			a.libraryFail(w, err)
			return
		}
		if rel == nil {
			bad("release_hash is not a known release")
			return
		}
		wr.ReleaseHash = in.ReleaseHash
	}

	u, _, err := a.settings(r)
	if err != nil {
		a.fail(w, err)
		return
	}
	// Unknown ASINs are fetched into the catalog first (the entry references the book).
	if _, err := a.d.Catalog.EnsureBook(r.Context(), u.Settings.Region, asin); err != nil {
		a.catalogFail(w, err)
		return
	}
	e, err := a.d.Library.PutEntry(r.Context(), u.ID, wr)
	if err != nil {
		if errors.Is(err, library.ErrUnknownBook) {
			WriteError(w, http.StatusNotFound, "not_found", "unknown book")
			return
		}
		a.libraryFail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toEntry(e))
}

func (a *app) deleteLibrary(w http.ResponseWriter, r *http.Request) {
	if asin := r.PathValue("asin"); metadata.ValidASIN(asin) {
		if err := a.d.Library.DeleteEntry(r.Context(), a.userID(r), asin); err != nil {
			a.libraryFail(w, err)
			return
		}
	}
	w.WriteHeader(http.StatusNoContent)
}

func (a *app) listBookmarks(w http.ResponseWriter, r *http.Request) {
	asin, ok := libraryASIN(w, r)
	if !ok {
		return
	}
	bs, err := a.d.Library.ListBookmarks(r.Context(), a.userID(r), asin)
	if err != nil {
		a.libraryFail(w, err)
		return
	}
	out := make([]bookmarkDTO, len(bs))
	for i, b := range bs {
		out[i] = toBookmark(b)
	}
	WriteJSON(w, http.StatusOK, map[string]any{"bookmarks": out})
}

func (a *app) createBookmark(w http.ResponseWriter, r *http.Request) {
	asin, ok := libraryASIN(w, r)
	if !ok {
		return
	}
	var in struct {
		PositionMs *int64  `json:"position_ms"`
		Note       string  `json:"note"`
		ClientID   *string `json:"client_id"`
	}
	if !decode(w, r, &in) {
		return
	}
	note := strings.TrimSpace(in.Note)
	if in.PositionMs == nil || *in.PositionMs < 0 || utf8.RuneCountInString(note) > library.MaxNoteChars || !utf8.ValidString(note) || strings.IndexFunc(note, unicode.IsControl) >= 0 {
		WriteError(w, http.StatusBadRequest, "invalid_request", "position_ms must be >= 0 and note at most 280 characters")
		return
	}
	clientID := ""
	if in.ClientID != nil {
		if clientID = *in.ClientID; !clientIDRe.MatchString(clientID) {
			WriteError(w, http.StatusBadRequest, "invalid_request", "client_id must be 1-64 characters of A-Z, a-z, 0-9, - and _ (a UUID)")
			return
		}
	}
	uid := a.userID(r)
	b, err := a.d.Library.CreateBookmark(r.Context(), uid, asin, *in.PositionMs, note, clientID)
	if errors.Is(err, library.ErrNotFound) {
		// The book is not in the library: add it as `saved` (it must exist in the catalog; the entry
		// count limit applies), then create the bookmark.
		u, _, serr := a.settings(r)
		if serr != nil {
			a.fail(w, serr)
			return
		}
		if _, err := a.d.Catalog.EnsureBook(r.Context(), u.Settings.Region, asin); err != nil {
			a.catalogFail(w, err)
			return
		}
		if _, err := a.d.Library.EnsureSaved(r.Context(), uid, asin); err != nil {
			if errors.Is(err, library.ErrUnknownBook) {
				WriteError(w, http.StatusNotFound, "not_found", "unknown book")
				return
			}
			a.libraryFail(w, err)
			return
		}
		b, err = a.d.Library.CreateBookmark(r.Context(), uid, asin, *in.PositionMs, note, clientID)
	}
	if err != nil {
		a.libraryFail(w, err)
		return
	}
	WriteJSON(w, http.StatusOK, toBookmark(b))
}

func (a *app) deleteBookmark(w http.ResponseWriter, r *http.Request) {
	if asin := r.PathValue("asin"); metadata.ValidASIN(asin) {
		if err := a.d.Library.DeleteBookmark(r.Context(), a.userID(r), asin, r.PathValue("bookmarkId")); err != nil {
			a.libraryFail(w, err)
			return
		}
	}
	w.WriteHeader(http.StatusNoContent)
}
