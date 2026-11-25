// Package httpapi implements the Stremio addon protocol (manifest, catalog, meta, stream), the
// /resolve redirector, the /configure page and the background-listening feed (/feed, /listen)
// on top of service.API.
//
// Security notes: the sealed user config travels in the URL path, so no request URL is ever
// logged (only route patterns), every response sends Referrer-Policy: no-referrer, and debrid
// keys are never echoed back to the browser.
package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"runtime/debug"
	"strconv"
	"strings"
	"time"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"

	"github.com/AresiusXP/earmark/server/internal/linkhosts"
	"github.com/AresiusXP/earmark/server/internal/service"
	"github.com/AresiusXP/earmark/server/internal/userconfig"
)

// Deps are the collaborators of the HTTP layer.
type Deps struct {
	API              service.API
	Sealer           *userconfig.Sealer
	PublicURL        string // e.g. https://earmark.aresius.xyz (no trailing slash needed)
	InstancePassword string // empty = no password on POST /api/config
	Logger           *slog.Logger
	Version          string
	Registry         *prometheus.Registry // nil = a private registry with Go/process collectors
	// V1 serves every path under /v1/ (the app API, see internal/appapi). It sits outside the
	// Stremio CORS policy: no Access-Control-Allow-Origin is sent for it. nil = /v1 is a 404.
	V1 http.Handler
	// LinkHosts adds CDN host suffixes per provider to the allowlist of /resolve redirects (DEBRID_LINK_HOSTS).
	LinkHosts map[string][]string
}

type server struct {
	api       service.API
	sealer    *userconfig.Sealer
	publicURL string
	password  string
	log       *slog.Logger
	version   string

	m          *metrics
	metricsH   http.Handler
	limiter    *bucket
	main       *http.ServeMux
	resolveMux *http.ServeMux
	v1         http.Handler
	allow      linkhosts.Allowlist
}

// New builds the addon handler.
func New(d Deps) http.Handler {
	s := &server{
		api:       d.API,
		sealer:    d.Sealer,
		publicURL: strings.TrimRight(d.PublicURL, "/"),
		password:  d.InstancePassword,
		log:       d.Logger,
		version:   d.Version,
		limiter:   newBucket(30, 30, time.Minute),
		v1:        d.V1,
		allow:     linkhosts.New(d.LinkHosts),
	}
	if s.log == nil {
		s.log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	reg := d.Registry
	if reg == nil {
		reg = prometheus.NewRegistry()
		reg.MustRegister(collectorsDefault()...)
	}
	s.m = newMetrics(reg)
	s.metricsH = promhttp.HandlerFor(reg, promhttp.HandlerOpts{})

	m := http.NewServeMux()
	m.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Location", "/configure")
		w.WriteHeader(http.StatusFound)
	})
	m.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = io.WriteString(w, "ok")
	})
	m.Handle("GET /metrics", s.metricsH)
	m.HandleFunc("GET /static/logo.png", serveAsset("logo.png", "image/png"))
	m.HandleFunc("GET /static/icon-192.png", serveAsset("icon-192.png", "image/png"))
	m.HandleFunc("GET /static/favicon.png", serveAsset("favicon.png", "image/png"))
	m.HandleFunc("GET /static/background.jpg", serveAsset("background.jpg", "image/jpeg"))
	m.HandleFunc("GET /favicon.ico", serveAsset("favicon.png", "image/png"))
	m.HandleFunc("GET /manifest.json", s.manifest)
	m.HandleFunc("GET /{cfg}/manifest.json", s.manifest)
	m.HandleFunc("GET /{cfg}/catalog/audiobook/{id}", s.catalog)
	m.HandleFunc("GET /{cfg}/catalog/audiobook/{id}/{extra}", s.catalog)
	m.HandleFunc("GET /{cfg}/meta/audiobook/{id}", s.meta)
	m.HandleFunc("GET /{cfg}/stream/audiobook/{id}", s.stream)
	// The ".rss" suffix cannot be part of a wildcard segment: the handler strips it from the
	// last segment ("{asin}" for the default feed, "{ref}" otherwise).
	m.HandleFunc("GET /{cfg}/feed/{asin}", s.feed)
	m.HandleFunc("GET /{cfg}/feed/{asin}/{ref}", s.feed)
	m.HandleFunc("GET /{cfg}/listen/{asin}", s.listenPage)
	m.HandleFunc("GET /configure", s.configurePage)
	m.HandleFunc("GET /{cfg}/configure", s.configurePage)
	m.HandleFunc("POST /api/config", s.configAPI)
	s.main = m

	// /resolve lives in its own mux: "/resolve/{cfg}/{p}/{h}/{i}" would otherwise conflict with
	// "/{cfg}/catalog/audiobook/{id}/{extra}" (both match /resolve/catalog/audiobook/x/y).
	rm := http.NewServeMux()
	rm.HandleFunc("GET /resolve/{cfg}/{provider}/{hash}/{idx}", s.resolve)
	rm.HandleFunc("GET /resolve/{cfg}/{provider}/{hash}/{idx}/{name...}", s.resolve)
	s.resolveMux = rm
	return s
}

