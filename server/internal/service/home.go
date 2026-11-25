package service

import (
	"context"
	"slices"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/AresiusXP/earmark/server/internal/metadata"
	"github.com/AresiusXP/earmark/server/internal/model"
)

// Kinds of home shelf. The app titles a shelf from its kind and arg.
const (
	ShelfNextInSeries   = "next_in_series"   // arg: series title
	ShelfMoreFromAuthor = "more_from_author" // arg: author name
	ShelfBestSellers    = "bestsellers"
	ShelfGenre          = "genre" // arg: a metadata.GenreKeys key
	ShelfNewReleases    = "new_releases"
)

const (
	homeShelfBooks   = 12 // books per shelf
	homeMinBooks     = 4  // a generic shelf with fewer books is left out
	homeMinPersonal  = 3  // same for "more from author"
	homeSeriesShelfs = 2
	homeAuthorShelfs = 2
	homeGenreShelfs  = 3
	homeShelfTimeout = 15 * time.Second
	doneFraction     = 0.8 // a book this far in counts as read for "next in series"
)

// HomeEntry is one book of the caller's library as the home shelves see it, most recent first.
type HomeEntry struct {
	Book model.Book
	// Started is true once the book was opened (listening or finished), not merely saved.
	Started bool
	// Done is true for a finished book or one listened to for 80 % or more.
	Done bool
}

// Shelf is one named row of the home screen.
type Shelf struct {
	ID    string
	Kind  string
	Arg   string
	Books []model.Book
}

// HomeMetadata is the part of the metadata client that builds the home shelves. A Metadata that
// does not implement it only gets the best-seller shelf.
type HomeMetadata interface {
	AuthorBooks(ctx context.Context, region, language, author string) ([]model.Book, error)
	SeriesBooks(ctx context.Context, region, language, series, author string) ([]model.Book, error)
	GenreBooks(ctx context.Context, region, language, genre, sort string) ([]model.Book, error)
	NewBooks(ctx context.Context, region, language string) ([]model.Book, error)
}

type shelfSpec struct {
	id, kind, arg string
	min           int
	fetch         func(ctx context.Context) ([]model.Book, error)
	// narrow post-processes the fetched books (series shelves keep the books after the last one read).
	narrow func([]model.Book) []model.Book
}

// HomeFor builds the home shelves of one user: what to read next in the series and by the authors
// they listened to, then the best sellers, a few genres and the newest releases. A book shows up in
// one shelf only and never when the user already has it; shelves too short are left out. It fails
// only when no shelf could be built because the catalog was unreachable.
func (s *Service) HomeFor(ctx context.Context, p Prefs, library []HomeEntry) ([]Shelf, error) {
	owned := make(map[string]bool, len(library))
	for _, e := range library {
		owned[e.Book.ASIN] = true
	}
	specs := []shelfSpec{}
	hm, extended := s.d.Metadata.(HomeMetadata)
	if extended {
		specs = append(specs, s.seriesSpecs(p, hm, library, owned)...)
		specs = append(specs, s.authorSpecs(p, hm, library)...)
	}
	specs = append(specs, shelfSpec{
		id: "bestsellers", kind: ShelfBestSellers, min: homeMinBooks,
		fetch: func(ctx context.Context) ([]model.Book, error) { return s.DiscoverFor(ctx, p, 0) },
	})
	if extended {
		keys := metadata.GenreKeys()
		_, week := s.d.Clock().ISOWeek()
		for i := 0; i < homeGenreShelfs; i++ {
			key := keys[(week+i)%len(keys)]
			specs = append(specs, shelfSpec{
				id: "genre-" + key, kind: ShelfGenre, arg: key, min: homeMinBooks,
				fetch: func(ctx context.Context) ([]model.Book, error) {
					return s.catalogLookup(ctx, p, "genre\x00"+key+"\x00"+metadata.SortBestSellers,
						func(ctx context.Context, region, lang string) ([]model.Book, error) {
							return hm.GenreBooks(ctx, region, lang, key, metadata.SortBestSellers)
						})
				},
			})
		}
		specs = append(specs, shelfSpec{
			id: "new", kind: ShelfNewReleases, min: homeMinBooks,
			fetch: func(ctx context.Context) ([]model.Book, error) {
				return s.catalogLookup(ctx, p, "new",
					func(ctx context.Context, region, lang string) ([]model.Book, error) {
						return hm.NewBooks(ctx, region, lang)
					})
			},
		})
	}

	results := make([][]model.Book, len(specs))
	errs := make([]error, len(specs))
	var wg sync.WaitGroup
	for i, sp := range specs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			errs[i] = safely(func() error {
				ctx, cancel := context.WithTimeout(ctx, homeShelfTimeout)
				defer cancel()
				books, err := sp.fetch(ctx)
				if err != nil {
					return err
				}
				if sp.narrow != nil {
					books = sp.narrow(books)
				}
				results[i] = books
				return nil
			})
		}()
	}
	wg.Wait()

	seen := cloneSet(owned)
	out := []Shelf{}
	var firstErr error
	answered := 0
	for i, sp := range specs {
		if errs[i] != nil {
			if firstErr == nil {
				firstErr = errs[i]
			}
			s.log.Warn("home shelf failed", "kind", sp.kind, "err", errs[i])
			continue
		}
		answered++
		books := []model.Book{}
		for _, b := range results[i] {
			if len(books) == homeShelfBooks {
				break
			}
			if seen[b.ASIN] {
				continue
			}
			books = append(books, b)
		}
		if len(books) < sp.min {
			continue
		}
		for _, b := range books {
			seen[b.ASIN] = true
		}
		out = append(out, Shelf{ID: sp.id, Kind: sp.kind, Arg: sp.arg, Books: books})
	}
	if answered == 0 && firstErr != nil {
		return nil, firstErr
	}
	return out, nil
}

