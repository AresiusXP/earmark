package accounts

import (
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"strings"
)

// Codes use Crockford base32 (no I, L, O, U), shown in groups of four ("K7QD-9XMP-...").
const codeAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

const (
	inviteCodeLen = 16 // 80 bits
	linkCodeLen   = 12 // 60 bits; valid for 15 minutes behind a global failure limiter
)

func newCode(n int) (string, error) {
	raw := make([]byte, n)
	if _, err := rand.Read(raw); err != nil {
		return "", fmt.Errorf("accounts: random: %w", err)
	}
	var b strings.Builder
	for i, v := range raw {
		if i > 0 && i%4 == 0 {
			b.WriteByte('-')
		}
		b.WriteByte(codeAlphabet[int(v)&31])
	}
	return b.String(), nil
}

// normCode makes a typed code comparable: upper case, no separators; Crockford's look-alike
// letters are folded (I, L -> 1, O -> 0).
func normCode(s string) string {
	s = strings.ToUpper(s)
	var b strings.Builder
	for _, r := range s {
		switch r {
		case '-', ' ', '\t', '\n', '\r':
			continue
		case 'I', 'L':
			r = '1'
		case 'O':
			r = '0'
		}
		b.WriteRune(r)
	}
	return b.String()
}

// hashCode returns the SHA-256 of the normalised code (what the database stores).
func hashCode(code string) []byte {
	h := sha256.Sum256([]byte(normCode(code)))
	return h[:]
}

func newRefresh() (token string, hash []byte, err error) {
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return "", nil, fmt.Errorf("accounts: random: %w", err)
	}
	h := sha256.Sum256(raw)
	return b64.EncodeToString(raw), h[:], nil
}

func hashRefresh(token string) ([]byte, bool) {
	raw, err := b64.DecodeString(token)
	if err != nil || len(raw) != 32 {
		return nil, false
	}
	h := sha256.Sum256(raw)
	return h[:], true
}
