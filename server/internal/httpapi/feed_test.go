package httpapi

import (
	"encoding/xml"
	"errors"
	"fmt"
	"html"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/AresiusXP/earmark/server/internal/model"
	"github.com/AresiusXP/earmark/server/internal/service"
	"github.com/AresiusXP/earmark/server/internal/userconfig"
)

const (
	itunesNS = "http://www.itunes.com/dtds/podcast-1.0.dtd"
	atomNS   = "http://www.w3.org/2005/Atom"
)

type parsedFeed struct {
	XMLName xml.Name `xml:"rss"`
	Version string   `xml:"version,attr"`
	Channel struct {
		Title       string `xml:"title"`
		Description string `xml:"description"`
		Language    string `xml:"language"`
		Generator   string `xml:"generator"`
		Author      string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd author"`
		Explicit    string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd explicit"`
		Type        string `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd type"`
		Image       struct {
			Href string `xml:"href,attr"`
		} `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd image"`
		Self struct {
			Href string `xml:"href,attr"`
			Rel  string `xml:"rel,attr"`
		} `xml:"http://www.w3.org/2005/Atom link"`
		Link  string `xml:"link"`
		Items []struct {
			Title string `xml:"title"`
			GUID  struct {
				IsPermaLink string `xml:"isPermaLink,attr"`
				Value       string `xml:",chardata"`
			} `xml:"guid"`
			PubDate   string `xml:"pubDate"`
			Enclosure struct {
				URL    string `xml:"url,attr"`
				Length int64  `xml:"length,attr"`
				Type   string `xml:"type,attr"`
			} `xml:"enclosure"`
			Episode int `xml:"http://www.itunes.com/dtds/podcast-1.0.dtd episode"`
		} `xml:"item"`
	} `xml:"channel"`
}

func parseFeed(t *testing.T, rec *httptest.ResponseRecorder) parsedFeed {
	t.Helper()
	var f parsedFeed
	if err := xml.Unmarshal(rec.Body.Bytes(), &f); err != nil {
		t.Fatalf("feed is not valid XML: %v\n%s", err, rec.Body.String())
	}
	return f
}

const feedASIN = "B002V0QK4C"

func feedBookPage() *service.BookPage {
	h1, h2, h3, h4 := strings.Repeat("a", 40), strings.Repeat("b", 40), strings.Repeat("c", 40), strings.Repeat("d", 40)
	return &service.BookPage{Book: sampleBook(), Releases: []service.ReleaseView{
		{Season: 1, Release: model.Release{InfoHash: h1, Title: "Dune MP3", Parsed: model.ParsedTitle{Format: "mp3"}},
			Cached: map[string]bool{"torbox": true},
			Files: []model.File{
				{Index: 3, Path: "Dune/01 - Chapter 1 & 2 <x>.mp3", Size: 1000},
				{Index: 4, Path: "Dune\\02_Second part.MP3", Size: 2000},
				{Index: 9, Path: "Dune/cover.jpg", Size: 5},
				{Index: 7, Path: "Dune/1984.flac"},
			}},
		{Season: 2, Release: model.Release{InfoHash: h2, Title: "Dune M4B", Parsed: model.ParsedTitle{Format: "m4b"}},
			Files: []model.File{{Index: 0, Path: "Dune.m4b", Size: 123456}}},
		{Season: 3, Release: model.Release{InfoHash: h3, Title: "Dune unknown", Parsed: model.ParsedTitle{Format: "m4b"}}},
		{Season: 4, Release: model.Release{InfoHash: h4, Title: "Dune Chronicles 1-6"}, Collection: true,
			Files: []model.File{{Index: 11, Path: "Pack/Book 1 - Dune/01.m4a", Size: 10}}},
	}}
}

func feedEnv(t *testing.T) (*env, string) {
	e := newEnv(t, "")
	e.api.book = func(_ userconfig.Config, asin string) (*service.BookPage, error) {
		if asin != feedASIN {
			return nil, service.ErrNotFound
		}
		return feedBookPage(), nil
	}
	return e, e.defaultToken(t)
}

