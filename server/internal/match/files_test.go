package match

import (
	"reflect"
	"testing"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

func filesOf(paths ...string) []model.File {
	out := make([]model.File, len(paths))
	for i, p := range paths {
		out[i] = model.File{Index: i, Path: p, Size: int64(100+i) * mbSize}
	}
	return out
}

func pathsOf(fs []model.File) []string {
	out := []string{}
	for _, f := range fs {
		out = append(out, f.Path)
	}
	return out
}

var (
	dccFlat = []string{
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/Dungeon Crawler Carl, Book 1.m4b",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/Carl's Doomsday Scenario Dungeon Crawler Carl, Book 2.m4b",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/The Princess Who Saved Herself Dungeon Crawler Carl, Book 3.m4b",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/The Dungeon Anarchist's Cookbook Dungeon Crawler Carl, Book 4.m4b",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/The Gate of the Feral Gods, Book 5.m4b",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/The Eye of the Bedlam Bride, Book 6.m4b",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/This Inevitable Ruin, Book 7.m4b",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/cover.jpg",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/info.nfo",
	}
	dccChapters = []string{
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/Dungeon Crawler Carl Book 1/Chapter 01.mp3",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/Dungeon Crawler Carl Book 1/Chapter 02.mp3",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/Dungeon Crawler Carl Book 1/03 - The Beginning.mp3",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/Dungeon Crawler Carl Book 2/Chapter 01.mp3",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/Dungeon Crawler Carl Book 2/Chapter 02.mp3",
		"Dungeon Crawler Carl Books 1-8 - Matt Dinniman/Dungeon Crawler Carl Book 10/Chapter 01.mp3",
	}
	dccNumbered = []string{
		"01 - Dungeon Crawler Carl.m4b",
		"02 - Carl's Doomsday Scenario.m4b",
		"03 - The Princess Who Saved Herself.m4b",
	}
	dccSeriesFolder = []string{
		"Dungeon Crawler Carl/01 - Dungeon Crawler Carl.m4b",
		"Dungeon Crawler Carl/02 - Carl's Doomsday Scenario.m4b",
		"Dungeon Crawler Carl/Book 3.m4b",
	}
	dccBook3 = model.Book{Title: "The Princess Who Saved Herself", Authors: []string{"Matt Dinniman"}, Language: "en",
		Series: "Dungeon Crawler Carl", SeriesPosition: "3"}
	dccBook6 = model.Book{Title: "The Eye of the Bedlam Bride", Authors: []string{"Matt Dinniman"}, Language: "en",
		Series: "Dungeon Crawler Carl", SeriesPosition: "6"}
)

func TestFilterFiles(t *testing.T) {
	stormlight := func(title, pos string) model.Book {
		return model.Book{Title: title, Authors: []string{"Brandon Sanderson"}, Series: "The Stormlight Archive", SeriesPosition: pos}
	}
	dresden := func(title, pos string) model.Book {
		return model.Book{Title: title, Authors: []string{"Jim Butcher"}, Series: "The Dresden Files", SeriesPosition: pos}
	}
	stormFiles := []string{
		"Stormlight Archive/The Stormlight Archive Vol. 1 - The Way of Kings.m4b",
		"Stormlight Archive/The Stormlight Archive Vol. 2 - Words of Radiance.m4b",
		"Stormlight Archive/The Stormlight Archive Vol. 3 - Oathbringer.m4b",
		"Stormlight Archive/The Stormlight Archive Vol. 4 - Rhythm of War.m4b",
		"Stormlight Archive/Stormlight Archive Vol. 10 - Untitled.m4b",
	}
	dresdenFiles := []string{
		"Dresden Files #1 - Storm Front.m4b",
		"Dresden Files #2 - Fool Moon.m4b",
		"Dresden Files #3 - Grave Peril.m4b",
		"Dresden Files #3.5 - Publicity Stunt.m4b",
		"Dresden Files #13 - Turn Coat.m4b",
	}
	hpNL2 := model.Book{Title: "Harry Potter en de Geheime Kamer", Authors: []string{"J.K. Rowling"}, Language: "nl", Series: "Harry Potter", SeriesPosition: "2"}
	hpFiles := []string{
		"Harry Potter/Harry Potter deel 1 - De Steen der Wijzen/01.mp3",
		"Harry Potter/Harry Potter deel 1 - De Steen der Wijzen/02.mp3",
		"Harry Potter/Harry Potter deel 2 - De Geheime Kamer/01.mp3",
		"Harry Potter/Harry Potter deel 2 - De Geheime Kamer/02.mp3",
		"Harry Potter/Harry Potter deel 2 - De Geheime Kamer/cover.jpg",
		"Harry Potter/Harry Potter deel 3 - De Gevangene van Azkaban/01.mp3",
	}
	catch22 := model.Book{Title: "Catch-22", Authors: []string{"Joseph Heller"}, Series: "Catch-22", SeriesPosition: "1"}
	noSeries := model.Book{Title: "Dungeon Crawler Carl", Authors: []string{"Matt Dinniman"}}

	tests := []struct {
		name  string
		book  model.Book
		files []string
		want  []string
	}{
		{"flat pack, book 1", dcc1, dccFlat, []string{dccFlat[0]}},
		{"flat pack, book 2 (title only inside the series name)", dcc2, dccFlat, []string{dccFlat[1]}},
		{"flat pack, book 3 by title and position", dccBook3, dccFlat, []string{dccFlat[2]}},
		{"flat pack, book 6 by title", dccBook6, dccFlat, []string{dccFlat[5]}},
		{"flat pack, book not in the pack", model.Book{Title: "The Butcher's Masquerade", Authors: []string{"Matt Dinniman"}, Series: "Dungeon Crawler Carl", SeriesPosition: "8"}, dccFlat, []string{}},
		{"flat pack, another series' book", model.Book{Title: "Project Hail Mary", Authors: []string{"Andy Weir"}}, dccFlat, []string{}},
		{"chapter folders, book 1", dcc1, dccChapters, dccChapters[:3]},
		{"chapter folders, book 2", dcc2, dccChapters, dccChapters[3:5]},
		{"chapter folders, book 1 is not book 10", dcc1, dccChapters[5:], []string{}},
		{"numbered file names, book 1", dcc1, dccNumbered, dccNumbered[:1]},
		{"numbered file names, book 2", dcc2, dccNumbered, dccNumbered[1:2]},
		{"numbered file names, book 3", dccBook3, dccNumbered, dccNumbered[2:]},
		{"series folder with numbered files, book 1", dcc1, dccSeriesFolder, dccSeriesFolder[:1]},
		{"series folder with numbered files, book 2", dcc2, dccSeriesFolder, dccSeriesFolder[1:2]},
		{"series folder, 'Book 3.m4b' is book 3", dccBook3, dccSeriesFolder, dccSeriesFolder[2:]},
		{"series folder, 'Book 1.m4b' under a title-only folder", dcc1, []string{"Dungeon Crawler Carl/Book 1.m4b", "Dungeon Crawler Carl/Book 2.m4b"}, []string{"Dungeon Crawler Carl/Book 1.m4b"}},
		{"Vol. N, book 3", stormlight("Oathbringer", "3"), stormFiles, []string{stormFiles[2]}},
		{"Vol. N, book 1", stormlight("The Way of Kings", "1"), stormFiles, []string{stormFiles[0]}},
		{"Vol. N, book 10 is not book 1", stormlight("Untitled", "10"), stormFiles, []string{stormFiles[4]}},
		{"#N, book 3", dresden("Grave Peril", "3"), dresdenFiles, []string{dresdenFiles[2]}},
		{"#N, novella 3.5 is not book 3", dresden("Grave Peril", "3"), dresdenFiles[3:4], []string{}},
		{"#N, novella 3.5", dresden("Publicity Stunt", "3.5"), dresdenFiles, []string{dresdenFiles[3]}},
		{"#N, book 13 by position only", dresden("Turn Coat", "13"), dresdenFiles, []string{dresdenFiles[4]}},
		{"dutch Deel N folders, book 2", hpNL2, hpFiles, hpFiles[2:4]},
		{"number in the book title is not a position", catch22, []string{"Joseph Heller/Catch-22.m4b", "Joseph Heller/Catch-22, Book 2.m4b"}, []string{"Joseph Heller/Catch-22.m4b"}},
		{"book 1 matches its plain file", dcc1, []string{"Dungeon Crawler Carl - Matt Dinniman.m4b"}, []string{"Dungeon Crawler Carl - Matt Dinniman.m4b"}},
		{"title words of another book do not match without a position", dcc1, []string{"Dungeon Crawler Carl - Carl's Doomsday Scenario.m4b"}, []string{}},
		{"a whole-series file cannot be split", dcc1, []string{"Dungeon Crawler Carl Books 1-8 (Complete).m4b"}, []string{}},
		{"a position of another book vetoes the title match", dcc1, []string{"Carl's Doomsday Scenario Dungeon Crawler Carl, Book 2.m4b"}, []string{}},
		{"non-audio files never match", dcc1, []string{"Dungeon Crawler Carl, Book 1.pdf", "Dungeon Crawler Carl Book 1/cover.jpg"}, []string{}},
		{"windows separators", dcc1, []string{`Pack\Dungeon Crawler Carl Book 1\Chapter 01.mp3`, `Pack\Dungeon Crawler Carl Book 2\Chapter 01.mp3`}, []string{`Pack\Dungeon Crawler Carl Book 1\Chapter 01.mp3`}},
		{"dotted folder name", dcc1, []string{"Dungeon.Crawler.Carl.Book.1/01.mp3", "Dungeon.Crawler.Carl.Book.2/01.mp3"}, []string{"Dungeon.Crawler.Carl.Book.1/01.mp3"}},
		{"book without series position, plain title", noSeries, dccFlat[:1], []string{dccFlat[0]}},
		{"book without series position cannot veto but also not match others by title", noSeries, dccFlat[1:2], []string{}},
		{"chapter folder named by title only", dcc1, []string{"Dungeon Crawler Carl/Part 01.mp3", "Dungeon Crawler Carl/Part 02.mp3"}, []string{"Dungeon Crawler Carl/Part 01.mp3", "Dungeon Crawler Carl/Part 02.mp3"}},
		{"nested: pack folder then book folder", dcc2, []string{"Matt Dinniman/Dungeon Crawler Carl 1-8/02 - Carl's Doomsday Scenario/Dungeon Crawler Carl - Part 05.mp3", "Matt Dinniman/Dungeon Crawler Carl 1-8/01 - Dungeon Crawler Carl/Dungeon Crawler Carl - Part 05.mp3"}, []string{"Matt Dinniman/Dungeon Crawler Carl 1-8/02 - Carl's Doomsday Scenario/Dungeon Crawler Carl - Part 05.mp3"}},
		{"empty list", dcc1, nil, []string{}},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			in := filesOf(tc.files...)
			got := pathsOf(FilterFiles(tc.book, in))
			if !reflect.DeepEqual(got, tc.want) {
				t.Errorf("got  %q\nwant %q", got, tc.want)
			}
			for _, f := range in {
				if FileMatches(tc.book, f.Path) != contains1(tc.want, f.Path) {
					t.Errorf("FileMatches(%q) disagrees with FilterFiles", f.Path)
				}
			}
		})
	}
}

