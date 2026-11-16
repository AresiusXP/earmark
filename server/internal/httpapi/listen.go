package httpapi

import (
	"crypto/rand"
	_ "embed"
	"encoding/base64"
	"html/template"
	"net/http"
	"strings"
)

//go:embed web/listen.html
var listenHTML string

var listenTmpl = template.Must(template.New("listen").Parse(listenHTML))

type listenRelease struct {
	Label       string
	Title       string
	Cached      bool
	Pack        bool
	Files       int
	FeedURL     string
	AntennaPod  template.URL
	PocketCasts template.URL
	Pcast       template.URL
	Podcast     template.URL
}

type listenData struct {
	Nonce    string
	Title    string
	Authors  string
	Cover    string
	Releases []listenRelease
}

// subscribeLinks builds the one-tap subscribe links for a feed URL. Verified against the apps'
// own sources: AntennaPod strips the "antennapod-subscribe://" prefix and keeps the rest as the
// feed URL (a bare host/path would be turned into http://, so the https:// stays in);
// Pocket Casts reads "pktc://subscribe/<rest>" as http://<rest> and "pktc://subscribehttps/<rest>"
// as https://<rest>; pcast:// and podcast:// carry host/path without a scheme.
func subscribeLinks(feed string) (antennapod, pocketcasts, pcast, podcast template.URL) {
	rest, secure := strings.CutPrefix(feed, "https://")
	if !secure {
		rest = strings.TrimPrefix(feed, "http://")
	}
	pc := "pktc://subscribe/"
	if secure {
		pc = "pktc://subscribehttps/"
	}
	return template.URL("antennapod-subscribe://" + feed), template.URL(pc + rest),
		template.URL("pcast://" + rest), template.URL("podcast://" + rest)
}

func (s *server) listenPage(w http.ResponseWriter, r *http.Request) {
	cfg, tok, ok := s.openCfg(w, r)
	if !ok {
		return
	}
	asin := r.PathValue("asin")
	if !isASIN(asin) {
		plainError(w, http.StatusNotFound, "not found")
		return
	}
	page, ok := s.bookPage(w, r, asin, cfg)
	if !ok {
		return
	}

	data := listenData{Title: page.Book.Title, Authors: strings.Join(page.Book.Authors, ", ")}
	if strings.HasPrefix(page.Book.CoverURL, "https://") {
		data.Cover = page.Book.CoverURL
	}
	for _, rv := range listenableReleases(page) {
		files := audioFiles(rv.Files)
		rel := rv.Release
		if rv.Collection {
			rel.Size = 0
			for _, f := range files {
				rel.Size += f.Size
			}
		}
		lr := listenRelease{Title: rel.Title, Pack: rv.Collection, Files: len(files)}
		if len(files) > 0 {
			lr.Label = releaseLabel(rel, &files[0])
		} else {
			lr.Label = releaseLabel(rel, nil)
		}
		for _, c := range rv.Cached {
			lr.Cached = lr.Cached || c
		}
		lr.FeedURL = s.feedURL(tok, asin, rel.InfoHash)
		lr.AntennaPod, lr.PocketCasts, lr.Pcast, lr.Podcast = subscribeLinks(lr.FeedURL)
		data.Releases = append(data.Releases, lr)
	}

	nonceRaw := make([]byte, 16)
	_, _ = rand.Read(nonceRaw)
	data.Nonce = base64.RawStdEncoding.EncodeToString(nonceRaw)

	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	h.Set("Content-Security-Policy", "default-src 'none'; style-src 'nonce-"+data.Nonce+"'; script-src 'nonce-"+data.Nonce+
		"'; img-src 'self' https:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	h.Set("X-Frame-Options", "DENY")
	if err := listenTmpl.Execute(w, data); err != nil {
		s.log.Error("listen page render failed", "err", err.Error())
	}
}
