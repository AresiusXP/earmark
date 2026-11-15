package match

import (
	"strings"
	"testing"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/parse"
)

const (
	mbSize = int64(1 << 20)
)

func rel(raw string, sizeMB int64) model.Release {
	return model.Release{Title: raw, Size: sizeMB * mbSize, Parsed: parse.Title(raw)}
}

var (
	phm = model.Book{ASIN: "B08G9PRS1K", Title: "Project Hail Mary", Authors: []string{"Andy Weir"},
		Narrators: []string{"Ray Porter"}, Language: "en", RuntimeMin: 970}
	artemis = model.Book{ASIN: "B01M0LOE2N", Title: "Artemis", Subtitle: "A Novel", Authors: []string{"Andy Weir"},
		Narrators: []string{"Rosario Dawson"}, Language: "en", RuntimeMin: 548}
	martian = model.Book{Title: "The Martian", Subtitle: "A Novel", Authors: []string{"Andy Weir"},
		Narrators: []string{"R. C. Bray"}, Language: "en", RuntimeMin: 654}
	hpEN1 = model.Book{Title: "Harry Potter and the Philosopher's Stone", Authors: []string{"J.K. Rowling"},
		Narrators: []string{"Stephen Fry"}, Language: "en", Series: "Harry Potter", SeriesPosition: "1", RuntimeMin: 497}
	hpEN2 = model.Book{Title: "Harry Potter and the Chamber of Secrets", Authors: []string{"J.K. Rowling"},
		Narrators: []string{"Stephen Fry"}, Language: "en", Series: "Harry Potter", SeriesPosition: "2", RuntimeMin: 560}
	hpNL1 = model.Book{Title: "Harry Potter en de Steen der Wijzen", Authors: []string{"J.K. Rowling"},
		Narrators: []string{"Pieter Lutz"}, Language: "nl", Series: "Harry Potter", SeriesPosition: "1", RuntimeMin: 480}
	mistborn1 = model.Book{Title: "The Final Empire", Subtitle: "Mistborn, Book 1", Authors: []string{"Brandon Sanderson"},
		Narrators: []string{"Michael Kramer"}, Language: "en", Series: "Mistborn", SeriesPosition: "1", RuntimeMin: 1480}
	mistborn2 = model.Book{Title: "The Well of Ascension", Authors: []string{"Brandon Sanderson"},
		Language: "en", Series: "Mistborn", SeriesPosition: "2", RuntimeMin: 1700}
	reacherSecret = model.Book{Title: "The Secret", Authors: []string{"Lee Child", "Andrew Child"},
		Language: "en", Series: "Jack Reacher", SeriesPosition: "28", RuntimeMin: 700}
	reacherPersonal = model.Book{Title: "Personal", Authors: []string{"Lee Child"}, Language: "en",
		Series: "Jack Reacher", SeriesPosition: "19", RuntimeMin: 620}
	skyward  = model.Book{Title: "ReDawn", Authors: []string{"Brandon Sanderson", "Janci Patterson"}, Language: "en", RuntimeMin: 180}
	hobbitNL = model.Book{Title: "De Hobbit", Authors: []string{"J.R.R. Tolkien"}, Language: "nl", RuntimeMin: 640}
	hobbitEN = model.Book{Title: "The Hobbit", Authors: []string{"J.R.R. Tolkien"}, Language: "en", RuntimeMin: 665}
	dune     = model.Book{Title: "Dune", Authors: []string{"Frank Herbert"}, Language: "en", Series: "Dune", SeriesPosition: "1", RuntimeMin: 1260}
	carrie   = model.Book{Title: "Carrie", Authors: []string{"Stephen King"}, Language: "en", RuntimeMin: 420}
	atomic   = model.Book{Title: "Atomic Habits", Subtitle: "An Easy & Proven Way to Build Good Habits & Break Bad Ones",
		Authors: []string{"James Clear"}, Language: "en", RuntimeMin: 335}
	hungerTrilogy = model.Book{Title: "The Hunger Games Trilogy", Authors: []string{"Suzanne Collins"}, Language: "en", RuntimeMin: 1900}
	marsianer     = model.Book{Title: "Der Marsianer", Authors: []string{"Andy Weir"}, Language: "de", RuntimeMin: 660}
	chirurg       = model.Book{Title: "De chirurg", Authors: []string{"Tess Gerritsen"}, Language: "nl", RuntimeMin: 600}
	cafe          = model.Book{Title: "Café Müller", Authors: []string{"Zoë Brontë"}, Language: "en", RuntimeMin: 300}
)

