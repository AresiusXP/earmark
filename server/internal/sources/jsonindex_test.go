package sources

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

func TestLooksLikeNoise(t *testing.T) {
	for _, tc := range []struct {
		title string
		size  int64
		want  bool
	}{
		{"Dungeon Crawler Carl", 800 << 20, false},
		{"Dungeon Crawler Carl by Matt Dinniman EPUB", 800 << 20, true},
		{"Dungeon Crawler Carl.epub", 800 << 20, true},
		{"The Hobbit 2012 extended.mkv", 9000 << 20, true},
		{"The Hobbit - Audiobook.mp4", 300 << 20, true},
		{"Tolkien disc image.iso", 700 << 20, true},
		{"Book (mobi)", 800 << 20, true},
		{"Book [AZW3]", 800 << 20, true},
		{"Book - PDF", 800 << 20, true},
		{"Book.cbz", 800 << 20, true},
		{"Book cbr", 800 << 20, true},
		{"The Hobbit 1080p BluRay", 800 << 20, true},
		{"The Hobbit 720p", 800 << 20, true},
		{"The Hobbit.2160p.HEVC", 800 << 20, true},
		{"The Hobbit x264", 800 << 20, true},
		{"The Hobbit x265", 800 << 20, true},
		{"The Hobbit WEBRip", 800 << 20, true},
		{"The Hobbit WEB-DL", 800 << 20, true},
		{"The Hobbit web.dl", 800 << 20, true},
		{"The Hobbit HDTV", 800 << 20, true},
		{"Show s01e02", 800 << 20, true},
		{"Show S10E123 audio", 800 << 20, true},
		{"The Hobbit - J.R.R. Tolkien (Rob Inglis) [MP3]", 800 << 20, false},
		{"Mobius Dick", 800 << 20, false}, // "mobi" is only a word of its own
		{"Pdfs of the Wind", 800 << 20, false},
		{"Season 1 Episode 2 audiobook", 800 << 20, false},
		{"The Hobbit", 20<<20 - 1, true},
		{"The Hobbit", 20 << 20, false},
		{"The Hobbit", 0, false}, // unknown size is not held against it
	} {
		if got := looksLikeNoise(tc.title, tc.size); got != tc.want {
			t.Errorf("looksLikeNoise(%q, %d) = %v, want %v", tc.title, tc.size, got, tc.want)
		}
	}
}

func TestJSONIndexQuery(t *testing.T) {
	for _, tc := range []struct {
		in, want string
		ok       bool
	}{
		{"Dungeon Crawler Carl", "dungeon crawler carl", true},
		{"Carl's  “Quest”: Part-1!", "carls quest part 1", true},
		{"  The Hobbit  ", "the hobbit", true},
		{"Ik", "ik", true},
		{"A", "a", false},
		{"!!", "", false},
		{"", "", false},
	} {
		got, ok := jsonIndexQuery(tc.in)
		if got != tc.want || ok != tc.ok {
			t.Errorf("jsonIndexQuery(%q) = %q, %v; want %q, %v", tc.in, got, ok, tc.want, tc.ok)
		}
	}
}

func TestJSONIndexReleaseHash(t *testing.T) {
	good := "92E715F6B0A3C0F5A7D8E1C2B3A49586776655AA"
	r, ok := jsonIndexRelease("x", " "+good+" ", "Title", 100<<20, 4, time.Time{})
	if !ok || r.InfoHash != "92e715f6b0a3c0f5a7d8e1c2b3a49586776655aa" || r.Source != "x" || r.Seeders != 4 {
		t.Fatalf("%+v %v", r, ok)
	}
	for _, h := range []string{"", "abc", "zz" + good[2:], good + "0", "MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U"} {
		if _, ok := jsonIndexRelease("x", h, "Title", 100<<20, 1, time.Time{}); ok {
			t.Errorf("hash %q accepted", h)
		}
	}
	if _, ok := jsonIndexRelease("x", good, "  ", 100<<20, 1, time.Time{}); ok {
		t.Error("empty title accepted")
	}
}

func TestHostClientRefusesForeignRedirects(t *testing.T) {
	var foreign atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		foreign.Add(1)
	}))
	defer other.Close()
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/loop" {
			http.Redirect(w, r, "/loop", http.StatusFound)
			return
		}
		if r.URL.Path == "/own" {
			_, _ = w.Write([]byte("ok"))
			return
		}
		http.Redirect(w, r, other.URL+"/x", http.StatusFound)
	}))
	defer srv.Close()

	hc := hostClient("test", srv.URL)
	if resp, err := hc.Get(srv.URL + "/foreign"); err == nil {
		resp.Body.Close()
		t.Fatal("redirect to a foreign host was followed")
	}
	if foreign.Load() != 0 {
		t.Error("foreign host was contacted")
	}
	if resp, err := hc.Get(srv.URL + "/loop"); err == nil {
		resp.Body.Close()
		t.Fatal("redirect loop was followed")
	}
	resp, err := hc.Get(srv.URL + "/own")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
}

func TestHostClientRefusesHTTPSDowngrade(t *testing.T) {
	var srv *httptest.Server
	srv = httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/own" {
			_, _ = w.Write([]byte("ok"))
			return
		}
		http.Redirect(w, r, "http://"+r.Host+"/own", http.StatusFound)
	}))
	defer srv.Close()
	hc := hostClient("test", srv.URL)
	hc.Transport = srv.Client().Transport

	resp, err := hc.Get(srv.URL + "/start")
	if err == nil {
		resp.Body.Close()
		t.Fatal("https -> http redirect was followed")
	}
	if !strings.Contains(err.Error(), "downgrades") {
		t.Errorf("err = %v", err)
	}
	if resp, err := hc.Get(srv.URL + "/own"); err != nil {
		t.Fatal(err)
	} else {
		resp.Body.Close()
	}

	// http -> same-host https (an upgrade) and http -> http stay allowed
	check := hostClient("test", "http://a.example").CheckRedirect
	mk := func(u string) *http.Request { r, _ := http.NewRequest("GET", u, nil); return r }
	if err := check(mk("https://a.example/x"), []*http.Request{mk("http://a.example/")}); err != nil {
		t.Errorf("upgrade refused: %v", err)
	}
	if err := check(mk("http://a.example/x"), []*http.Request{mk("http://a.example/")}); err != nil {
		t.Errorf("same-scheme refused: %v", err)
	}
}
