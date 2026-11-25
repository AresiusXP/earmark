package accounts

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"strings"
	"time"
)

// Claims are the access-token claims. Small on purpose.
type Claims struct {
	Sub string `json:"sub"` // user id
	Dev string `json:"dev"` // device id
	Iat int64  `json:"iat"`
	Exp int64  `json:"exp"`
}

var (
	b64     = base64.RawURLEncoding
	jwtHead = b64.EncodeToString([]byte(`{"alg":"HS256","typ":"JWT"}`))
)

func sign(secret []byte, c Claims) (string, error) {
	body, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	msg := jwtHead + "." + b64.EncodeToString(body)
	return msg + "." + b64.EncodeToString(mac(secret, msg)), nil
}

func mac(secret []byte, msg string) []byte {
	h := hmac.New(sha256.New, secret)
	h.Write([]byte(msg))
	return h.Sum(nil)
}

// verify checks the signature (constant time), the fixed header (no algorithm negotiation: only
// HS256 is accepted) and the expiry.
func verify(secret []byte, token string, now time.Time) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || parts[0] != jwtHead {
		return Claims{}, ErrInvalidToken
	}
	sig, err := b64.DecodeString(parts[2])
	if err != nil || !hmac.Equal(sig, mac(secret, parts[0]+"."+parts[1])) {
		return Claims{}, ErrInvalidToken
	}
	raw, err := b64.DecodeString(parts[1])
	if err != nil {
		return Claims{}, ErrInvalidToken
	}
	var c Claims
	if err := json.Unmarshal(raw, &c); err != nil || c.Sub == "" || c.Dev == "" {
		return Claims{}, ErrInvalidToken
	}
	if now.Unix() >= c.Exp {
		return Claims{}, ErrInvalidToken
	}
	return c, nil
}