func TestScore(t *testing.T) {
	tests := []struct {
		name    string
		book    model.Book
		raw     string
		sizeMB  int64
		want    bool
		whyPart string // substring that must appear in the reasons (optional)
	}{
		// --- positives ---
		{"plain author - title", phm, "Andy Weir - Project Hail Mary (Ray Porter) [2021] [M4B 64kbps]", 450, true, "narrator"},
		{"dotted name", phm, "Andy.Weir.-.Project.Hail.Mary.2021.Audiobook.M4B", 480, true, ""},
		{"title - author [fmt]", martian, "The Martian - Andy Weir [MP3]", 300, true, ""},
		{"title by author", martian, "The Martian by Andy Weir 2013 [160kbps]", 0, true, ""},
		{"subtitle optional", artemis, "Andy Weir - Artemis (2017) [Unabridged] [MP3 128 kbit]", 500, true, ""},
		{"unknown size is neutral", phm, "Andy Weir - Project Hail Mary", 0, true, ""},
		{"article and punctuation insensitive", hpEN1, "J.K. Rowling - Harry Potter & the Philosophers Stone (Stephen Fry) [M4B]", 300, true, ""},
		{"apostrophe, uk title", hpEN1, "J.K. Rowling - Harry Potter and the Philosopher's Stone [MP3 64kbps]", 250, true, ""},
		{"series position equal", mistborn1, "Brandon Sanderson - Mistborn Book 1 - The Final Empire", 0, true, "series position 1"},
		{"series in group", mistborn1, "Brandon Sanderson - The Final Empire (Mistborn #1) [GraphicAudio]", 0, true, ""},
		{"series number prefix", mistborn2, "Brandon Sanderson - Mistborn 02 - The Well of Ascension - read by Michael Kramer", 900, true, ""},
		{"series title with colon in title", reacherSecret, "The Secret: Jack Reacher, 28 - Lee Child, Andrew Child - 2023 (miok) [Audiobook]", 400, true, ""},
		{"single word title, series pos in name", reacherPersonal, "Lee Child - Personal (Jack Reacher #19)", 0, true, ""},
		{"surname-only author, book number in title", reacherPersonal, "Lee Child Personal Jack Reacher Book 19", 0, true, ""},
		{"one of two authors is enough", skyward, "Brandon Sanderson - ReDawn [MP3]", 0, true, "author match 1/2"},
		{"book subtitle present in release", atomic, "James Clear - Atomic Habits - An Easy & Proven Way to Build Good Habits & Break Bad Ones [M4B]", 200, true, ""},
		{"book subtitle absent in release", atomic, "James Clear - Atomic Habits [M4B]", 200, true, ""},
		{"dutch book, dutch release", hobbitNL, "De Hobbit - J.R.R. Tolkien luisterboek audioboek", 0, true, "language nl matches"},
		{"dutch book, dutch release 2", chirurg, "Tess Gerritsen - De chirurg (Audioboek)(Dutch) 2Lions-Team", 0, true, ""},
		{"dutch edition HP", hpNL1, "J.K. Rowling - Harry Potter en de Steen der Wijzen - gelezen door Pieter Lutz [NL] [MP3]", 0, true, ""},
		{"german book, german release", marsianer, "Andy Weir - Der Marsianer - ungekürzt - Hörbuch [MP3]", 0, true, ""},
		{"accents and ligatures", cafe, "Zoe Bronte - Cafe Muller [M4B]", 0, true, ""},
		{"one-letter typo in a long word is tolerated", martian, "Andy Weir - The Martan [MP3]", 0, true, ""},
		{"collection requested explicitly", hungerTrilogy, "Suzanne Collins - The Hunger Games Trilogy [MP3]", 0, true, ""},
		{"typo in surname", reacherPersonal, "Lee Childs - Personal (Jack Reacher #19)", 0, true, ""},
		{"plausible size", phm, "Andy Weir - Project Hail Mary [M4B]", 520, true, "size plausible"},

		// --- negatives: wrong book, same author ---
		{"PHM vs Artemis", phm, "Andy Weir - Artemis", 0, false, ""},
		{"Artemis vs PHM", artemis, "Andy Weir - Project Hail Mary (Ray Porter) [M4B]", 0, false, ""},
		{"Artemis vs Martian", artemis, "The Martian - Andy Weir [MP3]", 0, false, ""},
		{"HP stone vs chamber", hpEN1, "J.K. Rowling - Harry Potter and the Chamber of Secrets (Stephen Fry) [M4B]", 0, false, ""},
		{"HP chamber vs stone", hpEN2, "J.K. Rowling - Harry Potter and the Philosopher's Stone (Stephen Fry) [M4B]", 0, false, ""},
		{"Dune vs Dune Messiah", dune, "Frank Herbert - Dune Messiah (Dune #2) [M4B]", 0, false, ""},
		{"Dune vs Dune Messiah plain", dune, "Frank Herbert - Dune Messiah", 0, false, ""},
		{"Mistborn 1 vs 2 by series/title", mistborn1, "Brandon Sanderson - Mistborn Book 2 - Well of Ascension", 0, false, ""},
		{"Mistborn 2 vs 1", mistborn2, "Brandon Sanderson - The Final Empire (Mistborn #1) [GraphicAudio]", 0, false, ""},
		{"same title, other number of same series", reacherPersonal, "Lee Child - Personal (Jack Reacher #20)", 0, false, "different book of the same series"},
		{"Reacher other book", reacherPersonal, "Lee Child - Never Go Back (Jack Reacher #18)", 0, false, ""},

		// --- negatives: author ---
		{"same title, different author", carrie, "Carrie - Jane Doe [Unabridged]", 0, false, "no book author"},
		{"title only, no author", carrie, "Carrie (Unabridged)", 0, false, "no book author"},
		{"HP nl title without author", hpNL1, "Harry Potter En De Geheime Kamer (voorgelezen door Jan Meng)", 0, false, ""},
		{"author present but title absent", phm, "Andy Weir - Greatest Hits [MP3]", 0, false, ""},
		{"book without authors cannot match", model.Book{Title: "Carrie"}, "Stephen King - Carrie", 0, false, "no book author"},

		// --- negatives: language ---
		{"dutch release for english book", hpEN1, "J.K. Rowling - Harry Potter and the Philosopher's Stone [Nederlands] [MP3]", 0, false, "language mismatch"},
		{"dutch edition vs english book", hpEN1, "J.K. Rowling - Harry Potter en de Steen der Wijzen - gelezen door Fred Butter [NL] [MP3]", 0, false, ""},
		{"english release for dutch book", hobbitNL, "J.R.R. Tolkien - De Hobbit [English] [M4B]", 0, false, "language mismatch"},
		{"german release for english book", martian, "The Martian - Andy Weir - Hörbuch [MP3]", 0, false, "language mismatch"},
		{"english martian vs german book", marsianer, "The Martian - Andy Weir [MP3]", 0, false, ""},
		{"dutch hobbit vs english book", hobbitEN, "De Hobbit - J.R.R. Tolkien luisterboek audioboek", 0, false, ""},

		// --- negatives: collections ---
		{"complete series", mistborn1, "Brandon Sanderson - The Final Empire - Mistborn Complete Series [MP3]", 0, false, "collection"},
		{"books range", mistborn1, "Brandon Sanderson - Mistborn Series [Books 1-4] - The Final Empire", 0, false, "collection"},
		{"box set", martian, "Andy Weir - The Martian + Artemis Box Set [M4B]", 0, false, "collection"},
		{"collection word", carrie, "Stephen King Collection - Carrie, It, Misery", 0, false, "collection"},
		{"numeric range", reacherPersonal, "Lee Child: Jack Reacher 01-24 - Personal (read by Jeff Harding) + Shorts", 0, false, "collection"},
		{"dutch range", hpNL1, "J.K. Rowling - Harry Potter en de Steen der Wijzen deel 1 tot 7 [NL]", 0, false, "collection"},
		{"all N books", hpEN1, "J.K. Rowling - Harry Potter and the Philosopher's Stone - All 7 Books [MP3]", 0, false, "collection"},
		{"anthology", carrie, "Stephen King - Carrie - Anthology [MP3]", 0, false, "collection"},
		{"series word without number", mistborn1, "Brandon Sanderson - The Final Empire - Mistborn Series", 0, false, "collection"},

		// --- negatives: size ---
		{"tiny sample", phm, "Andy Weir - Project Hail Mary [M4B]", 3, false, "sample"},
		{"far too large", phm, "Andy Weir - Project Hail Mary [M4B]", 9000, false, "far too large"},
		{"too small for runtime", phm, "Andy Weir - Project Hail Mary [M4B]", 40, false, "too small"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			r := Score(tc.book, rel(tc.raw, tc.sizeMB))
			if r.Match != tc.want {
				t.Errorf("Score(%q vs %q) match=%v want %v (score %.3f)\nreasons: %s",
					tc.book.Title, tc.raw, r.Match, tc.want, r.Score, strings.Join(r.Reasons, "; "))
			}
			if r.Match != (r.Score >= Threshold) {
				t.Errorf("Match=%v inconsistent with score %.3f and Threshold %.2f", r.Match, r.Score, Threshold)
			}
			if r.Score < 0 || r.Score > 1 {
				t.Errorf("score %.3f out of range", r.Score)
			}
			if tc.whyPart != "" && !strings.Contains(strings.Join(r.Reasons, "; "), tc.whyPart) {
				t.Errorf("reasons lack %q: %s", tc.whyPart, strings.Join(r.Reasons, "; "))
			}
		})
	}
}

