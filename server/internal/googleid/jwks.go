package googleid

import (
	"context"
	"crypto/rsa"
	"encoding/json"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	minKeyTTL = 5 * time.Minute
	maxKeyTTL = 24 * time.Hour
	// refetchGap is the shortest time between two fetches caused by an unknown kid, so a flood
	// of tokens with made-up kids cannot turn this server into a Google client.
	refetchGap  = time.Minute
	maxJWKSBody = 1 << 20
	minRSABits  = 2048
)

// keySet caches Google's signing keys by kid.
type keySet struct {
	url string
	hc  *http.Client
	now func() time.Time

	mu        sync.RWMutex
	keys      map[string]*rsa.PublicKey
	expires   time.Time // the cache is trusted until then
	fetchedAt time.Time

	fetchMu sync.Mutex // one fetch at a time
}

func newKeySet(url string, hc *http.Client, now func() time.Time) *keySet {
	return &keySet{url: url, hc: hc, now: now}
}

func (k *keySet) lookup(kid string) (pub *rsa.PublicKey, fresh bool) {
	k.mu.RLock()
	defer k.mu.RUnlock()
	return k.keys[kid], k.keys != nil && k.now().Before(k.expires)
}

// get returns the key for kid. A stale cache is refreshed; an unknown kid in a fresh cache is
// refetched once (at most every refetchGap) because Google rotates keys ahead of Cache-Control.
func (k *keySet) get(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	if pub, fresh := k.lookup(kid); pub != nil && fresh {
		return pub, nil
	}
	k.fetchMu.Lock()
	defer k.fetchMu.Unlock()
	pub, fresh := k.lookup(kid) // someone may have fetched while we waited
	if pub != nil && fresh {
		return pub, nil
	}
	k.mu.RLock()
	empty := k.keys == nil
	recent := !k.fetchedAt.IsZero() && k.now().Sub(k.fetchedAt) < refetchGap
	k.mu.RUnlock()
	if recent {
		switch {
		case pub != nil:
			return pub, nil // stale or not, a known key within the gap
		case empty:
			return nil, fmt.Errorf("%w: recent fetch failed", ErrUnavailable)
		}
		return nil, reject("unknown key")
	}
	if err := k.fetch(ctx); err != nil {
		// An expired cache still beats failing every sign-in during a short Google outage, but
		// only for a key we already knew.
		if pub != nil {
			return pub, nil
		}
		return nil, err
	}
	pub, _ = k.lookup(kid)
	if pub == nil {
		return nil, reject("unknown key")
	}
	return pub, nil
}

type jwk struct {
	Kty string `json:"kty"`
	Kid string `json:"kid"`
	Alg string `json:"alg"`
	Use string `json:"use"`
	N   string `json:"n"`
	E   string `json:"e"`
}

func (k *keySet) fetch(ctx context.Context) error {
	k.mu.Lock()
	k.fetchedAt = k.now() // also on failure, so an outage is not hammered
	k.mu.Unlock()

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, k.url, nil)
	if err != nil {
		return fmt.Errorf("%w: %v", ErrUnavailable, err)
	}
	req.Header.Set("Accept", "application/json")
	resp, err := k.hc.Do(req)
	if err != nil {
		return fmt.Errorf("%w: fetch failed", ErrUnavailable)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("%w: status %d", ErrUnavailable, resp.StatusCode)
	}
	var set struct {
		Keys []jwk `json:"keys"`
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, maxJWKSBody))
	if err != nil || json.Unmarshal(raw, &set) != nil { // Unmarshal: exactly one value, nothing after it
		return fmt.Errorf("%w: bad key set", ErrUnavailable)
	}
	keys := make(map[string]*rsa.PublicKey, len(set.Keys))
	for _, j := range set.Keys {
		if pub := j.public(); pub != nil {
			keys[j.Kid] = pub
		}
	}
	if len(keys) == 0 {
		return fmt.Errorf("%w: no usable keys", ErrUnavailable)
	}
	ttl := min(max(maxAge(resp.Header.Get("Cache-Control")), minKeyTTL), maxKeyTTL)
	k.mu.Lock()
	k.keys, k.expires = keys, k.now().Add(ttl)
	k.mu.Unlock()
	return nil
}

func (j jwk) public() *rsa.PublicKey {
	if j.Kty != "RSA" || j.Kid == "" || (j.Alg != "" && j.Alg != "RS256") || (j.Use != "" && j.Use != "sig") {
		return nil
	}
	nb, err1 := b64.DecodeString(j.N)
	eb, err2 := b64.DecodeString(j.E)
	if err1 != nil || err2 != nil || len(eb) == 0 || len(eb) > 4 {
		return nil
	}
	n := new(big.Int).SetBytes(nb)
	if n.BitLen() < minRSABits {
		return nil
	}
	e := int(new(big.Int).SetBytes(eb).Int64())
	if e < 3 || e%2 == 0 {
		return nil
	}
	return &rsa.PublicKey{N: n, E: e}
}

// maxAge returns the max-age of a Cache-Control header, 0 when absent (the caller clamps).
func maxAge(cc string) time.Duration {
	for _, d := range strings.Split(cc, ",") {
		d = strings.TrimSpace(d)
		if v, ok := strings.CutPrefix(strings.ToLower(d), "max-age="); ok {
			if n, err := strconv.ParseInt(strings.Trim(v, `"`), 10, 64); err == nil && n > 0 && n < 1<<31 {
				return time.Duration(n) * time.Second
			}
		}
	}
	return 0
}