func contains1(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

func TestFilterFilesKeepsInputOrderAndIndexes(t *testing.T) {
	in := []model.File{
		{Index: 7, Path: "B/Dungeon Crawler Carl Book 1/Chapter 10.mp3", Size: 5},
		{Index: 3, Path: "B/Dungeon Crawler Carl Book 1/Chapter 02.mp3", Size: 6},
		{Index: 4, Path: "B/Dungeon Crawler Carl Book 2/Chapter 01.mp3", Size: 7},
	}
	got := FilterFiles(dcc1, in)
	if !reflect.DeepEqual(got, in[:2]) {
		t.Errorf("got %+v", got)
	}
}

func TestIsPack(t *testing.T) {
	tests := []struct {
		name  string
		files []string
		want  bool
	}{
		{"flat pack", dccFlat, true},
		{"chapter folders", dccChapters, true},
		{"vol numbers", []string{"S Vol. 1.m4b", "S Vol. 2.m4b"}, true},
		{"single book", []string{"Dungeon Crawler Carl, Book 1.m4b", "cover.jpg"}, false},
		{"one book in many chapter files", []string{"Dungeon Crawler Carl Book 1/Chapter 01.mp3", "Dungeon Crawler Carl Book 1/Chapter 02.mp3"}, false},
		{"chapter numbering is not a pack", []string{"Title/01 - Intro.mp3", "Title/02 - Next.mp3", "Title/Part 3.mp3"}, false},
		{"range-only names", []string{"Dungeon Crawler Carl Books 1-8.m4b"}, false},
		{"empty", nil, false},
	}
	for _, tc := range tests {
		if got := IsPack(filesOf(tc.files...)); got != tc.want {
			t.Errorf("%s: IsPack = %v, want %v", tc.name, got, tc.want)
		}
	}
}