func (s *server) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	h := w.Header()
	isV1 := strings.HasPrefix(r.URL.Path, "/v1/")
	if !isV1 {
		h.Set("Access-Control-Allow-Origin", "*")
		h.Set("Access-Control-Allow-Headers", "*")
		h.Set("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
		h.Set("Access-Control-Max-Age", "86400")
	}
	h.Set("Referrer-Policy", "no-referrer")
	h.Set("X-Content-Type-Options", "nosniff")
	if r.Method == http.MethodOptions && !isV1 {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	start := time.Now()
	sw := &statusWriter{ResponseWriter: w}
	defer func() {
		if rec := recover(); rec != nil {
			if rec == http.ErrAbortHandler { //nolint:errorlint // sentinel compared by identity
				panic(rec)
			}
			s.log.Error("panic in handler", "route", routeOf(r), "panic", rec, "stack", string(debug.Stack()))
			if !sw.wrote {
				writeError(sw, http.StatusInternalServerError, "internal error")
			}
		}
		route := routeOf(r)
		dur := time.Since(start)
		s.m.requests.WithLabelValues(route, itoa(sw.code())).Inc()
		s.m.duration.WithLabelValues(route).Observe(dur.Seconds())
		lvl := slog.LevelInfo
		if route == "GET /healthz" || route == "GET /metrics" {
			lvl = slog.LevelDebug
		}
		s.log.Log(r.Context(), lvl, "request", "method", r.Method, "route", route, "status", sw.code(), "dur", dur)
	}()

	if isV1 {
		if s.v1 == nil {
			http.NotFound(sw, r)
			return
		}
		s.v1.ServeHTTP(sw, r)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/resolve/") {
		s.resolveMux.ServeHTTP(sw, r)
		return
	}
	s.main.ServeHTTP(sw, r)
}

func routeOf(r *http.Request) string {
	if r.Pattern == "" {
		return "unmatched"
	}
	return r.Pattern
}

type statusWriter struct {
	http.ResponseWriter
	status int
	wrote  bool
}

func (w *statusWriter) WriteHeader(code int) {
	if !w.wrote {
		w.status, w.wrote = code, true
	}
	w.ResponseWriter.WriteHeader(code)
}

func (w *statusWriter) Write(b []byte) (int, error) {
	if !w.wrote {
		w.status, w.wrote = http.StatusOK, true
	}
	return w.ResponseWriter.Write(b)
}

func (w *statusWriter) code() int {
	if w.status == 0 {
		return http.StatusOK
	}
	return w.status
}

func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func itoa(n int) string { return strconv.Itoa(n) }

// ---- helpers ------------------------------------------------------------------------------

func writeJSON(w http.ResponseWriter, status int, v any) {
	b, err := json.Marshal(v)
	if err != nil {
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_, _ = w.Write(b)
}

func writeError(w http.ResponseWriter, status int, msg string) {
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, status, map[string]string{"error": msg})
}

func setCache(w http.ResponseWriter, maxAge int) {
	w.Header().Set("Cache-Control", "public, max-age="+itoa(maxAge))
}

func nz[T any](s []T) []T {
	if s == nil {
		return []T{}
	}
	return s
}

// openCfg opens the sealed config of the request. On failure it answers 400 and returns false.
func (s *server) openCfg(w http.ResponseWriter, r *http.Request) (userconfig.Config, string, bool) {
	tok := r.PathValue("cfg")
	cfg, err := s.sealer.Open(tok)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid config")
		return userconfig.Config{}, "", false
	}
	return cfg, tok, true
}

// isOutage reports whether err looks like an upstream outage (timeouts, network failures).
func isOutage(err error) bool {
	if errors.Is(err, context.DeadlineExceeded) {
		return true
	}
	var ne net.Error
	return errors.As(err, &ne)
}

// safeErr renders err for logs without request URLs (net/http errors embed them).
func safeErr(err error) string {
	var ue *url.Error
	if errors.As(err, &ue) && ue.Err != nil {
		return ue.Op + ": " + ue.Err.Error()
	}
	return err.Error()
}
