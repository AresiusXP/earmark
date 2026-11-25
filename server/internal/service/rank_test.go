package service

import (
	"errors"
	"fmt"
	"reflect"
	"slices"
	"strings"
	"testing"

	"github.com/AresiusXP/earmark/server/internal/model"
)

func TestNaturalCompare(t *testing.T) {
	tests := []struct {
		a, b string
		want int
	}{
		{"2", "10", -1},
		{"10", "2", 1},
		{"a2.mp3", "a10.mp3", -1},
		{"Chapter 9.mp3", "chapter 10.mp3", -1},
		{"ABC", "abd", -1},
		{"abc", "ABC", 1}, // equal folded, plain order breaks the tie ('a' > 'A')
		{"01.mp3", "1.mp3", -1},
		{"1.mp3", "01.mp3", 1},
		{"x", "x", 0},
		{"x", "x1", -1},
		{"Disc 1/02.mp3", "Disc 1/10.mp3", -1},
		{"Disc 2/01.mp3", "Disc 10/01.mp3", -1},
		{"a007b", "a7c", -1},
		{"", "a", -1},
		{"99999999999999999999999.mp3", "100000000000000000000000.mp3", -1},
	}
	for _, tt := range tests {
		t.Run(tt.a+"|"+tt.b, func(t *testing.T) {
			got := naturalCompare(tt.a, tt.b)
			if (got < 0) != (tt.want < 0) || (got > 0) != (tt.want > 0) {
				t.Fatalf("naturalCompare(%q,%q) = %d, want sign %d", tt.a, tt.b, got, tt.want)
			}
			if back := naturalCompare(tt.b, tt.a); (back < 0) != (got > 0) || (back > 0) != (got < 0) {
				t.Fatalf("not antisymmetric: %d vs %d", got, back)
			}
		})
	}
}

func TestAudioFiles(t *testing.T) {
	files := []model.File{
		{Index: 0, Path: "Book/Chapter 10.mp3"},
		{Index: 1, Path: "Book/cover.jpg"},
		{Index: 2, Path: "Book/chapter 2.MP3"},
		{Index: 3, Path: "Book/Chapter 1.mp3"},
		{Index: 4, Path: "Book/info.nfo"},
		{Index: 5, Path: "Book/Chapter 01.mp3"},
	}
	got := audioFiles(files, 0)
	var paths []string
	for _, f := range got {
		paths = append(paths, f.Path)
	}
	want := []string{"Book/Chapter 01.mp3", "Book/Chapter 1.mp3", "Book/chapter 2.MP3", "Book/Chapter 10.mp3"}
	if !reflect.DeepEqual(paths, want) {
		t.Fatalf("paths = %v, want %v", paths, want)
	}
	if got := audioFiles(files, 2); len(got) != 2 {
		t.Fatalf("cap: len = %d", len(got))
	}
	if got := audioFiles(nil, 5); got == nil || len(got) != 0 {
		t.Fatalf("nil input must give empty non-nil slice, got %#v", got)
	}
	if !slices.Equal(files, []model.File{
		{Index: 0, Path: "Book/Chapter 10.mp3"}, {Index: 1, Path: "Book/cover.jpg"}, {Index: 2, Path: "Book/chapter 2.MP3"},
		{Index: 3, Path: "Book/Chapter 1.mp3"}, {Index: 4, Path: "Book/info.nfo"}, {Index: 5, Path: "Book/Chapter 01.mp3"},
	}) {
		t.Fatal("input was modified")
	}
}

func TestLargestAudio(t *testing.T) {
	tests := []struct {
		name  string
		files []model.File
		want  int
		ok    bool
	}{
		{"none", nil, 0, false},
		{"no audio", []model.File{{Index: 0, Path: "a.jpg", Size: 5}}, 0, false},
		{"largest", []model.File{{Index: 3, Path: "a.mp3", Size: 5}, {Index: 4, Path: "b.mp3", Size: 9}, {Index: 5, Path: "c.iso", Size: 99}}, 4, true},
		{"tie goes to natural order", []model.File{{Index: 7, Path: "b 10.mp3", Size: 9}, {Index: 8, Path: "b 2.mp3", Size: 9}}, 8, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			f, ok := largestAudio(tt.files)
			if ok != tt.ok || (ok && f.Index != tt.want) {
				t.Fatalf("got (%v,%v), want (%d,%v)", f.Index, ok, tt.want, tt.ok)
			}
		})
	}
}