func TestScoreOrdering(t *testing.T) {
	score := func(b model.Book, raw string, mb int64) float64 { return Score(b, rel(raw, mb)).Score }

	t.Run("narrator match scores higher", func(t *testing.T) {
		with := score(phm, "Andy Weir - Project Hail Mary (Ray Porter) [M4B]", 0)
		without := score(phm, "Andy Weir - Project Hail Mary [M4B]", 0)
		if with <= without {
			t.Errorf("with narrator %.3f <= without %.3f", with, without)
		}
	})
	t.Run("plausible size scores higher than unknown", func(t *testing.T) {
		if a, b := score(phm, "Andy Weir - Project Hail Mary", 450), score(phm, "Andy Weir - Project Hail Mary", 0); a <= b {
			t.Errorf("plausible %.3f <= unknown %.3f", a, b)
		}
	})
	t.Run("matching language scores higher than unknown", func(t *testing.T) {
		if a, b := score(hobbitNL, "De Hobbit - J.R.R. Tolkien [Nederlands]", 0), score(hobbitNL, "De Hobbit - J.R.R. Tolkien", 0); a <= b {
			t.Errorf("explicit language %.3f <= unknown %.3f", a, b)
		}
	})
	t.Run("series position equal beats unknown", func(t *testing.T) {
		if a, b := score(mistborn1, "Brandon Sanderson - Mistborn Book 1 - The Final Empire", 0), score(mistborn1, "Brandon Sanderson - The Final Empire", 0); a <= b {
			t.Errorf("series equal %.3f <= unknown %.3f", a, b)
		}
	})
	t.Run("exact title beats extra words", func(t *testing.T) {
		if a, b := score(dune, "Frank Herbert - Dune", 0), score(dune, "Frank Herbert - Dune Messiah", 0); a <= b {
			t.Errorf("exact %.3f <= extra %.3f", a, b)
		}
	})
}

