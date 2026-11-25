package userkeys

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hkdf"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
)

const (
	sealLabel        = "audiobooks-keys-v1"
	fingerprintLabel = "audiobooks-keys-v1/fingerprint"
	sealPrefix       = "v1:"
	minSecret        = 32
)

// ErrInvalidSealed is returned by Open for anything that is not a value sealed with this key
// for the same context: bad format, truncation, tampering, a different secret or a value moved
// to another user or provider.
var ErrInvalidSealed = errors.New("userkeys: invalid sealed value")

// Sealer seals debrid keys with AES-256-GCM and fingerprints them. Its keys are derived from
// CONFIG_SECRET with HKDF under labels of their own: the JWT key and the Stremio config key are
// different keys. Safe for concurrent use.
type Sealer struct {
	aead   cipher.AEAD
	fpKey  []byte
	random func([]byte) (int, error) // tests
}

// NewSealer derives the sealing and fingerprint keys from secret (>= 32 bytes).
func NewSealer(secret string) (*Sealer, error) {
	if len(secret) < minSecret {
		return nil, fmt.Errorf("userkeys: secret must be at least %d bytes", minSecret)
	}
	key, err := hkdf.Key(sha256.New, []byte(secret), nil, sealLabel, 32)
	if err != nil {
		return nil, fmt.Errorf("userkeys: derive key: %w", err)
	}
	fp, err := hkdf.Key(sha256.New, []byte(secret), nil, fingerprintLabel, 32)
	if err != nil {
		return nil, fmt.Errorf("userkeys: derive fingerprint key: %w", err)
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Sealer{aead: aead, fpKey: fp, random: rand.Read}, nil
}

// context binds a sealed value to its row, so copying it to another user's row or provider
// makes it unreadable.
func sealContext(userID, provider string) []byte { return []byte(userID + "\x00" + provider) }

// Seal encrypts plain for (userID, provider) with a fresh random nonce.
// Format: "v1:" + base64url(nonce || ciphertext+tag).
func (s *Sealer) Seal(userID, provider, plain string) (string, error) {
	nonce := make([]byte, s.aead.NonceSize())
	if _, err := s.random(nonce); err != nil {
		return "", fmt.Errorf("userkeys: nonce: %w", err)
	}
	out := s.aead.Seal(nonce, nonce, []byte(plain), sealContext(userID, provider))
	return sealPrefix + base64.RawURLEncoding.EncodeToString(out), nil
}

// Open decrypts a value produced by Seal for the same (userID, provider).
func (s *Sealer) Open(userID, provider, sealed string) (string, error) {
	enc, ok := strings.CutPrefix(sealed, sealPrefix)
	if !ok {
		return "", ErrInvalidSealed
	}
	raw, err := base64.RawURLEncoding.DecodeString(enc)
	n := s.aead.NonceSize()
	if err != nil || len(raw) < n+s.aead.Overhead() {
		return "", ErrInvalidSealed
	}
	plain, err := s.aead.Open(nil, raw[:n], raw[n:], sealContext(userID, provider))
	if err != nil {
		return "", ErrInvalidSealed
	}
	return string(plain), nil
}

// Fingerprint is hex(HMAC-SHA256(fingerprint key, apiKey)): equal for equal keys, useless
// without the server secret. Used to spot a household sharing one key and to key per-account
// budgets.
func (s *Sealer) Fingerprint(apiKey string) string {
	m := hmac.New(sha256.New, s.fpKey)
	m.Write([]byte(apiKey))
	return hex.EncodeToString(m.Sum(nil))
}