func cloneSet(in map[string]bool) map[string]bool {
	out := make(map[string]bool, len(in)+64)
	for k := range in {
		out[k] = true
	}
	return out
}

// seriesSpecs makes a "next in series" shelf for the series the user read furthest recently.
func (s *Service) seriesSpecs(p Prefs, hm HomeMetadata, library []HomeEntry, owned map[string]bool) []shelfSpec {
	type progress struct {
		series, author string
		last           float64
	}
	var order []string
	byKey := map[string]*progress{}
	for _, e := range library {
		b := e.Book
		if !e.Done || strings.TrimSpace(b.Series) == "" {
			continue
		}
		pos, err := strconv.ParseFloat(strings.TrimSpace(b.SeriesPosition), 64)
		if err != nil {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(b.Series))
		pr := byKey[key]
		if pr == nil {
			pr = &progress{series: strings.TrimSpace(b.Series), last: pos}
			if len(b.Authors) > 0 {
				pr.author = b.Authors[0]
			}
			byKey[key] = pr
			order = append(order, key)
		}
		pr.last = max(pr.last, pos)
	}
	var specs []shelfSpec
	for _, key := range order {
		if len(specs) == homeSeriesShelfs {
			break
		}
		pr := byKey[key]
		specs = append(specs, shelfSpec{
			id: "series-" + key, kind: ShelfNextInSeries, arg: pr.series, min: 1,
			fetch: func(ctx context.Context) ([]model.Book, error) {
				return s.catalogLookup(ctx, p, "series\x00"+key+"\x00"+strings.ToLower(pr.author),
					func(ctx context.Context, region, lang string) ([]model.Book, error) {
						return hm.SeriesBooks(ctx, region, lang, pr.series, pr.author)
					})
			},
			narrow: func(books []model.Book) []model.Book { return nextInSeries(books, pr.last, owned) },
		})
	}
	return specs
}

// nextInSeries keeps the books positioned after last that the user does not own, in series order.
func nextInSeries(books []model.Book, last float64, owned map[string]bool) []model.Book {
	type pos struct {
		b model.Book
		n float64
	}
	var ps []pos
	for _, b := range books {
		n, err := strconv.ParseFloat(strings.TrimSpace(b.SeriesPosition), 64)
		if err != nil || n <= last || owned[b.ASIN] {
			continue
		}
		ps = append(ps, pos{b, n})
	}
	sort.SliceStable(ps, func(i, j int) bool { return ps[i].n < ps[j].n })
	out := make([]model.Book, len(ps))
	for i, p := range ps {
		out[i] = p.b
	}
	return out
}

// authorSpecs makes "more from" shelves for the authors of the most recently started books.
func (s *Service) authorSpecs(p Prefs, hm HomeMetadata, library []HomeEntry) []shelfSpec {
	var authors []string
	for _, e := range library {
		if !e.Started || len(e.Book.Authors) == 0 {
			continue
		}
		a := strings.TrimSpace(e.Book.Authors[0])
		if a == "" || slices.ContainsFunc(authors, func(x string) bool { return strings.EqualFold(x, a) }) {
			continue
		}
		authors = append(authors, a)
		if len(authors) == homeAuthorShelfs {
			break
		}
	}
	specs := make([]shelfSpec, 0, len(authors))
	for _, a := range authors {
		specs = append(specs, shelfSpec{
			id: "author-" + strings.ToLower(a), kind: ShelfMoreFromAuthor, arg: a, min: homeMinPersonal,
			fetch: func(ctx context.Context) ([]model.Book, error) {
				return s.catalogLookup(ctx, p, "author\x00"+strings.ToLower(a),
					func(ctx context.Context, region, lang string) ([]model.Book, error) {
						return hm.AuthorBooks(ctx, region, lang, a)
					})
			},
		})
	}
	return specs
}
