package httpapi

import (
	"bytes"
	"context"
	"errors"
	"net/http"
	"net/url"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
)

const (
	resolveTimeout = 30 * time.Second
	retryAfterSecs = "30"

	// Silent MPEG-1 Layer III, 44.1 kHz mono, 32 kbit/s CBR: 104-byte frames of 26.1 ms.
	// An all-zero payload (empty side info, no main data) decodes to silence.
	mp3FrameLen = 104
	mp3Frames   = 383 // ~10 s
)

var placeholderMP3 = silentMP3(mp3Frames)

func silentMP3(frames int) []byte {
	b := make([]byte, frames*mp3FrameLen)
	for i := range frames {
		copy(b[i*mp3FrameLen:], []byte{0xFF, 0xFB, 0x10, 0xC0})
	}
	return b
}

func (s *server) servePlaceholder(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Content-Type", "audio/mpeg")
	http.ServeContent(w, r, "", time.Time{}, bytes.NewReader(placeholderMP3))
}

func (s *server) knownProvider(name string) string {
	for _, p := range s.api.Providers() {
		if p == name {
			return name
		}
	}
	return "unknown"
}

func (s *server) resolve(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Cache-Control", "no-store")
	provider, hash := r.PathValue("provider"), r.PathValue("hash")
	idx, idxOK := parseIndex(r.PathValue("idx"))
	if !isProviderName(provider) || !isInfoHash(hash) || !idxOK {
		writeError(w, http.StatusNotFound, "not found")
		return
	}
	cfg, _, ok := s.openCfg(w, r)
	if !ok {
		return
	}
	if r.Method == http.MethodHead {
		// A probe must never have side effects: resolving can add a torrent to the user's
		// debrid account. Answer with what a player needs to know and do nothing.
		w.Header().Set("Content-Type", "audio/mpeg")
		w.Header().Set("Accept-Ranges", "bytes")
		w.WriteHeader(http.StatusOK)
		return
	}
	label := s.knownProvider(provider)
	outcome := func(o string) { s.m.resolves.WithLabelValues(label, o).Inc() }

	ctx, cancel := context.WithTimeout(r.Context(), resolveTimeout)
	defer cancel()
	res, err := s.api.Resolve(ctx, cfg, provider, hash, idx)
	switch {
	case err == nil && res.Pending:
		outcome("pending")
		s.servePlaceholder(w, r)
	case err == nil:
		u, perr := url.Parse(res.URL)
		if perr != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" {
			s.log.Error("resolve returned an unusable url", "provider", provider)
			outcome("error")
			writeError(w, http.StatusBadGateway, "provider error")
			return
		}
		outcome("redirect")
		w.Header().Set("Location", res.URL)
		w.WriteHeader(http.StatusFound)
	case errors.Is(err, debrid.ErrBadKey):
		s.log.Warn("resolve: debrid key rejected", "provider", provider)
		outcome("bad_key")
		s.servePlaceholder(w, r)
	case errors.Is(err, debrid.ErrRateLimited):
		outcome("rate_limited")
		w.Header().Set("Retry-After", retryAfterSecs)
		writeError(w, http.StatusTooManyRequests, "rate limited")
	case errors.Is(err, debrid.ErrNotFound), errors.Is(err, service.ErrNotFound), errors.Is(err, service.ErrNoProvider):
		outcome("not_found")
		// The reason (e.g. "no audio file in torrent") is what makes a failing stream debuggable.
		s.log.Warn("resolve: not found", "provider", provider, "err", safeErr(err))
		writeError(w, http.StatusNotFound, "not found")
	case errors.Is(err, context.Canceled) && r.Context().Err() != nil:
		outcome("canceled")
	case errors.Is(err, context.DeadlineExceeded):
		outcome("timeout")
		writeError(w, http.StatusGatewayTimeout, "provider timeout")
	default:
		s.log.Warn("resolve failed", "provider", provider, "err", safeErr(err))
		outcome("error")
		writeError(w, http.StatusBadGateway, "provider error")
	}
}
