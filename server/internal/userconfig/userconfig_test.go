package userconfig

import (
	"errors"
	"reflect"
	"strings"
	"testing"
)

const secret = "0123456789abcdef0123456789abcdef"

func TestRoundTrip(t *testing.T) {
	s, err := NewSealer(secret)
	if err != nil {
		t.Fatal(err)
	}
	in := Config{
		Debrid: map[string]string{"torbox": "tb-secret-key", "realdebrid": ""},
		Region: "uk", Languages: []string{"en", "nl"}, PreferFormat: "mp3", ShowUncached: true, MaxReleases: 3,
	}
	tok, err := s.Seal(in)
	if err != nil {
		t.Fatal(err)
	}
	if strings.Contains(tok, "tb-secret") {
		t.Fatal("token contains the key in clear")
	}
	out, err := s.Open(tok)
	if err != nil {
		t.Fatal(err)
	}
	want := in
	want.Debrid = map[string]string{"torbox": "tb-secret-key"}
	if !reflect.DeepEqual(out, want) {
		t.Fatalf("got %+v want %+v", out, want)
	}
	tok2, _ := s.Seal(in)
	if tok == tok2 {
		t.Fatal("nonce must be random")
	}
}

func TestOpenRejects(t *testing.T) {
	s, _ := NewSealer(secret)
	other, _ := NewSealer(strings.Repeat("x", 40))
	good, _ := s.Seal(Default())
	flipped := []byte(good)
	flipped[len(flipped)-3] ^= 1
	tooBig := strings.Repeat("A", maxSealedSize*2+1)
	for name, tok := range map[string]string{
		"empty": "", "garbage": "!!!", "short": "AAAA", "wrong secret": mustSeal(t, other),
		"tampered": string(flipped), "too big": tooBig,
	} {
		if _, err := s.Open(tok); !errors.Is(err, ErrInvalid) {
			t.Errorf("%s: err = %v, want ErrInvalid", name, err)
		}
	}
}

func mustSeal(t *testing.T, s *Sealer) string {
	t.Helper()
	tok, err := s.Seal(Default())
	if err != nil {
		t.Fatal(err)
	}
	return tok
}

func TestNormalize(t *testing.T) {
	d := Default()
	if d.Region != "us" || !reflect.DeepEqual(d.Languages, []string{"en"}) || d.MaxReleases != 5 {
		t.Fatalf("defaults: %+v", d)
	}
	for name, c := range map[string]Config{
		"region": {Region: "zz"}, "lang": {Languages: []string{"fr"}}, "format": {PreferFormat: "flac"},
	} {
		if _, err := c.Normalize(); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
	for name, langs := range map[string][]string{
		"spanish": {"es"}, "all three": {"en", "nl", "es"}, "spanish first": {"es", "en"},
	} {
		c, err := (Config{Languages: langs}).Normalize()
		if err != nil || !reflect.DeepEqual(c.Languages, langs) {
			t.Errorf("%s: languages %v, err %v", name, c.Languages, err)
		}
	}
	for name, langs := range map[string][]string{"portuguese": {"es", "pt"}, "upper case": {"ES"}} {
		if _, err := (Config{Languages: langs}).Normalize(); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
	if c, _ := (Config{MaxReleases: 99}).Normalize(); c.MaxReleases != 20 {
		t.Errorf("max releases clamp: %d", c.MaxReleases)
	}
	if _, err := NewSealer("short"); err == nil {
		t.Error("short secret accepted")
	}
}
