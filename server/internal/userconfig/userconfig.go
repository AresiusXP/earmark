// Package userconfig holds the per-user addon configuration (debrid keys, preferences)
// and seals it into an opaque URL path segment with AES-256-GCM, so keys never appear in
// clear text in the addon URL or in access logs. The server secret never leaves the server.
package userconfig

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"slices"
)

const (
	version       byte = 1
	maxSealedSize      = 4096
	minSecretLen       = 32
)

// ErrInvalid is returned for any config that cannot be opened (tampered, wrong secret, too big).
var ErrInvalid = errors.New("userconfig: invalid config")

// Config is what a user picks on /configure.
type Config struct {
	// Debrid maps a provider name ("torbox", "realdebrid") to the user's API key.
	Debrid       map[string]string `json:"d,omitempty"`
	Region       string            `json:"r,omitempty"`  // Audible marketplace, default "us"
	Languages    []string          `json:"l,omitempty"`  // "en", "nl"; empty = ["en"]
	PreferFormat string            `json:"f,omitempty"`  // "m4b", "mp3" or "" (m4b first)
	ShowUncached bool              `json:"u,omitempty"`  // list releases not cached anywhere
	MaxReleases  int               `json:"mr,omitempty"` // releases shown per book, default 5
}

var (
	validRegions   = []string{"us", "uk", "de", "fr", "it", "es", "ca", "au", "in", "nl"}
	validLanguages = []string{"en", "nl"}
	validFormats   = []string{"", "m4b", "mp3"}
)

// Normalize fills defaults and rejects unknown values. It returns a copy.
func (c Config) Normalize() (Config, error) {
	out := c
	out.Languages = slices.Clone(c.Languages)
	if out.Region == "" {
		out.Region = "us"
	}
	if !slices.Contains(validRegions, out.Region) {
		return Config{}, fmt.Errorf("unknown region %q", out.Region)
	}
	if len(out.Languages) == 0 {
		out.Languages = []string{"en"}
	}
	for _, l := range out.Languages {
		if !slices.Contains(validLanguages, l) {
			return Config{}, fmt.Errorf("unknown language %q", l)
		}
	}
	if !slices.Contains(validFormats, out.PreferFormat) {
		return Config{}, fmt.Errorf("unknown format %q", out.PreferFormat)
	}
	if out.MaxReleases <= 0 {
		out.MaxReleases = 5
	}
	if out.MaxReleases > 20 {
		out.MaxReleases = 20
	}
	out.Debrid = make(map[string]string, len(c.Debrid))
	for k, v := range c.Debrid {
		if v != "" {
			out.Debrid[k] = v
		}
	}
	return out, nil
}

// Sealer seals and opens configs with a key derived from the server secret.
type Sealer struct{ aead cipher.AEAD }

// NewSealer derives the AES key with HKDF-SHA256. The secret must be at least 32 bytes.
func NewSealer(secret string) (*Sealer, error) {
	if len(secret) < minSecretLen {
		return nil, fmt.Errorf("userconfig: secret must be at least %d bytes", minSecretLen)
	}
	key, err := hkdf.Key(sha256.New, []byte(secret), nil, "earmark user config v1", 32)
	if err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Sealer{aead: aead}, nil
}

// Seal normalizes c and returns a base64url string safe for a URL path segment.
func (s *Sealer) Seal(c Config) (string, error) {
	c, err := c.Normalize()
	if err != nil {
		return "", err
	}
	plain, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	out := append([]byte{version}, nonce...)
	out = s.aead.Seal(out, nonce, plain, []byte{version})
	if len(out) > maxSealedSize {
		return "", fmt.Errorf("userconfig: config too large")
	}
	return base64.RawURLEncoding.EncodeToString(out), nil
}

// Open reverses Seal. Every failure is ErrInvalid (no detail leaks to the caller).
func (s *Sealer) Open(token string) (Config, error) {
	if len(token) > maxSealedSize*2 {
		return Config{}, ErrInvalid
	}
	raw, err := base64.RawURLEncoding.DecodeString(token)
	ns := s.aead.NonceSize()
	if err != nil || len(raw) < 1+ns+s.aead.Overhead() || raw[0] != version {
		return Config{}, ErrInvalid
	}
	plain, err := s.aead.Open(nil, raw[1:1+ns], raw[1+ns:], raw[:1])
	if err != nil {
		return Config{}, ErrInvalid
	}
	var c Config
	if err := json.Unmarshal(plain, &c); err != nil {
		return Config{}, ErrInvalid
	}
	c, err = c.Normalize()
	if err != nil {
		return Config{}, ErrInvalid
	}
	return c, nil
}

// Default is the config used when the addon is installed without /configure.
func Default() Config {
	c, _ := Config{}.Normalize()
	return c
}