func TestFeedDefault(t *testing.T) {
	e, tok := feedEnv(t)
	h1 := strings.Repeat("a", 40)
	rec := e.get("/" + tok + "/feed/" + feedASIN + ".rss")
	mustStatus(t, rec, 200)
	for k, want := range map[string]string{
		"Content-Type":                "application/rss+xml; charset=utf-8",
		"Cache-Control":               "no-store",
		"Access-Control-Allow-Origin": "*",
		"Referrer-Policy":             "no-referrer",
	} {
		if got := rec.Header().Get(k); got != want {
			t.Errorf("%s = %q, want %q", k, got, want)
		}
	}
	body := rec.Body.String()
	if !strings.HasPrefix(body, "<?xml") {
		t.Errorf("no XML declaration: %.40s", body)
	}
	for _, ns := range []string{`xmlns:itunes="` + itunesNS + `"`, `xmlns:content="http://purl.org/rss/1.0/modules/content/"`, `xmlns:atom="` + atomNS + `"`} {
		if !strings.Contains(body, ns) {
			t.Errorf("missing %s", ns)
		}
	}
	f := parseFeed(t, rec)
	c := f.Channel
	if f.Version != "2.0" || c.Title != "Dune" || c.Author != "Frank Herbert" || c.Language != "en" ||
		c.Explicit != "false" || c.Type != "serial" || c.Generator == "" || c.Image.Href != "https://img.example/dune.jpg" ||
		!strings.Contains(c.Description, "Desert planet.") || !strings.Contains(c.Description, "Scott Brick") {
		t.Errorf("channel = %+v", c)
	}
	if c.Self.Rel != "self" || c.Self.Href != testPublic+"/"+tok+"/feed/"+feedASIN+".rss" {
		t.Errorf("self link = %+v", c.Self)
	}
	if c.Link != testPublic+"/"+tok+"/listen/"+feedASIN {
		t.Errorf("channel link = %q", c.Link)
	}

	type want struct {
		title, guid, name, typ string
		length                 int64
	}
	wants := []want{
		{"Chapter 1 & 2 <x>", "abk:" + feedASIN + ":" + h1 + ":3", "01 - Chapter 1 & 2 <x>.mp3", "audio/mpeg", 1000},
		{"Second part", "abk:" + feedASIN + ":" + h1 + ":4", "02_Second part.MP3", "audio/mpeg", 2000},
		{"1984", "abk:" + feedASIN + ":" + h1 + ":7", "1984.flac", "audio/flac", 0},
	}
	if len(c.Items) != len(wants) {
		t.Fatalf("items = %d, want %d (cover.jpg must be skipped)", len(c.Items), len(wants))
	}
	var prev time.Time
	for i, w := range wants {
		it := c.Items[i]
		if it.Title != w.title || it.GUID.Value != w.guid || it.GUID.IsPermaLink != "false" ||
			it.Enclosure.Type != w.typ || it.Enclosure.Length != w.length || it.Episode != i+1 {
			t.Errorf("item %d = %+v, want %+v", i, it, w)
		}
		wantURL := testPublic + "/resolve/" + tok + "/torbox/" + h1 + "/" + fmt.Sprint(strings.Split(w.guid, ":")[3]) + "/" + url.PathEscape(w.name)
		if it.Enclosure.URL != wantURL {
			t.Errorf("enclosure %d = %q, want %q", i, it.Enclosure.URL, wantURL)
		}
		pd, err := time.Parse(time.RFC1123Z, it.PubDate)
		if err != nil {
			t.Fatalf("pubDate %q: %v", it.PubDate, err)
		}
		if i > 0 && !pd.After(prev) {
			t.Errorf("pubDate not increasing: %v then %v", prev, pd)
		}
		prev = pd
	}
	// the odd file name is escaped in the XML text and the URL path
	if strings.Contains(body, "<x>") || strings.Contains(body, "& 2") {
		t.Errorf("unescaped markup in body:\n%s", body)
	}
	if strings.Contains(c.Items[0].Enclosure.URL, "<") || strings.Contains(c.Items[0].Enclosure.URL, " ") {
		t.Errorf("enclosure URL not escaped: %q", c.Items[0].Enclosure.URL)
	}
	// the key never appears; only the sealed token does
	if strings.Contains(body, testKey) {
		t.Error("debrid key leaked into the feed")
	}
	if strings.Contains(e.logs.String(), tok) || strings.Contains(e.logs.String(), feedASIN) {
		t.Errorf("request path logged: %s", e.logs.String())
	}
}