func rel(hash, lang, format string, bitrate, seeders int) model.Release {
	return model.Release{InfoHash: hash, Seeders: seeders, Parsed: model.ParsedTitle{Language: lang, Format: format, BitrateKbps: bitrate}}
}

func sc(r model.Release, score float64, cached map[string]bool) scored {
	return scored{Candidate: Candidate{Release: r, Score: score}, Cached: cached}
}

func hashesList(in []scored) []string {
	out := []string{}
	for _, s := range in {
		out = append(out, s.Release.InfoHash)
	}
	return out
}

func TestFilterReleases(t *testing.T) {
	yes := map[string]bool{"torbox": true}
	no := map[string]bool{"torbox": false}
	items := []scored{
		sc(rel("en-cached", "en", "m4b", 0, 1), 1, yes),
		sc(rel("en-uncached", "en", "m4b", 0, 1), 1, no),
		sc(rel("en-unknown", "en", "m4b", 0, 1), 1, nil),
		sc(rel("nl-cached", "nl", "m4b", 0, 1), 1, yes),
		sc(rel("de-cached", "de", "m4b", 0, 1), 1, yes),
		sc(rel("nolang-uncached", "", "m4b", 0, 1), 1, no),
		sc(rel("EN-upper", "EN", "m4b", 0, 1), 1, yes),
	}
	tests := []struct {
		name      string
		langs     []string
		providers []string
		uncached  bool
		want      []string
	}{
		{"no debrid shows everything wanted", []string{"en"}, nil, false,
			[]string{"en-cached", "en-uncached", "en-unknown", "nolang-uncached", "EN-upper"}},
		{"torbox hides known uncached", []string{"en"}, []string{"torbox"}, false,
			[]string{"en-cached", "en-unknown", "EN-upper"}},
		{"show uncached", []string{"en"}, []string{"torbox"}, true,
			[]string{"en-cached", "en-uncached", "en-unknown", "nolang-uncached", "EN-upper"}},
		{"two languages", []string{"en", "nl"}, []string{"torbox"}, false,
			[]string{"en-cached", "en-unknown", "nl-cached", "EN-upper"}},
		{"extra provider with unknown state keeps the release", []string{"en"}, []string{"realdebrid", "torbox"}, false,
			[]string{"en-cached", "en-uncached", "en-unknown", "nolang-uncached", "EN-upper"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := hashesList(filterReleases(items, tt.langs, tt.providers, tt.uncached))
			if !reflect.DeepEqual(got, tt.want) {
				t.Fatalf("got %v, want %v", got, tt.want)
			}
		})
	}
	if got := filterReleases(nil, []string{"en"}, nil, false); got == nil {
		t.Fatal("result must not be nil")
	}
}

