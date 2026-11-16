package httpapi

import (
	"bytes"
	"embed"
	"net/http"
	"time"
)

// The brand images (logo, background, icons) are generated artwork embedded in the binary.
//
//go:embed assets/logo.png assets/icon-192.png assets/favicon.png assets/background.jpg
var assetFS embed.FS

func serveAsset(name, contentType string) http.HandlerFunc {
	data, err := assetFS.ReadFile("assets/" + name)
	if err != nil {
		panic("httpapi: missing embedded asset " + name) // a build-time mistake, caught by tests
	}
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", contentType)
		w.Header().Set("Cache-Control", "public, max-age=86400")
		http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(data))
	}
}