func TestFeedStableAcrossCalls(t *testing.T) {
	e, tok := feedEnv(t)
	a := e.get("/" + tok + "/feed/" + feedASIN + ".rss").Body.String()
	b := e.get("/" + tok + "/feed/" + feedASIN + ".rss").Body.String()
	if a != b {
		t.Error("two calls give different feeds (guid/pubDate must be deterministic)")
	}
}

func TestFeedRefs(t *testing.T) {
	e, tok := feedEnv(t)
	h2, h3, h4 := strings.Repeat("b", 40), strings.Repeat("c", 40), strings.Repeat("d", 40)
	get := func(ref string) *httptest.ResponseRecorder {
		return e.get("/" + tok + "/feed/" + feedASIN + "/" + ref + ".rss")
	}

	t.Run("season 1 equals the default apart from the title suffix", func(t *testing.T) {
		f := parseFeed(t, get("1"))
		if f.Channel.Title != "Dune [MP3]" || len(f.Channel.Items) != 3 {
			t.Errorf("channel = %q, %d items", f.Channel.Title, len(f.Channel.Items))
		}
	})
	t.Run("by hash", func(t *testing.T) {
		f := parseFeed(t, get(h2))
		if f.Channel.Title != "Dune [M4B]" || len(f.Channel.Items) != 1 {
			t.Fatalf("channel = %q, %d items", f.Channel.Title, len(f.Channel.Items))
		}
		it := f.Channel.Items[0]
		if it.Enclosure.Type != "audio/mp4" || it.Enclosure.Length != 123456 || it.GUID.Value != "abk:"+feedASIN+":"+h2+":0" {
			t.Errorf("item = %+v", it)
		}
		if f.Channel.Self.Href != testPublic+"/"+tok+"/feed/"+feedASIN+"/"+h2+".rss" {
			t.Errorf("self = %q", f.Channel.Self.Href)
		}
	})
	t.Run("by season", func(t *testing.T) {
		f := parseFeed(t, get("2"))
		if f.Channel.Title != "Dune [M4B]" || len(f.Channel.Items) != 1 {
			t.Errorf("channel = %q, %d items", f.Channel.Title, len(f.Channel.Items))
		}
	})
	t.Run("unknown files give one full-release item", func(t *testing.T) {
		f := parseFeed(t, get(h3))
		if len(f.Channel.Items) != 1 {
			t.Fatalf("items = %d", len(f.Channel.Items))
		}
		it := f.Channel.Items[0]
		if it.Title != "Play full release" || it.GUID.Value != "abk:"+feedASIN+":"+h3+":-1" || it.Enclosure.Length != 0 ||
			it.Enclosure.Type != "audio/mp4" || it.Enclosure.URL != testPublic+"/resolve/"+tok+"/torbox/"+h3+"/-1/release.m4b" {
			t.Errorf("item = %+v", it)
		}
	})
	t.Run("pack serves only the filtered files", func(t *testing.T) {
		f := parseFeed(t, get(h4))
		if len(f.Channel.Items) != 1 || f.Channel.Items[0].Title != "01" || f.Channel.Items[0].Enclosure.Type != "audio/mp4" {
			t.Errorf("items = %+v", f.Channel.Items)
		}
	})
	t.Run("bad refs are 404", func(t *testing.T) {
		for _, ref := range []string{"0", "5", "99", "-1", "01", "abc", strings.Repeat("e", 40), strings.Repeat("a", 39), "1.5", "1000"} {
			mustStatus(t, get(ref), 404)
		}
		mustStatus(t, e.get("/"+tok+"/feed/"+feedASIN+"/1"), 404)       // missing .rss
		mustStatus(t, e.get("/"+tok+"/feed/"+feedASIN), 404)            // missing .rss
		mustStatus(t, e.get("/"+tok+"/feed/"+feedASIN+"/.rss"), 404)    // empty ref
		mustStatus(t, e.get("/"+tok+"/feed/B00%2F0.rss"), 404)          // bad asin
		mustStatus(t, e.get("/"+tok+"/feed/B000000000.rss"), 404)       // unknown book
		mustStatus(t, e.get("/"+tok+"/feed/"+feedASIN+".rss.rss"), 404) // double suffix
	})
	t.Run("pack without files is not offered", func(t *testing.T) {
		e2, tok2 := feedEnv(t)
		e2.api.book = func(userconfig.Config, string) (*service.BookPage, error) {
			p := feedBookPage()
			p.Releases = []service.ReleaseView{{Season: 1, Collection: true, Release: p.Releases[3].Release}}
			return p, nil
		}
		mustStatus(t, e2.get("/"+tok2+"/feed/"+feedASIN+".rss"), 404)
	})
	t.Run("upper-case hash is accepted", func(t *testing.T) {
		mustStatus(t, get(strings.ToUpper(h2)), 200)
	})
}

