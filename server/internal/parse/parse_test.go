package parse

import (
	"fmt"
	"reflect"
	"strconv"
	"testing"

	"github.com/AresiusXP/earmark/server/internal/model"
)

var (
	yes = true
	no  = false
)

// want is a compact expectation; zero values mean "unknown".
type want struct {
	authors   []string
	title     string
	narrator  string
	year      int
	format    string
	bitrate   int
	lang      string
	unabr     *bool
	series    string
	seriesPos string
}

type goldenCase struct {
	raw string
	want
}

// Names are taken from (or modelled on) real Knaben audiobook releases; expectations are
// hand-written, not snapshots.
var golden = []goldenCase{
	// --- English: "Author - Title" family ---
	{"Andy Weir - Project Hail Mary (Ray Porter) [2021] [M4B 64kbps]",
		want{authors: []string{"Andy Weir"}, title: "Project Hail Mary", narrator: "Ray Porter", year: 2021, format: "m4b", bitrate: 64}},
	{"Andy.Weir.-.Project.Hail.Mary.2021.Audiobook.M4B",
		want{authors: []string{"Andy Weir"}, title: "Project Hail Mary", year: 2021, format: "m4b"}},
	{"The Martian - Andy Weir [MP3]",
		want{authors: []string{"Andy Weir"}, title: "The Martian", format: "mp3"}},
	{"Andy Weir - Artemis (2017) [Unabridged] [MP3 128 kbit]",
		want{authors: []string{"Andy Weir"}, title: "Artemis", year: 2017, format: "mp3", bitrate: 128, unabr: &yes}},
	{"The Martian by Andy Weir 2013 [160kbps]",
		want{authors: []string{"Andy Weir"}, title: "The Martian", year: 2013, bitrate: 160}},
	{"Andy Weir - 2020 - The Martian (Sci-Fi)",
		want{authors: []string{"Andy Weir"}, title: "The Martian", year: 2020}},
	{"The Martian - Andy Weir - Audible Audiobook MP3 CBR 64k",
		want{authors: []string{"Andy Weir"}, title: "The Martian", format: "mp3", bitrate: 64}},
	{"Brandon Sanderson - Mistborn Book 1 - The Final Empire",
		want{authors: []string{"Brandon Sanderson"}, title: "The Final Empire", series: "Mistborn", seriesPos: "1"}},
	{"Brandon Sanderson - The Well of Ascension (Mistborn #2) [GraphicAudio]",
		want{authors: []string{"Brandon Sanderson"}, title: "The Well of Ascension", series: "Mistborn", seriesPos: "2"}},
	{"Brandon Sanderson - Mistborn 02 - The Well of Ascension - read by Michael Kramer",
		want{authors: []string{"Brandon Sanderson"}, title: "The Well of Ascension", narrator: "Michael Kramer", series: "Mistborn", seriesPos: "2"}},
	{"Brandon Sanderson - Steelheart (Reckoners #1)",
		want{authors: []string{"Brandon Sanderson"}, title: "Steelheart", series: "Reckoners", seriesPos: "1"}},
	{"Brandon Sanderson, Janci Patterson - ReDawn (Skyward #2.2)",
		want{authors: []string{"Brandon Sanderson", "Janci Patterson"}, title: "ReDawn", series: "Skyward", seriesPos: "2.2"}},
	{"Brandon Sanderson-Rhythm of War-Graphic Audio.mp3",
		want{authors: []string{"Brandon Sanderson"}, title: "Rhythm of War", format: "mp3"}},
	{"Brandon_Sanderson_-_The_Way_of_Kings_[M4B]",
		want{authors: []string{"Brandon Sanderson"}, title: "The Way of Kings", format: "m4b"}},
	{"Brandon Sanderson  -  Droga Królów [PL] MP3@128",
		want{authors: []string{"Brandon Sanderson"}, title: "Droga Królów", format: "mp3", bitrate: 128, lang: "pl"}},
	{"Stephen King - The Gunslinger (Dark Tower #1) {Unabridged} [M4B]",
		want{authors: []string{"Stephen King"}, title: "The Gunslinger", format: "m4b", unabr: &yes, series: "Dark Tower", seriesPos: "1"}},
	{"Stephen King - 11.22.63 - narrated by Craig Wasson [MP3 64k]",
		want{authors: []string{"Stephen King"}, title: "11.22.63", narrator: "Craig Wasson", format: "mp3", bitrate: 64}},
	{"Stephen King-The Regulators",
		want{authors: []string{"Stephen King"}, title: "The Regulators"}},
	{"Stephen King- Gramma.mp3",
		want{authors: []string{"Stephen King"}, title: "Gramma", format: "mp3"}},
	{"Stephen King - Vägbygge 1988",
		want{authors: []string{"Stephen King"}, title: "Vägbygge", year: 1988}},
	{"Stephen King - Lisey\\'s Story",
		want{authors: []string{"Stephen King"}, title: "Lisey's Story"}},
	{"Stephen.King.-.It.Unabridged.Audiobook.MP3.64kbps",
		want{authors: []string{"Stephen King"}, title: "It", format: "mp3", bitrate: 64, unabr: &yes}},
	{"Carrie - Stephen King [Unabridged]",
		want{authors: []string{"Stephen King"}, title: "Carrie", unabr: &yes}},
	{"Dracula - Bram Stoker  - Unabridged",
		want{authors: []string{"Bram Stoker"}, title: "Dracula", unabr: &yes}},
	{"Lee Child - Never Go Back (Jack Reacher #18)",
		want{authors: []string{"Lee Child"}, title: "Never Go Back", series: "Jack Reacher", seriesPos: "18"}},
	{"Personal: A Jack Reacher Novel, Book 19 - Lee Child - Audiobook",
		want{authors: []string{"Lee Child"}, title: "Personal: A Jack Reacher Novel", seriesPos: "19"}},
	{"Never Go Back: A Jack Reacher Novel By Lee Child (Abee)",
		want{authors: []string{"Lee Child"}, title: "Never Go Back: A Jack Reacher Novel"}},
	{"The Secret: Jack Reacher, 28 - Lee Child, Andrew Child - 2023 (miok) [Audiobook]",
		want{authors: []string{"Lee Child", "Andrew Child"}, title: "The Secret: Jack Reacher, 28", year: 2023}},
	{"Neil Gaiman - American Gods (Unabridged) (narrated by George Guidall) 2011 mp3",
		want{authors: []string{"Neil Gaiman"}, title: "American Gods", narrator: "George Guidall", year: 2011, format: "mp3", unabr: &yes}},
	{"Douglas Adams - The Hitchhiker's Guide to the Galaxy (Stephen Fry) [1990] [128kbps MP3]",
		want{authors: []string{"Douglas Adams"}, title: "The Hitchhiker's Guide to the Galaxy", narrator: "Stephen Fry", year: 1990, format: "mp3", bitrate: 128}},
	{"Frank Herbert - Dune (2007) - Simon Vance, Scott Brick, Orlagh Cassidy [M4B]",
		want{authors: []string{"Frank Herbert"}, title: "Dune", narrator: "Simon Vance, Scott Brick, Orlagh Cassidy", year: 2007, format: "m4b"}},
	{"J.K. Rowling - Harry Potter and the Philosopher's Stone (Stephen Fry) [M4B]",
		want{authors: []string{"J.K. Rowling"}, title: "Harry Potter and the Philosopher's Stone", narrator: "Stephen Fry", format: "m4b"}},
	{"Harry Potter and the Chamber of Secrets - J.K. Rowling - Stephen Fry [Unabridged] [MP3]",
		want{authors: []string{"J.K. Rowling"}, title: "Harry Potter and the Chamber of Secrets", narrator: "Stephen Fry", format: "mp3", unabr: &yes}},
	{"Ernest Cline - Ready Player One [2011] [Wil Wheaton] [MP3 64kbps]",
		want{authors: []string{"Ernest Cline"}, title: "Ready Player One", narrator: "Wil Wheaton", year: 2011, format: "mp3", bitrate: 64}},
	{"Michael Crichton - Jurassic Park (1990) read by Scott Brick M4B",
		want{authors: []string{"Michael Crichton"}, title: "Jurassic Park", narrator: "Scott Brick", year: 1990, format: "m4b"}},
	{"George R. R. Martin - A Game of Thrones (A Song of Ice and Fire, Book 1) [Unabridged] [64kbps]",
		want{authors: []string{"George R. R. Martin"}, title: "A Game of Thrones", bitrate: 64, unabr: &yes, series: "A Song of Ice and Fire", seriesPos: "1"}},
	{"Agatha Christie - Murder on the Orient Express (Hercule Poirot, Book 10) - David Suchet [M4B]",
		want{authors: []string{"Agatha Christie"}, title: "Murder on the Orient Express", narrator: "David Suchet", format: "m4b", series: "Hercule Poirot", seriesPos: "10"}},
	{"Robert Jordan - The Eye of the World - Wheel of Time 01 [Michael Kramer & Kate Reading]",
		want{authors: []string{"Robert Jordan"}, title: "The Eye of the World", narrator: "Michael Kramer & Kate Reading", series: "Wheel of Time", seriesPos: "1"}},
	{"Terry Pratchett - Discworld 01 - The Colour of Magic - read by Nigel Planer [MP3 128kbps]",
		want{authors: []string{"Terry Pratchett"}, title: "The Colour of Magic", narrator: "Nigel Planer", format: "mp3", bitrate: 128, series: "Discworld", seriesPos: "1"}},
	{"Terry Pratchett & Neil Gaiman - Good Omens [M4B]",
		want{authors: []string{"Terry Pratchett", "Neil Gaiman"}, title: "Good Omens", format: "m4b"}},
	{"Douglas Preston, Lincoln Child - Relic (Pendergast #1) [MP3]",
		want{authors: []string{"Douglas Preston", "Lincoln Child"}, title: "Relic", format: "mp3", series: "Pendergast", seriesPos: "1"}},
	{"James S. A. Corey - Leviathan Wakes (The Expanse #1) - Jefferson Mays [M4B 64kbps]",
		want{authors: []string{"James S. A. Corey"}, title: "Leviathan Wakes", narrator: "Jefferson Mays", format: "m4b", bitrate: 64, series: "The Expanse", seriesPos: "1"}},
	{"Patrick Rothfuss - The Name of the Wind (Kingkiller Chronicle #1) (Nick Podehl) [2007] [FLAC]",
		want{authors: []string{"Patrick Rothfuss"}, title: "The Name of the Wind", narrator: "Nick Podehl", year: 2007, format: "flac", series: "Kingkiller Chronicle", seriesPos: "1"}},
	{"Joe Abercrombie - The Blade Itself (The First Law #1) - Steven Pacey [Abridged]",
		want{authors: []string{"Joe Abercrombie"}, title: "The Blade Itself", narrator: "Steven Pacey", unabr: &no, series: "The First Law", seriesPos: "1"}},
	{"Ray Bradbury - Fahrenheit 451 (2005) [Tim Robbins] 64kbps mp3",
		want{authors: []string{"Ray Bradbury"}, title: "Fahrenheit 451", narrator: "Tim Robbins", year: 2005, format: "mp3", bitrate: 64}},
	{"Matt Haig - The Midnight Library [Audiobook] [Carey Mulligan] [2020] [M4B]",
		want{authors: []string{"Matt Haig"}, title: "The Midnight Library", narrator: "Carey Mulligan", year: 2020, format: "m4b"}},
	{"David Baldacci - The Innocent - unabridged 64 kbps",
		want{authors: []string{"David Baldacci"}, title: "The Innocent", bitrate: 64, unabr: &yes}},
	{"Sebastian Faulks - Birdsong (Unabridged)",
		want{authors: []string{"Sebastian Faulks"}, title: "Birdsong", unabr: &yes}},
	{"Ann Patchett - 2019 - The Dutch House (Fiction)",
		want{authors: []string{"Ann Patchett"}, title: "The Dutch House", year: 2019}},

	// --- English: "Title by Author", narrator phrases, no author ---
	{"Artemis by Andy Weir",
		want{authors: []string{"Andy Weir"}, title: "Artemis"}},
	{"Oliver Twist by Charles Dickens 2006 Narrated by: Martin Jarvis",
		want{authors: []string{"Charles Dickens"}, title: "Oliver Twist", narrator: "Martin Jarvis", year: 2006}},
	{"Wuthering Heights by Emily Bronte (Narrated by Joanne Froggatt)",
		want{authors: []string{"Emily Bronte"}, title: "Wuthering Heights", narrator: "Joanne Froggatt"}},
	{"Calling Death Written by Jonathan Maberry Narrated by Tom Weiner [Audiobook m4b][Audible Version][Uploaded by ni6hant]",
		want{authors: []string{"Jonathan Maberry"}, title: "Calling Death", narrator: "Tom Weiner", format: "m4b"}},
	{"Harrowgate by Kate Maruyama M4B",
		want{authors: []string{"Kate Maruyama"}, title: "Harrowgate", format: "m4b"}},
	{"Fahrenheit 451 - Narrated by Christopher Hurt",
		want{title: "Fahrenheit 451", narrator: "Christopher Hurt"}},
	{"The Mark of Zorro  (Narrated by Val Kilmer)",
		want{title: "The Mark of Zorro", narrator: "Val Kilmer"}},
	{"The Ramayana of Valmiki [Narrated by Sagar Arya] 2019 M4B",
		want{title: "The Ramayana of Valmiki", narrator: "Sagar Arya", year: 2019, format: "m4b"}},
	{"Harry Potter 1-7 Unabridged Audiobooks Narrated By Stephen Fry ( ...",
		want{title: "Harry Potter 1-7", narrator: "Stephen Fry", unabr: &yes}},
	{"My Husband's Wife [B0F6FJCXM3].m4b",
		want{title: "My Husband's Wife", format: "m4b"}},
	{"Jurassic Park - Unabridged",
		want{title: "Jurassic Park", unabr: &yes}},
	{"Property (Unabridged).m4b",
		want{title: "Property", format: "m4b", unabr: &yes}},
	{"The Picture of Dorian Gray -- Narrated by Stephen Fry [ABRIDGED] ...",
		want{title: "The Picture of Dorian Gray", narrator: "Stephen Fry", unabr: &no}},

	// --- Collections: parsed like any other name, rejection is match's job ---
	{"Fablehaven - Brandon Mull   (Complete Series)",
		want{authors: []string{"Brandon Mull"}, title: "Fablehaven"}},
	{"Janet Evanovich - Complete Stephanie Plum Series (MP3 Format)",
		want{authors: []string{"Janet Evanovich"}, title: "Complete Stephanie Plum Series", format: "mp3"}},
	{"Jim Butcher - Codex Alera - Complete Series [.m4b]",
		want{authors: []string{"Jim Butcher"}, title: "Codex Alera", format: "m4b"}},
	{"Lee Child Jack Reacher Collection Books 1-5",
		want{title: "Lee Child Jack Reacher Collection Books 1-5"}},

	// --- Dutch ---
	{"Harry Potter En De Geheime Kamer (voorgelezen door Jan Meng)",
		want{title: "Harry Potter En De Geheime Kamer", narrator: "Jan Meng", lang: "nl"}},
	{"J.K. Rowling - Harry Potter en de Steen der Wijzen - gelezen door Fred Butter [NL] [MP3]",
		want{authors: []string{"J.K. Rowling"}, title: "Harry Potter en de Steen der Wijzen", narrator: "Fred Butter", format: "mp3", lang: "nl"}},
	{"Luisterboek - Karin Slaughter - Verbroken - NL - By Pixarr",
		want{authors: []string{"Karin Slaughter"}, title: "Verbroken", lang: "nl"}},
	{"Cecilia Ahern - Voor jou (Luisterboek)NLT-Release",
		want{authors: []string{"Cecilia Ahern"}, title: "Voor jou", lang: "nl"}},
	{"De Hobbit - J.R.R. Tolkien luisterboek audioboek",
		want{authors: []string{"J.R.R. Tolkien"}, title: "De Hobbit", lang: "nl"}},
	{"Tess Gerritsen - De chirurg (Audioboek)(Dutch) 2Lions-Team",
		want{authors: []string{"Tess Gerritsen"}, title: "De chirurg", lang: "nl"}},
	{"Audioboek Tess Gerritsen : Hartslag (Dutch) 2Lions-Team",
		want{authors: []string{"Tess Gerritsen"}, title: "Hartslag", lang: "nl"}},
	{"Esther Verhoef - Close-up (gelezen door Sanne Vogel) [64kbps MP3]",
		want{authors: []string{"Esther Verhoef"}, title: "Close-up", narrator: "Sanne Vogel", format: "mp3", bitrate: 64, lang: "nl"}},
	{"Thea Beckman - Kruistocht in Spijkerbroek [Luisterboek] [MP3]",
		want{authors: []string{"Thea Beckman"}, title: "Kruistocht in Spijkerbroek", format: "mp3", lang: "nl"}},
	{"Harry Mulisch - De Aanslag - Luisterboek - voorgelezen door Pierre Bokma",
		want{authors: []string{"Harry Mulisch"}, title: "De Aanslag", narrator: "Pierre Bokma", lang: "nl"}},
	{"Dan Brown - Da Vinci Code cd1-6 Nederlands",
		want{authors: []string{"Dan Brown"}, title: "Da Vinci Code", lang: "nl"}},
	{"Jip en Janneke - Luisterboek - NL",
		want{title: "Jip en Janneke", lang: "nl"}},
	{"Etorrentz - Harry Mulisch - Twee vrouwen (luisterboek)",
		want{authors: []string{"Harry Mulisch"}, title: "Twee vrouwen", lang: "nl"}},
	{"Stephen King - Het (Nederlandstalig) [Luisterboek] 2019 M4B",
		want{authors: []string{"Stephen King"}, title: "Het", year: 2019, format: "m4b", lang: "nl"}},
	{"Jan Terlouw - Oorlogswinter - gesproken door Rik Hoogendoorn - ingekort",
		want{authors: []string{"Jan Terlouw"}, title: "Oorlogswinter", narrator: "Rik Hoogendoorn", lang: "nl", unabr: &no}},
	{"Jan Terlouw - Oorlogswinter - onverkort [MP3 64kbps]",
		want{authors: []string{"Jan Terlouw"}, title: "Oorlogswinter", format: "mp3", bitrate: 64, lang: "nl", unabr: &yes}},
	{"Karin Slaughter - Nachtschade (Audioboek)(Dutch) 2Lions-Team",
		want{authors: []string{"Karin Slaughter"}, title: "Nachtschade", lang: "nl"}},

	// --- German / other languages ---
	{"[Hörbuch] Harry Potter und der Halbblutprinz",
		want{title: "Harry Potter und der Halbblutprinz", lang: "de"}},
	{"Andy Weir - Der Marsianer - ungekürzt - Hörbuch [MP3]",
		want{authors: []string{"Andy Weir"}, title: "Der Marsianer", format: "mp3", lang: "de", unabr: &yes}},
	{"George RR Martin - 01 Die Herren von Winterfell (Hörbuch)",
		want{authors: []string{"George RR Martin"}, title: "Die Herren von Winterfell", lang: "de", seriesPos: "1"}},
	{"Ken Follett - Sturz der Titanen [Hörbuch] [12 CDs] [OGG]",
		want{authors: []string{"Ken Follett"}, title: "Sturz der Titanen", format: "ogg", lang: "de"}},
	{"Andy Weir - Ensam P&aring; Mars -Svensk",
		want{authors: []string{"Andy Weir"}, title: "Ensam På Mars", lang: "sv"}},
	{"Andy Weir - The Martian [English] [M4B]",
		want{authors: []string{"Andy Weir"}, title: "The Martian", format: "m4b", lang: "en"}},
	{"Andy Weir - Project Hail Mary (ENG) [M4B]",
		want{authors: []string{"Andy Weir"}, title: "Project Hail Mary", format: "m4b", lang: "en"}},
}

