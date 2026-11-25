package appapi

import (
	"context"
	"log/slog"
	"net/http"
	"time"

	"github.com/AresiusXP/earmark/server/internal/accounts"
	"github.com/AresiusXP/earmark/server/internal/appupdate"
	"github.com/AresiusXP/earmark/server/internal/debrid"
	"github.com/AresiusXP/earmark/server/internal/library"
	"github.com/AresiusXP/earmark/server/internal/metadata"
	"github.com/AresiusXP/earmark/server/internal/model"
	"github.com/AresiusXP/earmark/server/internal/service"
)

// Catalog is the part of *service.Service the catalog routes use.
type Catalog interface {
	SearchFor(ctx context.Context, p service.Prefs, query string) ([]model.Book, error)
	DiscoverFor(ctx context.Context, p service.Prefs, page int) ([]model.Book, error)
	BookFor(ctx context.Context, req service.BookRequest) (*service.BookPage, error)
	EnsureBook(ctx context.Context, region, asin string) (model.Book, error)
	ChapterList(ctx context.Context, region, asin string) (metadata.ChapterList, error)
}

// ReleaseStore is what the release routes need from the database (implemented by store.Postgres).
type ReleaseStore interface {
	GetRelease(ctx context.Context, infoHash string) (*model.Release, error)
	InsertReleaseIfAbsent(ctx context.Context, r model.Release) (bool, error)
	SaveClientFiles(ctx context.Context, infoHash string, files []model.File) ([]model.File, bool, error)
	ClientFiles(ctx context.Context, infoHashes []string) (map[string][]model.File, error)
	// SaveFileDurations: first writer wins per (hash, path); returns the stored values in input order.
	SaveFileDurations(ctx context.Context, infoHash string, ds []library.FileDuration) ([]library.FileDuration, error)
	Files(ctx context.Context, infoHashes []string) (map[string][]model.File, error)
	// SaveFilesIfAbsent: first writer wins, atomically (provider indexes are never replaced).
	SaveFilesIfAbsent(ctx context.Context, infoHash string, files []model.File) (bool, error)
	// SetAvailability is only ever called for shared-cache providers (TorBox).
	SetAvailability(ctx context.Context, provider string, entries []service.Availability) error
	SetUserAvailability(ctx context.Context, fingerprint, provider, infoHash string, cached bool, at time.Time) error
}

// AppDeps are the collaborators of the catalog, release, library and app-update routes.
type AppDeps struct {
	Catalog   Catalog
	Store     ReleaseStore
	Library   library.Store
	Providers debrid.Registry
	// Update is the APK bundle (nil or empty: "available": false).
	Update *appupdate.Service
	// PublicURL is the https origin the app reaches the server at; it makes the APK url.
	PublicURL string
	// AddsPerHour is the cold-add budget per key fingerprint (0 = DefaultAddsPerHour).
	AddsPerHour int
	// MaxWork and MaxWorkPerKey bound the in-flight provider work (prepare, download and HEAD
	// flights) overall and per key fingerprint; excess requests get 429 rate_limited. 0 = defaults.
	MaxWork, MaxWorkPerKey int
	// LinkHosts adds CDN host suffixes per provider to the built-in allowlist (see linkhosts.go).
	LinkHosts map[string][]string
	Clock     func() time.Time
	Logger    *slog.Logger
}

// app holds the state of the routes added by MountApp.
type app struct {
	*API
	d     AppDeps
	log   *slog.Logger
	now   func() time.Time
	allow LinkAllowlist
	links *linkCache
	trk   *tracker
	bud   *budget
	prep  flights[prepResult]
	dl    flights[prepResult]
	// cold is the cold-add step of one (fingerprint, provider, hash), shared by prepare and download.
	cold flights[coldOut]
	work *workGate
	// appDL bounds concurrent APK downloads (the route is unauthenticated).
	appDL chan struct{}
	// updateCap and apkCap are global request-rate caps of the two unauthenticated update routes.
	updateCap, apkCap *accounts.RateCap
}

// MountApp registers the catalog (search, discover, books, chapters), release (prepare, download,
// torrent-files, durations), library and app-update routes on the /v1 mux. Call it once, before serving.
func (a *API) MountApp(d AppDeps) {
	if d.Clock == nil {
		d.Clock = time.Now
	}
	if d.AddsPerHour <= 0 {
		d.AddsPerHour = DefaultAddsPerHour
	}
	if d.Logger == nil {
		d.Logger = a.log
	}
	ap := &app{
		API: a, d: d, log: d.Logger, now: d.Clock, allow: NewLinkAllowlist(d.LinkHosts),
		links: newLinkCache(d.Clock), trk: newTracker(d.Clock), bud: newBudget(d.AddsPerHour, d.Clock),
		work: newWorkGate(d.MaxWork, d.MaxWorkPerKey), appDL: make(chan struct{}, maxAppDownloads),
		updateCap: accounts.NewRateCap(5, 20, d.Clock), apkCap: accounts.NewRateCap(2, 20, d.Clock),
	}
	a.Extend(func(r Router) {
		m, auth := r.Mux, r.Auth
		m.Handle("GET /v1/search", auth(http.HandlerFunc(ap.search)))
		m.Handle("GET /v1/discover", auth(http.HandlerFunc(ap.discover)))
		m.Handle("GET /v1/books/{asin}", auth(http.HandlerFunc(ap.book)))
		m.Handle("GET /v1/books/{asin}/chapters", auth(http.HandlerFunc(ap.chapters)))

		m.Handle("POST /v1/releases/{hash}/prepare", auth(http.HandlerFunc(ap.prepareRoute)))
		// GET also answers HEAD (Go's mux), which the handler treats as side-effect free.
		m.Handle("GET /v1/releases/{hash}/files/{index}/download", auth(http.HandlerFunc(ap.download)))
		m.Handle("POST /v1/releases/{hash}/torrent-files", auth(http.HandlerFunc(ap.torrentFiles)))
		m.Handle("POST /v1/releases/{hash}/durations", auth(http.HandlerFunc(ap.durations)))

		m.Handle("GET /v1/me/library", auth(http.HandlerFunc(ap.listLibrary)))
		m.Handle("GET /v1/me/library/{asin}", auth(http.HandlerFunc(ap.getLibrary)))
		m.Handle("PUT /v1/me/library/{asin}", auth(http.HandlerFunc(ap.putLibrary)))
		m.Handle("DELETE /v1/me/library/{asin}", auth(http.HandlerFunc(ap.deleteLibrary)))
		m.Handle("GET /v1/me/library/{asin}/bookmarks", auth(http.HandlerFunc(ap.listBookmarks)))
		m.Handle("POST /v1/me/library/{asin}/bookmarks", auth(http.HandlerFunc(ap.createBookmark)))
		m.Handle("DELETE /v1/me/library/{asin}/bookmarks/{bookmarkId}", auth(http.HandlerFunc(ap.deleteBookmark)))

		// Outside the auth middleware: the app must be able to update before anything else works.
		m.HandleFunc("GET /v1/app/update", ap.appUpdate)
		m.HandleFunc("GET /v1/app/update/apk", ap.appUpdateAPK)
	})
}
