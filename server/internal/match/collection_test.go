package match

import (
	"strings"
	"testing"

	"github.com/AresiusXP/earmark/server/internal/model"
)

var (
	dcc1 = model.Book{ASIN: "B08J5F6C9D", Title: "Dungeon Crawler Carl", Authors: []string{"Matt Dinniman"},
		Narrators: []string{"Jeff Hays"}, Language: "en", Series: "Dungeon Crawler Carl", SeriesPosition: "1", RuntimeMin: 700}
	dcc2 = model.Book{ASIN: "B08M3M5Y3Z", Title: "Carl's Doomsday Scenario", Authors: []string{"Matt Dinniman"},
		Narrators: []string{"Jeff Hays"}, Language: "en", Series: "Dungeon Crawler Carl", SeriesPosition: "2", RuntimeMin: 800}
	clancy = model.Book{Title: "Command Authority", Authors: []string{"Tom Clancy"}, Language: "en",
		Series: "Tom Clancy's Jack Ryan", SeriesPosition: "10", RuntimeMin: 900}
)

func TestScoreSeriesPack(t *testing.T) {
	tests := []struct {
		name   string
		book   model.Book
		raw    string
		sizeMB int64
		match  bool
		coll   bool
		why    string
	}{
		// --- packs of the book's series by the book's author ---
		{"DCC pack for book 1", dcc1, "Dungeon Crawler Carl Books 1-8 - Matt Dinniman", 25000, true, true, "multi-book pack"},
		{"DCC pack for book 2 (title absent)", dcc2, "Dungeon Crawler Carl Books 1-8 - Matt Dinniman", 25000, true, true, "multi-book pack"},
		{"DCC pack, author first, series word", dcc2, "Matt Dinniman - Dungeon Crawler Carl Series (Books 1-7) [M4B] (Jeff Hays)", 18000, true, true, ""},
		{"DCC pack, numeric range", dcc1, "Dungeon Crawler Carl 1-8 (Matt Dinniman) [MP3]", 0, true, true, ""},
		{"DCC pack, dotted scene name", dcc1, "Matt.Dinniman.Dungeon.Crawler.Carl.Complete.Series.Audiobook.M4B", 0, true, true, ""},
		{"complete series", mistborn1, "Brandon Sanderson - The Final Empire - Mistborn Complete Series [MP3]", 0, true, true, ""},
		{"books range", mistborn1, "Brandon Sanderson - Mistborn Series [Books 1-4] - The Final Empire", 0, true, true, ""},
		{"numeric range", reacherPersonal, "Lee Child: Jack Reacher 01-24 - Personal (read by Jeff Harding) + Shorts", 0, true, true, ""},
		{"dutch range", hpNL1, "J.K. Rowling - Harry Potter en de Steen der Wijzen deel 1 tot 7 [NL]", 0, true, true, ""},
		{"all N books", hpEN1, "J.K. Rowling - Harry Potter and the Philosopher's Stone - All 7 Books [MP3]", 0, true, true, ""},
		{"series word without number", mistborn1, "Brandon Sanderson - The Final Empire - Mistborn Series", 0, true, true, ""},
		{"fuzzy series name", dcc1, "Dungeon Crawlr Carl Books 1-8 - Matt Dinniman", 0, true, true, ""},

		// --- still rejected ---
		{"other series of the same author", dcc1, "Matt Dinniman - Some Other Saga Books 1-8", 0, false, false, "collection"},
		{"pack of a different series", dcc1, "The Expanse Books 1-9 - James S.A. Corey", 0, false, false, "collection"},
		{"same series words, different author", dcc1, "Dungeon Crawler Carl Books 1-8 - Jane Doe", 0, false, false, "collection"},
		{"same author pack without the series", dcc1, "Matt Dinniman - Complete Works Collection", 0, false, false, "collection"},
		{"series only half mentioned", dcc1, "Crawler Books 1-8 - Matt Dinniman", 0, false, false, "collection"},
		{"author surname is part of the series name", clancy, "Tom Clancy's Jack Ryan Books 1-5 - Mark Greaney", 0, false, false, "collection"},
		{"pack in the wrong language", dcc1, "Dungeon Crawler Carl Books 1-8 - Matt Dinniman [Nederlands]", 0, false, false, ""},
		{"abridged pack", dcc1, "Dungeon Crawler Carl Books 1-8 - Matt Dinniman (Abridged)", 0, false, false, "abridged"},
		{"sample-sized pack", dcc1, "Dungeon Crawler Carl Books 1-8 - Matt Dinniman", 2, false, false, "sample"},
		{"box set of two books", martian, "Andy Weir - The Martian + Artemis Box Set [M4B]", 0, false, false, "collection"},
		{"dramatised pack", dcc1, "Dungeon Crawler Carl Books 1-8 - Matt Dinniman - Radio Drama", 0, false, false, "dramatisation"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			r := Score(tc.book, rel(tc.raw, tc.sizeMB))
			why := strings.Join(r.Reasons, "; ")
			if r.Match != tc.match || r.Collection != tc.coll {
				t.Errorf("match=%v collection=%v want %v/%v (score %.3f)\nreasons: %s", r.Match, r.Collection, tc.match, tc.coll, r.Score, why)
			}
			if r.Match != (r.Score >= Threshold) {
				t.Errorf("Match=%v inconsistent with score %.3f", r.Match, r.Score)
			}
			if r.Collection && r.Match && r.Score > packCap+1e-9 {
				t.Errorf("pack score %.3f above cap %.2f", r.Score, packCap)
			}
			if tc.why != "" && !strings.Contains(why, tc.why) {
				t.Errorf("reasons lack %q: %s", tc.why, why)
			}
		})
	}
}

func TestSinglesRankAbovePacks(t *testing.T) {
	pack := Score(dcc1, rel("Dungeon Crawler Carl Books 1-8 - Matt Dinniman", 25000))
	single := Score(dcc1, rel("Matt Dinniman - Dungeon Crawler Carl [M4B]", 500))
	bare := Score(dcc1, rel("Matt Dinniman - Dungeon Crawler Carl", 0))
	if !pack.Match || !single.Match || !bare.Match || single.Collection || bare.Collection {
		t.Fatalf("pack=%+v single=%+v bare=%+v", pack, single, bare)
	}
	if single.Score <= pack.Score || bare.Score <= pack.Score {
		t.Errorf("pack %.3f must rank below single %.3f / bare %.3f", pack.Score, single.Score, bare.Score)
	}
}

func TestBookThatIsACollectionIsNotAPack(t *testing.T) {
	book := model.Book{Title: "Dungeon Crawler Carl Complete Series", Authors: []string{"Matt Dinniman"}, Language: "en",
		Series: "Dungeon Crawler Carl"}
	r := Score(book, rel("Matt Dinniman - Dungeon Crawler Carl Complete Series [M4B]", 0))
	if !r.Match || r.Collection {
		t.Errorf("a collection book matches its own release as a plain release: %+v", r)
	}
}