func TestTitleGolden(t *testing.T) {
	if len(golden) < 60 {
		t.Fatalf("golden set too small: %d", len(golden))
	}
	for _, tc := range golden {
		t.Run(tc.raw, func(t *testing.T) {
			got := Title(tc.raw)
			exp := model.ParsedTitle{
				Authors: tc.authors, Title: tc.title, Narrator: tc.narrator, Year: tc.year,
				Format: tc.format, BitrateKbps: tc.bitrate, Language: tc.lang, Unabridged: tc.unabr,
				Series: tc.series, SeriesPosition: tc.seriesPos,
			}
			if len(got.Authors) == 0 {
				got.Authors = nil
			}
			if !reflect.DeepEqual(got, exp) {
				t.Errorf("Title(%q)\n got: %s\nwant: %s", tc.raw, show(got), show(exp))
			}
		})
	}
}

func show(p model.ParsedTitle) string {
	u := "nil"
	if p.Unabridged != nil {
		u = strconv.FormatBool(*p.Unabridged)
	}
	return fmt.Sprintf("authors=%q title=%q narrator=%q year=%d format=%q kbps=%d lang=%q unabridged=%s series=%q pos=%q",
		p.Authors, p.Title, p.Narrator, p.Year, p.Format, p.BitrateKbps, p.Language, u, p.Series, p.SeriesPosition)
}