func TestFeedErrors(t *testing.T) {
	e, tok := feedEnv(t)
	t.Run("tampered cfg", func(t *testing.T) {
		rec := e.get("/" + tok[:len(tok)-2] + "xx/feed/" + feedASIN + ".rss")
		mustStatus(t, rec, 400)
		if decode(t, rec)["error"] != "invalid config" {
			t.Errorf("body = %s", rec.Body.String())
		}
	})
	t.Run("no usable provider", func(t *testing.T) {
		e.api.providers = []string{"someother"}
		defer func() { e.api.providers = nil }()
		mustStatus(t, e.get("/"+tok+"/feed/"+feedASIN+".rss"), 400)
	})
	t.Run("outage", func(t *testing.T) {
		e.api.book = func(userconfig.Config, string) (*service.BookPage, error) { return nil, errors.New("db") }
		mustStatus(t, e.get("/"+tok+"/feed/"+feedASIN+".rss"), 502)
	})
}

func TestFeedSanitizes(t *testing.T) {
	e, tok := feedEnv(t)
	e.api.book = func(userconfig.Config, string) (*service.BookPage, error) {
		b := sampleBook()
		b.Title = "Bad\x00 \x01title\x1b & <co>"
		b.Description = "line\x00 with \x08control\xff"
		b.Authors = []string{"A\x0bB"}
		b.CoverURL = "http://insecure.example/c.jpg"
		b.Language = "nl"
		return &service.BookPage{Book: b, Releases: []service.ReleaseView{
			{Season: 1, Release: model.Release{InfoHash: strings.Repeat("a", 40)}, Files: []model.File{{Index: 0, Path: "x/\x07na\x00me?#%.mp3"}}},
		}}, nil
	}
	rec := e.get("/" + tok + "/feed/" + feedASIN + ".rss")
	mustStatus(t, rec, 200)
	for _, r := range rec.Body.String() {
		if r < 0x20 && r != '\n' && r != '\t' && r != '\r' || r == 0xFFFD {
			t.Fatalf("invalid character %U in feed", r)
		}
	}
	f := parseFeed(t, rec)
	if f.Channel.Title != "Bad title & <co>" || f.Channel.Language != "nl" || f.Channel.Image.Href != "" || f.Channel.Author != "AB" {
		t.Errorf("channel = %+v", f.Channel)
	}
	if len(f.Channel.Items) != 1 || !strings.HasSuffix(f.Channel.Items[0].Enclosure.URL, "/0/name%3F%23%25.mp3") {
		t.Errorf("items = %+v", f.Channel.Items)
	}
}

