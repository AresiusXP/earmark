package httpapi

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

//go:embed web/configure.html
var configureHTML string

const (
	maxConfigBody = 8 << 10
	maxKeyLen     = 512
	validateTO    = 20 * time.Second
)

type bootProvider struct {
	Name   string `json:"name"`
	Label  string `json:"label"`
	HasKey bool   `json:"hasKey"`
}

type bootConfig struct {
	Region       string   `json:"region"`
	Languages    []string `json:"languages"`
	PreferFormat string   `json:"preferFormat"`
	ShowUncached bool     `json:"showUncached"`
	MaxReleases  int      `json:"maxReleases"`
}

type bootData struct {
	Providers        []bootProvider `json:"providers"`
	PasswordRequired bool           `json:"passwordRequired"`
	Current          string         `json:"current"`
	Config           *bootConfig    `json:"config"`
}

func (s *server) configurePage(w http.ResponseWriter, r *http.Request) {
	boot := bootData{Providers: []bootProvider{}, PasswordRequired: s.password != ""}
	var cfg userconfig.Config
	if r.PathValue("cfg") != "" {
		c, tok, ok := s.openCfg(w, r)
		if !ok {
			return
		}
		cfg = c
		boot.Current = tok
		boot.Config = &bootConfig{
			Region: c.Region, Languages: nz(slices.Clone(c.Languages)), PreferFormat: c.PreferFormat,
			ShowUncached: c.ShowUncached, MaxReleases: c.MaxReleases,
		}
	}
	for _, p := range s.api.Providers() {
		_, has := cfg.Debrid[p] // only a boolean ever reaches the browser, never the key
		boot.Providers = append(boot.Providers, bootProvider{Name: p, Label: providerLabel(p), HasKey: has})
	}
	// json.Marshal escapes <, > and & so the data cannot break out of its <script> block.
	b, err := json.Marshal(boot)
	if err != nil {
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	nonceRaw := make([]byte, 16)
	_, _ = rand.Read(nonceRaw)
	nonce := base64.RawStdEncoding.EncodeToString(nonceRaw)
	page := strings.NewReplacer("{{NONCE}}", nonce, "{{BOOT}}", string(b)).Replace(configureHTML)

	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Cache-Control", "no-store")
	h.Set("Content-Security-Policy", "default-src 'none'; style-src 'nonce-"+nonce+"'; script-src 'nonce-"+nonce+
		"'; connect-src 'self'; img-src 'self' data:; base-uri 'none'; form-action 'none'; frame-ancestors 'none'")
	h.Set("X-Frame-Options", "DENY")
	_, _ = w.Write([]byte(page))
}

type configRequest struct {
	Password     string            `json:"password"`
	Current      string            `json:"current"`
	Keys         map[string]string `json:"keys"`
	Region       string            `json:"region"`
	Languages    []string          `json:"languages"`
	PreferFormat string            `json:"preferFormat"`
	ShowUncached bool              `json:"showUncached"`
	MaxReleases  int               `json:"maxReleases"`
}

type configResponse struct {
	Token       string `json:"token"`
	ManifestURL string `json:"manifestUrl"`
	InstallURL  string `json:"installUrl"`
}

func (s *server) configAPI(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	if !s.limiter.allow() {
		w.Header().Set("Retry-After", "5")
		writeError(w, http.StatusTooManyRequests, "too many requests")
		return
	}
	var req configRequest
	dec := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxConfigBody))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&req); err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			writeError(w, http.StatusRequestEntityTooLarge, "request too large")
			return
		}
		writeError(w, http.StatusBadRequest, "invalid request")
		return
	}
	if err := dec.Decode(&struct{}{}); !errors.Is(err, io.EOF) {
		writeError(w, http.StatusBadRequest, "invalid request")
		return
	}
	if s.password != "" && !passwordOK(req.Password, s.password) {
		writeError(w, http.StatusUnauthorized, "wrong password")
		return
	}

	debridKeys := map[string]string{}
	if req.Current != "" {
		cur, err := s.sealer.Open(req.Current)
		if err != nil {
			writeError(w, http.StatusBadRequest, "invalid config")
			return
		}
		for k, v := range cur.Debrid {
			debridKeys[k] = v
		}
	}
	known := s.api.Providers()
	errs := map[string]string{}
	toCheck := map[string]string{}
	for name, key := range req.Keys {
		key = strings.TrimSpace(key)
		if key == "" {
			continue // blank keeps the key from `current`
		}
		if !slices.Contains(known, name) {
			errs[name] = "unknown provider"
			continue
		}
		if len(key) > maxKeyLen {
			errs[name] = "key too long"
			continue
		}
		debridKeys[name] = key
		toCheck[name] = key
	}
	// keys from `current` for providers that no longer exist are dropped.
	for name := range debridKeys {
		if !slices.Contains(known, name) {
			delete(debridKeys, name)
		}
	}

	cfg, err := userconfig.Config{
		Debrid: debridKeys, Region: req.Region, Languages: req.Languages, PreferFormat: req.PreferFormat,
		ShowUncached: req.ShowUncached, MaxReleases: req.MaxReleases,
	}.Normalize()
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid settings")
		return
	}
	if len(errs) == 0 && len(cfg.Debrid) == 0 {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]any{
			"error": "enter at least one debrid API key", "errors": map[string]string{},
		})
		return
	}
	if len(toCheck) > 0 && len(errs) == 0 {
		ctx, cancel := context.WithTimeout(r.Context(), validateTO)
		for name, verr := range s.api.ValidateKeys(ctx, toCheck) {
			if verr == nil {
				continue
			}
			if errors.Is(verr, debrid.ErrBadKey) {
				errs[name] = "key rejected by the provider"
			} else {
				errs[name] = "could not verify the key, try again"
			}
		}
		cancel()
	}
	if len(errs) > 0 {
		writeJSON(w, http.StatusUnprocessableEntity, map[string]any{"error": "validation failed", "errors": errs})
		return
	}

	tok, err := s.sealer.Seal(cfg)
	if err != nil {
		s.log.Error("seal config failed", "err", err.Error())
		writeError(w, http.StatusInternalServerError, "internal error")
		return
	}
	writeJSON(w, http.StatusOK, configResponse{
		Token:       tok,
		ManifestURL: s.publicURL + "/" + tok + "/manifest.json",
		InstallURL:  s.installURL(tok),
	})
}

func (s *server) installURL(tok string) string {
	host := strings.TrimPrefix(strings.TrimPrefix(s.publicURL, "https://"), "http://")
	if u, err := url.Parse(s.publicURL); err == nil && u.Host != "" {
		host = u.Host + strings.TrimRight(u.Path, "/")
	}
	return "stremio://" + host + "/" + tok + "/manifest.json"
}

// passwordOK compares in constant time regardless of the lengths involved.
func passwordOK(got, want string) bool {
	a, b := sha256.Sum256([]byte(got)), sha256.Sum256([]byte(want))
	return subtle.ConstantTimeCompare(a[:], b[:]) == 1
}
