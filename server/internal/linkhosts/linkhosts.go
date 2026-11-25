// Package linkhosts is the allowlist of CDN hosts a debrid download link may point at. The app
// download route (/v1/releases/.../download) and the legacy Stremio /resolve route both answer a
// 302 to a link they got from the provider's API, so both check it here.
package linkhosts

import (
	"errors"
	"net/url"
	"strings"
)

// builtin is the allowlist of CDN hosts a download link may point at, per provider. The
// download route answers a 302 to a link it got from the provider's API; the client follows it
// without the server seeing where it goes, so an unexpected host must never pass (no open
// redirect, whatever the provider API returns). A host matches when it equals an entry or ends
// with "."+entry, over https only, default port only, no userinfo.
//
//	TorBox        tb-cdn.st (nexus-NN.weur.tb-cdn.st, ...), torbox.app   same list as the Echoes server
//	Real-Debrid   real-debrid.com (NN-N.download.real-debrid.com, ...), real-debrid.cloud, rdb.so
//
// The RD list comes from the public documentation and links seen in practice; when a provider
// introduces a new CDN domain the route answers 502 upstream_failed until it is added here or via
// DEBRID_LINK_HOSTS (see Parse).
var builtin = map[string][]string{
	"torbox":     {"tb-cdn.st", "torbox.app"},
	"realdebrid": {"real-debrid.com", "real-debrid.cloud", "rdb.so"},
}

// ErrBadLink is returned for every refused link; it never contains the link.
var ErrBadLink = errors.New("linkhosts: download link is not on an allowed https host")

// Allowlist decides whether a provider link may be redirected to.
type Allowlist struct {
	hosts map[string][]string
}

// New returns the built-in allowlist plus extra suffixes per provider.
func New(extra map[string][]string) Allowlist {
	m := make(map[string][]string, len(builtin))
	for p, hs := range builtin {
		m[p] = append([]string(nil), hs...)
	}
	for p, hs := range extra {
		m[p] = append(m[p], hs...)
	}
	return Allowlist{hosts: m}
}

// Parse reads DEBRID_LINK_HOSTS: comma separated "provider:host-suffix" pairs, for
// example "realdebrid:example-cdn.net". Unknown providers and malformed entries are errors.
func Parse(v string) (map[string][]string, error) {
	out := map[string][]string{}
	for _, e := range strings.Split(v, ",") {
		e = strings.TrimSpace(e)
		if e == "" {
			continue
		}
		p, h, ok := strings.Cut(e, ":")
		h = strings.ToLower(strings.TrimSpace(h))
		if _, known := builtin[strings.TrimSpace(p)]; !ok || !known || !validHostSuffix(h) {
			return nil, errors.New(`DEBRID_LINK_HOSTS: entries are "torbox:<domain>" or "realdebrid:<domain>"`)
		}
		p = strings.TrimSpace(p)
		out[p] = append(out[p], h)
	}
	return out, nil
}

func validHostSuffix(h string) bool {
	if h == "" || len(h) > 253 || !strings.Contains(h, ".") || strings.HasPrefix(h, ".") || strings.HasSuffix(h, ".") {
		return false
	}
	for _, r := range h {
		if !(r >= 'a' && r <= 'z' || r >= '0' && r <= '9' || r == '-' || r == '.') {
			return false
		}
	}
	return true
}

// Check returns the normalised link when it may be redirected to, or ErrBadLink. The error never
// contains the link (it carries a token).
func (l Allowlist) Check(provider, link string) (string, error) {
	if link == "" || len(link) > 4096 {
		return "", ErrBadLink
	}
	for _, r := range link {
		if r < 0x21 || r == 0x7f || r == '\\' {
			return "", ErrBadLink
		}
	}
	u, err := url.Parse(link)
	if err != nil || u.Scheme != "https" || u.User != nil || u.Opaque != "" || u.Host == "" {
		return "", ErrBadLink
	}
	if p := u.Port(); p != "" && p != "443" {
		return "", ErrBadLink
	}
	host := strings.ToLower(u.Hostname())
	if host == "" || strings.HasSuffix(host, ".") {
		return "", ErrBadLink
	}
	for _, suf := range l.hosts[provider] {
		if host == suf || strings.HasSuffix(host, "."+suf) {
			return u.String(), nil
		}
	}
	return "", ErrBadLink
}