func TestCollectionIgnoredWhenBookIsCollection(t *testing.T) {
	book := model.Book{Title: "Harry Potter Complete Collection", Authors: []string{"J.K. Rowling"}, Language: "en"}
	r := Score(book, rel("J.K. Rowling - Harry Potter Complete Collection [MP3]", 0))
	if !r.Match {
		t.Errorf("want match, got %.3f: %v", r.Score, r.Reasons)
	}
}

func TestEmptyInputs(t *testing.T) {
	r := Score(model.Book{}, model.Release{})
	if r.Match || r.Score < 0 || r.Score > 1 {
		t.Errorf("empty input: %+v", r)
	}
}

func TestLooksLikeCollection(t *testing.T) {
	yes := []string{
		"Harry Potter Audiobooks 1-7", "Lee Child Jack Reacher Collection Books 1-5", "Complete Stephanie Plum Series",
		"Mistborn Series [Books 1-4]", "Serrano Legacy Book 1-7 Complete Series (Graphic Audio)", "Jack Reacher 01-24",
		"Discworld Collection 1-39 [M4B]", "All 7 Harry Potter Audio Books", "Lemony Snicket Complete Series (Books 1-13)",
		"Tolkien - In de Ban van de Ring Trilogie", "Barbara Taylor Bradford 9 Boeken", "luisterboek de grijze jager deel 1 tot 9",
		"Harry Potter.AUDIO.BOOK.PACK", "Stephen King Omnibus", "The Complete Sherlock Holmes Audiobook Collection",
	}
	no := []string{
		"Andy Weir - Project Hail Mary [2021] [M4B 64kbps]", "Fahrenheit 451 - Ray Bradbury", "Stephen King - 11.22.63",
		"Brandon Sanderson - Mistborn Book 2 - Well of Ascension", "Complete and Unabridged - Dune", "Jim Dale - Harry Potter and the Goblet of Fire 2021-05-06",
		"The Martian - Andy Weir - Audible Audiobook MP3 CBR 64k", "Catch-22 - Joseph Heller", "Andy Weir - Artemis 128 kbps", "Personal (Jack Reacher #19) [320kbps]",
	}
	for _, s := range yes {
		if !looksLikeCollection(s) {
			t.Errorf("%q: want collection", s)
		}
	}
	for _, s := range no {
		if looksLikeCollection(s) {
			t.Errorf("%q: want not a collection", s)
		}
	}
}

func TestNormalise(t *testing.T) {
	tests := map[string]string{
		"Harry Potter & the Philosopher's Stone": "harry potter and the philosophers stone",
		"Café Müller – Ünïcode":                  "cafe muller   unicode",
		"Die Heiligtümer des Todes":              "die heiligtumer des todes",
		"Fußball":                                "fussball",
	}
	for in, want := range tests {
		if got := strings.Join(strings.Fields(fold(in)), " "); got != strings.Join(strings.Fields(want), " ") {
			t.Errorf("fold(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSurnameInTitleIsNotAuthorProof(t *testing.T) {
	book := model.Book{Title: "The King", Authors: []string{"Stephen King"}}
	other := model.Release{Title: "The King - Jane Doe", Parsed: parse.Title("The King - Jane Doe")}
	if r := Score(book, other); r.Match {
		t.Fatalf("different author matched: %+v", r)
	}
	real := model.Release{Title: "Stephen King - The King", Parsed: parse.Title("Stephen King - The King")}
	if r := Score(book, real); !r.Match {
		t.Fatalf("real release did not match: %+v", r)
	}
}
