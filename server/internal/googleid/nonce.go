package googleid

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/binary"
	"fmt"
	"strings"
	"sync"
	"time"
)

const (
	// NonceTTL is how long a nonce can be used after it was minted.
	NonceTTL = 5 * time.Minute
	// maxUsedNonces bounds the replay cache; a nonce is only recorded once a token carrying it
	// passed every other check, so filling it takes that many real Google sign-ins in 5 minutes.
	maxUsedNonces = 4096
)

// nonces mints stateless HMAC-signed nonces (random + expiry + MAC, nothing stored) and
// enforces single use with a bounded in-memory set of the nonces spent so far. A restart forgets
// the set; a replay then still needs an unexpired nonce AND an unexpired Google token.
type nonces struct {
	key []byte
	now func() time.Time

	mu   sync.Mutex
	used map[string]time.Time // random part -> expiry
}

func newNonces(key []byte, now func() time.Time) *nonces {
	return &nonces{key: key, now: now, used: map[string]time.Time{}}
}

func (n *nonces) mac(payload []byte) []byte {
	h := hmac.New(sha256.New, n.key)
	h.Write(payload)
	return h.Sum(nil)
}

func (n *nonces) mint() (string, time.Time, error) {
	payload := make([]byte, 16+8)
	if _, err := rand.Read(payload[:16]); err != nil {
		return "", time.Time{}, fmt.Errorf("googleid: random: %w", err)
	}
	exp := n.now().Add(NonceTTL).Truncate(time.Second)
	binary.BigEndian.PutUint64(payload[16:], uint64(exp.Unix()))
	return b64.EncodeToString(payload) + "." + b64.EncodeToString(n.mac(payload)), exp, nil
}

// consume checks the MAC and expiry of nonce and marks it used; a second call fails.
func (n *nonces) consume(nonce string) error {
	p, m, ok := strings.Cut(nonce, ".")
	if !ok || len(nonce) > 128 {
		return reject("bad nonce")
	}
	payload, err1 := b64.DecodeString(p)
	mac, err2 := b64.DecodeString(m)
	if err1 != nil || err2 != nil || len(payload) != 24 || !hmac.Equal(mac, n.mac(payload)) {
		return reject("bad nonce")
	}
	now := n.now()
	exp := time.Unix(int64(binary.BigEndian.Uint64(payload[16:])), 0)
	if !now.Before(exp) {
		return reject("nonce expired")
	}
	n.mu.Lock()
	defer n.mu.Unlock()
	if _, spent := n.used[p]; spent {
		return reject("nonce already used")
	}
	if len(n.used) >= maxUsedNonces {
		for k, e := range n.used {
			if !now.Before(e) {
				delete(n.used, k)
			}
		}
		if len(n.used) >= maxUsedNonces {
			return ErrBusy
		}
	}
	n.used[p] = exp
	return nil
}
