package service

import (
	"context"
	"errors"
	"strconv"
	"testing"

	"github.com/AresiusXP/earmark/server/internal/model"
)

type homeMeta struct {
	*fakeMeta
	author, series, genre, newest []model.Book
	err                           error
}

func (h *homeMeta) AuthorBooks(_ context.Context, _, _, _ string) ([]model.Book, error) {
	return h.author, h.err
}
func (h *homeMeta) SeriesBooks(_ context.Context, _, _, s, a string) ([]model.Book, error) {
	return h.series, h.err
}
func (h *homeMeta) GenreBooks(_ context.Context, _, _, g, _ string) ([]model.Book, error) {
	return h.genre, h.err
}
func (h *homeMeta) NewBooks(context.Context, string, string) ([]model.Book, error) {
	return h.newest, h.err
}

func mkBooks(prefix string, n int) []model.Book {
	out := make([]model.Book, n)
	for i := range out {
		out[i] = model.Book{ASIN: prefix + strconv.Itoa(i), Title: prefix, Authors: []string{"A"}}
	}
	return out
}

func seriesBook(asin, series string, pos string) model.Book {
	return model.Book{ASIN: asin, Title: asin, Authors: []string{"Sanderson"}, Series: series, SeriesPosition: pos}
}

func TestHomeForShelves(t *testing.T) {
	hm := &homeMeta{fakeMeta: newFakeMeta()}
	hm.fakeMeta.discover["us|en|0"] = mkBooks("best", 6)
	hm.series = []model.Book{
		seriesBook("S1", "Mistborn", "1"), seriesBook("S3", "Mistborn", "3"), seriesBook("S2", "Mistborn", "2"),
		seriesBook("S2.5", "Mistborn", "2.5"), seriesBook("OWN", "Mistborn", "4"), seriesBook("NOPOS", "Mistborn", ""),
	}
	hm.author = append(mkBooks("auth", 5), model.Book{ASIN: "best0", Title: "dup", Authors: []string{"A"}})
	hm.genre = mkBooks("gen", 2) // too short: dropped
	hm.newest = mkBooks("new", 5)
	e := newEnv(t, func(e *env, d *Deps) { d.Metadata = hm })

	lib := []HomeEntry{
		{Book: seriesBook("S2", "Mistborn", "2"), Started: true, Done: true},
		{Book: seriesBook("OWN", "Mistborn", "4"), Started: false},
		{Book: model.Book{ASIN: "x", Authors: []string{"Other"}}, Started: true},
	}
	// S2 is owned too; the user read up to book 2, so 2.5 and 3 are next (4 is owned).
	shelves, err := e.svc.HomeFor(context.Background(), Prefs{Region: "us", Languages: []string{"en"}}, lib)
	if err != nil {
		t.Fatal(err)
	}
	var kinds []string
	for _, s := range shelves {
		kinds = append(kinds, s.Kind)
	}
	// The second author shelf is empty after the first took the books, so it is dropped.
	want := []string{ShelfNextInSeries, ShelfMoreFromAuthor, ShelfBestSellers, ShelfNewReleases}
	if len(kinds) != len(want) {
		t.Fatalf("kinds %v, want %v", kinds, want)
	}
	for i := range want {
		if kinds[i] != want[i] {
			t.Fatalf("kinds %v, want %v", kinds, want)
		}
	}
	next := shelves[0]
	if next.Arg != "Mistborn" || len(next.Books) != 2 || next.Books[0].ASIN != "S2.5" || next.Books[1].ASIN != "S3" {
		t.Errorf("series shelf %+v", next)
	}
	// "best0" belongs to the author shelves' dup; it must be in exactly one shelf.
	count := map[string]int{}
	for _, s := range shelves {
		for _, b := range s.Books {
			count[b.ASIN]++
			if b.ASIN == "OWN" || b.ASIN == "S2" {
				t.Errorf("owned book %s in shelf %s", b.ASIN, s.ID)
			}
		}
	}
	for a, n := range count {
		if n > 1 {
			t.Errorf("%s appears in %d shelves", a, n)
		}
	}
}

func TestHomeForLimitsAndFailure(t *testing.T) {
	hm := &homeMeta{fakeMeta: newFakeMeta(), err: errors.New("boom")}
	hm.fakeMeta.errs["en"] = errors.New("boom")
	e := newEnv(t, func(e *env, d *Deps) { d.Metadata = hm })
	if _, err := e.svc.HomeFor(context.Background(), Prefs{Region: "us", Languages: []string{"en"}}, nil); err == nil {
		t.Fatal("expected an error when every shelf failed")
	}

	// Shelves that answered but came out too short are an empty home, not a failure.
	hm1 := &homeMeta{fakeMeta: newFakeMeta(), genre: mkBooks("g", 1)}
	e1 := newEnv(t, func(e *env, d *Deps) { d.Metadata = hm1 })
	if s, err := e1.svc.HomeFor(context.Background(), Prefs{Region: "us", Languages: []string{"en"}}, nil); err != nil || len(s) != 0 {
		t.Fatalf("empty home: %v %v", s, err)
	}

	// A catalog that only partly answers still yields shelves, capped at 12 books.
	hm2 := &homeMeta{fakeMeta: newFakeMeta(), newest: mkBooks("new", 30), err: nil}
	hm2.fakeMeta.discover["us|en|0"] = mkBooks("best", 3) // short: dropped
	e2 := newEnv(t, func(e *env, d *Deps) { d.Metadata = hm2 })
	shelves, err := e2.svc.HomeFor(context.Background(), Prefs{Region: "us", Languages: []string{"en"}}, nil)
	if err != nil {
		t.Fatal(err)
	}
	if len(shelves) != 1 || shelves[0].Kind != ShelfNewReleases || len(shelves[0].Books) != homeShelfBooks {
		t.Fatalf("shelves %+v", shelves)
	}
}

func TestHomeForPlainMetadata(t *testing.T) {
	m := newFakeMeta()
	m.discover["us|en|0"] = mkBooks("best", 6)
	e := newEnv(t, func(e *env, d *Deps) { d.Metadata = m })
	shelves, err := e.svc.HomeFor(context.Background(), Prefs{Region: "us", Languages: []string{"en"}}, nil)
	if err != nil || len(shelves) != 1 || shelves[0].Kind != ShelfBestSellers {
		t.Fatalf("shelves %+v err %v", shelves, err)
	}
}