func TestRankReleases(t *testing.T) {
	yes := map[string]bool{"torbox": true}
	no := map[string]bool{"torbox": false}
	tests := []struct {
		name   string
		langs  []string
		prefer string
		in     []scored
		want   []string
	}{
		{"cached beats everything", []string{"en"}, "",
			[]scored{sc(rel("a", "en", "m4b", 128, 100), 0.99, no), sc(rel("b", "", "mp3", 0, 1), 0.7, yes)},
			[]string{"b", "a"}},
		{"cached at any provider", []string{"en"}, "",
			[]scored{sc(rel("a", "en", "m4b", 0, 1), 0.9, map[string]bool{"torbox": false, "rd": false}),
				sc(rel("b", "en", "m4b", 0, 1), 0.8, map[string]bool{"torbox": false, "rd": true})},
			[]string{"b", "a"}},
		{"language order then unknown", []string{"nl", "en"}, "",
			[]scored{sc(rel("unk", "", "m4b", 0, 1), 0.9, nil), sc(rel("en", "en", "m4b", 0, 1), 0.9, nil), sc(rel("nl", "nl", "mp3", 0, 1), 0.5, nil)},
			[]string{"nl", "en", "unk"}},
		{"default format m4b then mp3 then other", []string{"en"}, "",
			[]scored{sc(rel("flac", "en", "flac", 0, 1), 0.9, nil), sc(rel("mp3", "en", "mp3", 0, 1), 0.9, nil), sc(rel("m4b", "en", "m4b", 0, 1), 0.9, nil), sc(rel("none", "en", "", 0, 1), 0.9, nil)},
			[]string{"m4b", "mp3", "flac", "none"}},
		{"prefer mp3", []string{"en"}, "mp3",
			[]scored{sc(rel("m4b", "en", "m4b", 0, 1), 0.9, nil), sc(rel("mp3", "en", "mp3", 0, 1), 0.9, nil)},
			[]string{"mp3", "m4b"}},
		{"bitrate higher first, unknown last", []string{"en"}, "",
			[]scored{sc(rel("unk", "en", "m4b", 0, 1), 0.9, nil), sc(rel("64", "en", "m4b", 64, 1), 0.9, nil), sc(rel("128", "en", "m4b", 128, 1), 0.9, nil)},
			[]string{"128", "64", "unk"}},
		{"score then seeders then hash", []string{"en"}, "",
			[]scored{sc(rel("c", "en", "m4b", 64, 5), 0.8, nil), sc(rel("b", "en", "m4b", 64, 9), 0.8, nil), sc(rel("a", "en", "m4b", 64, 9), 0.8, nil), sc(rel("d", "en", "m4b", 64, 1), 0.95, nil)},
			[]string{"d", "a", "b", "c"}},
		{"language outranks format", []string{"en", "nl"}, "",
			[]scored{sc(rel("nl-m4b", "nl", "m4b", 0, 1), 0.9, nil), sc(rel("en-mp3", "en", "mp3", 0, 1), 0.9, nil)},
			[]string{"en-mp3", "nl-m4b"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			in := slices.Clone(tt.in)
			got := hashesList(rankReleases(tt.in, tt.langs, tt.prefer))
			if !reflect.DeepEqual(got, tt.want) {
				t.Fatalf("got %v, want %v", got, tt.want)
			}
			if !reflect.DeepEqual(hashesList(in), hashesList(tt.in)) {
				t.Fatal("input order modified")
			}
			// order must not depend on the input order
			rev := slices.Clone(tt.in)
			slices.Reverse(rev)
			if got2 := hashesList(rankReleases(rev, tt.langs, tt.prefer)); !reflect.DeepEqual(got2, tt.want) {
				t.Fatalf("reverse input gave %v, want %v", got2, tt.want)
			}
		})
	}
}

func TestInterleave(t *testing.T) {
	b := func(asins ...string) []model.Book {
		var out []model.Book
		for _, a := range asins {
			out = append(out, model.Book{ASIN: a})
		}
		return out
	}
	asins := func(bs []model.Book) string {
		s := ""
		for _, x := range bs {
			s += x.ASIN + " "
		}
		return s
	}
	tests := []struct {
		name  string
		lists [][]model.Book
		want  string
	}{
		{"empty", nil, ""},
		{"single", [][]model.Book{b("A", "B")}, "A B "},
		{"round robin", [][]model.Book{b("A1", "A2", "A3"), b("B1", "B2")}, "A1 B1 A2 B2 A3 "},
		{"dedupe keeps first", [][]model.Book{b("X", "A2"), b("X", "B2", "A2")}, "X A2 B2 "},
		{"empty list in the middle", [][]model.Book{b("A"), nil, b("C")}, "A C "},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := interleave(tt.lists)
			if got == nil {
				t.Fatal("nil result")
			}
			if asins(got) != tt.want {
				t.Fatalf("got %q, want %q", asins(got), tt.want)
			}
		})
	}
}

func TestSearchTitle(t *testing.T) {
	tests := map[string]string{
		"Dune":                              "Dune",
		"Dune: Book One":                    "Dune",
		"Dune (Unabridged)":                 "Dune",
		"The Name of the Wind [Unabridged]": "The Name of the Wind",
		"  Dune  : x":                       "Dune",
		"(Untitled)":                        "(Untitled)",
		":weird":                            ":weird",
		"":                                  "",
	}
	for in, want := range tests {
		t.Run(fmt.Sprintf("%q", in), func(t *testing.T) {
			if got := searchTitle(in); got != want {
				t.Fatalf("got %q, want %q", got, want)
			}
		})
	}
}

func TestRedact(t *testing.T) {
	sentinel := errors.New("sentinel")
	leaky := fmt.Errorf("%w: boom for key sekret-123", sentinel)
	got := redact(leaky, "sekret-123")
	if strings.Contains(got.Error(), "sekret-123") || !strings.Contains(got.Error(), "[redacted]") {
		t.Fatalf("got %q", got)
	}
	if !errors.Is(got, sentinel) {
		t.Fatal("errors.Is must still work")
	}
	if errors.Unwrap(got) != nil {
		t.Fatal("the original (leaking) error must not be reachable by unwrapping")
	}
	if redact(leaky, "") != leaky || redact(leaky, "other") != leaky || redact(nil, "k") != nil {
		t.Fatal("must return err unchanged without a match")
	}
}