func TestTitleDegenerate(t *testing.T) {
	for _, raw := range []string{"", "   ", " - ", "()", "[M4B]", "...", "\\'", "Hörbuch", "2021", "- - -"} {
		got := Title(raw)
		if raw == "" && !reflect.DeepEqual(got, model.ParsedTitle{}) {
			t.Errorf("Title(%q) = %s, want zero value", raw, show(got))
		}
	}
	if got := Title("1984 - George Orwell"); got.Title != "1984" || got.Year != 0 {
		t.Errorf("a year-like title must stay a title: %s", show(got))
	}
}

func TestLooksLikePerson(t *testing.T) {
	yes := []string{"Andy Weir", "J.K. Rowling", "J. K. Rowling", "George R. R. Martin", "Ursula Le Guin", "Frank van Dijk",
		"Lee Child, Andrew Child", "Terry Pratchett & Neil Gaiman", "Brandon Sanderson and Robert Jordan"}
	no := []string{"", "Dune", "The Martian", "Project Hail Mary Series", "Harry Potter and the Chamber of Secrets", "De Hobbit",
		"Fahrenheit 451", "Mistborn Complete Collection", "Walt disney"}
	for _, s := range yes {
		if !looksLikePerson(s) {
			t.Errorf("%q should look like a person", s)
		}
	}
	for _, s := range no {
		if looksLikePerson(s) {
			t.Errorf("%q should not look like a person", s)
		}
	}
}