func TestItemTitle(t *testing.T) {
	tests := []struct{ in, want string }{
		{"Book/01 - Prologue.mp3", "Prologue"},
		{"Book/01. Prologue.mp3", "Prologue"},
		{"Book/01_Prologue.mp3", "Prologue"},
		{"Book/12) Intro.mp3", "Intro"},
		{"Book/007 Bond.mp3", "Bond"},
		{"Book/1984.mp3", "1984"},
		{"Book/3 Body Problem.m4b", "3 Body Problem"},
		{"Book/Chapter 01.mp3", "Chapter 01"},
		{"Book/01.mp3", "01"},
		{"Book/01 - 02.mp3", "02"},
		{"", "Chapter 5"},
	}
	for _, tc := range tests {
		if got := itemTitle(tc.in, 5); got != tc.want {
			t.Errorf("itemTitle(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

func TestAudioType(t *testing.T) {
	for ext, want := range map[string]string{
		"a.mp3": "audio/mpeg", "a.M4B": "audio/mp4", "a.m4a": "audio/mp4", "a.aac": "audio/aac", "a.flac": "audio/flac",
		"a.ogg": "audio/ogg", "a.opus": "audio/ogg", "a.wav": "audio/wav", "a.wma": "audio/x-ms-wma", "a": "audio/mpeg",
	} {
		if got := audioType(ext); got != want {
			t.Errorf("audioType(%q) = %q, want %q", ext, got, want)
		}
	}
}

// ---- listen page ---------------------------------------------------------------------------

func TestListenPage(t *testing.T) {
	e, tok := feedEnv(t)
	rec := e.get("/" + tok + "/listen/" + feedASIN)
	mustStatus(t, rec, 200)
	if ct := rec.Header().Get("Content-Type"); ct != "text/html; charset=utf-8" {
		t.Errorf("Content-Type = %q", ct)
	}
	csp := rec.Header().Get("Content-Security-Policy")
	m := nonceRe.FindStringSubmatch(csp)
	if m == nil || !strings.Contains(csp, "default-src 'none'") || !strings.Contains(csp, "script-src 'nonce-"+m[1]+"'") ||
		!strings.Contains(csp, "style-src 'nonce-"+m[1]+"'") || strings.Contains(csp, "unsafe-inline") || strings.Contains(csp, "connect-src") && !strings.Contains(csp, "connect-src 'none'") {
		t.Fatalf("CSP = %q", csp)
	}
	body := rec.Body.String()
	if strings.Count(html.UnescapeString(body), `nonce="`+m[1]+`"`) != 2 {
		t.Errorf("expected nonce on the style and the script block")
	}
	for _, h := range []string{"Cache-Control", "Referrer-Policy", "X-Frame-Options"} {
		if rec.Header().Get(h) == "" {
			t.Errorf("missing header %s", h)
		}
	}
	// every inline <script>/<style> carries the nonce; no external requests besides the cover
	if strings.Contains(body, "<script>") || strings.Contains(body, "<style>") || strings.Contains(body, "onclick=") {
		t.Error("inline code without nonce")
	}
	for _, bad := range []string{"<script src", "@import", "fetch(", "XMLHttpRequest"} {
		if strings.Contains(body, bad) {
			t.Errorf("unexpected %q", bad)
		}
	}
	if strings.Contains(body, testKey) {
		t.Error("debrid key echoed")
	}

	h1, h2 := strings.Repeat("a", 40), strings.Repeat("b", 40)
	feed1 := testPublic + "/" + tok + "/feed/" + feedASIN + "/" + h1 + ".rss"
	feed2 := testPublic + "/" + tok + "/feed/" + feedASIN + "/" + h2 + ".rss"
	for _, want := range []string{
		"<h1>Dune</h1>", "Frank Herbert", `src="https://img.example/dune.jpg"`,
		`value="` + feed1 + `"`, `value="` + feed2 + `"`,
		`href="antennapod-subscribe://` + feed1 + `"`,
		`href="pktc://subscribehttps/` + strings.TrimPrefix(feed1, "https://") + `"`,
		`href="pcast://` + strings.TrimPrefix(feed1, "https://") + `"`,
		`href="podcast://` + strings.TrimPrefix(feed1, "https://") + `"`,
		"Open in AntennaPod", "Open in Pocket Casts", "⚡ cached", "series pack", "readonly", "data-copy=",
		"MP3", "M4B", "Add podcast by URL",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("page lacks %q", want)
		}
	}
	if strings.Count(body, "Open in AntennaPod") != 4 {
		t.Errorf("want one button per release (4), body:\n%s", body)
	}
	if strings.Contains(body, "#ZgotmplZ") {
		t.Error("a link was filtered by html/template")
	}
	if strings.Contains(e.logs.String(), tok) {
		t.Error("token logged")
	}
	// the nonce changes per request
	if m2 := nonceRe.FindStringSubmatch(e.get("/" + tok + "/listen/" + feedASIN).Header().Get("Content-Security-Policy")); m2 == nil || m2[1] == m[1] {
		t.Error("nonce reused")
	}
}

func TestListenPageEscapesAndEdgeCases(t *testing.T) {
	e, tok := feedEnv(t)
	e.api.book = func(userconfig.Config, string) (*service.BookPage, error) {
		b := sampleBook()
		b.Title = `<script>alert(1)</script>"`
		b.CoverURL = "javascript:alert(1)"
		return &service.BookPage{Book: b, Releases: []service.ReleaseView{
			{Season: 1, Release: model.Release{InfoHash: strings.Repeat("a", 40), Title: `x"><img src=x onerror=alert(1)>`}},
		}}, nil
	}
	rec := e.get("/" + tok + "/listen/" + feedASIN)
	mustStatus(t, rec, 200)
	body := rec.Body.String()
	if strings.Contains(body, "<script>alert") || strings.Contains(body, "<img src=x") || strings.Contains(body, "javascript:") {
		t.Errorf("unescaped content:\n%s", body)
	}
	if strings.Contains(body, `<img src="http`) {
		t.Error("non-https cover must not render")
	}
	if strings.Count(body, "<img") != 1 || !strings.Contains(body, `<img src="/static/logo.png"`) {
		t.Error("only the brand logo may render when the cover is not https")
	}

	e.api.book = func(userconfig.Config, string) (*service.BookPage, error) {
		return &service.BookPage{Book: sampleBook()}, nil
	}
	rec = e.get("/" + tok + "/listen/" + feedASIN)
	mustStatus(t, rec, 200)
	if !strings.Contains(rec.Body.String(), "No playable release") {
		t.Error("empty state missing")
	}
}

func TestListenErrors(t *testing.T) {
	e, tok := feedEnv(t)
	mustStatus(t, e.get("/"+tok[:len(tok)-2]+"xx/listen/"+feedASIN), 400)
	mustStatus(t, e.get("/"+tok+"/listen/B000000000"), 404)
	mustStatus(t, e.get("/"+tok+"/listen/bad%20asin"), 404)
}

func TestSubscribeLinks(t *testing.T) {
	a, p, pc, pd := subscribeLinks("https://h.example/tok/feed/B1/aa.rss")
	if a != "antennapod-subscribe://https://h.example/tok/feed/B1/aa.rss" || p != "pktc://subscribehttps/h.example/tok/feed/B1/aa.rss" ||
		pc != "pcast://h.example/tok/feed/B1/aa.rss" || pd != "podcast://h.example/tok/feed/B1/aa.rss" {
		t.Errorf("got %v %v %v %v", a, p, pc, pd)
	}
	_, p, _, _ = subscribeLinks("http://h.example:8080/x.rss")
	if p != "pktc://subscribe/h.example:8080/x.rss" {
		t.Errorf("http pocket casts link = %v", p)
	}
}

func TestFeedRoutesMetrics(t *testing.T) {
	e, tok := feedEnv(t)
	e.get("/" + tok + "/feed/" + feedASIN + ".rss")
	e.get("/" + tok + "/feed/" + feedASIN + "/1.rss")
	e.get("/" + tok + "/listen/" + feedASIN)
	body := e.get("/metrics").Body.String()
	for _, route := range []string{"GET /{cfg}/feed/{asin}", "GET /{cfg}/feed/{asin}/{ref}", "GET /{cfg}/listen/{asin}"} {
		if !strings.Contains(body, `route="`+route+`"`) {
			t.Errorf("metrics lack route label %q", route)
		}
	}
	if strings.Contains(body, tok) {
		t.Error("token in metrics")
	}
}
