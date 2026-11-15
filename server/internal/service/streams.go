package service

import (
	"context"
	"fmt"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/metadata"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/sources"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

// Streams returns one Stream per configured provider (ordered by provider name) for one
// video. Cached comes from stored availability only; unknown counts as not cached.
func (s *Service) Streams(ctx context.Context, cfg userconfig.Config, ref VideoRef) ([]Stream, error) {
	providers := s.configuredProviders(cfg)
	if len(providers) == 0 {
		return nil, ErrNoProvider
	}
	if !metadata.ValidASIN(ref.ASIN) || !hashRe.MatchString(ref.InfoHash) || ref.FileIndex < AutoFile {
		return nil, ErrNotFound
	}
	cands, err := s.d.Store.Candidates(ctx, ref.ASIN)
	if err != nil {
		return nil, fmt.Errorf("service: load candidates: %w", err)
	}
	var rel *model.Release
	for i := range cands {
		if cands[i].Release.InfoHash == ref.InfoHash {
			rel = &cands[i].Release
			break
		}
	}
	if rel == nil {
		return nil, ErrNotFound
	}

	var file *model.File
	if ref.FileIndex != AutoFile {
		files, err := s.d.Store.Files(ctx, []string{ref.InfoHash})
		if err != nil {
			s.log.Warn("load files failed", "err", err)
		}
		for _, f := range files[ref.InfoHash] {
			if f.Index == ref.FileIndex {
				file = &f
				break
			}
		}
	}

	newerThan := s.now().Add(-s.d.AvailabilityTTL)
	out := make([]Stream, 0, len(providers))
	for _, name := range providers {
		cached := false
		av, err := s.d.Store.GetAvailability(ctx, name, []string{ref.InfoHash}, newerThan)
		if err != nil {
			s.log.Warn("read availability failed", "provider", name, "err", err)
		} else if a, ok := av[ref.InfoHash]; ok {
			cached = a.Cached
		}
		out = append(out, Stream{Provider: name, Cached: cached, Release: *rel, File: file, FileIndex: ref.FileIndex})
	}
	return out, nil
}

// Resolve turns (provider, release, file) into a playable URL with the user's key. Debrid
// errors are returned as they are (callers use errors.Is on debrid.ErrBadKey and friends).
func (s *Service) Resolve(ctx context.Context, cfg userconfig.Config, provider, infoHash string, fileIndex int) (debrid.ResolveResult, error) {
	key := cfg.Debrid[provider]
	p, ok := s.d.Providers.Get(provider)
	if !ok || key == "" {
		return debrid.ResolveResult{}, ErrNoProvider
	}
	if !hashRe.MatchString(infoHash) || fileIndex < AutoFile {
		return debrid.ResolveResult{}, ErrNotFound
	}

	if fileIndex == AutoFile {
		// Unknown files stay AutoFile: the provider picks the largest audio file once it can
		// inspect the torrent. Never guess an index (it could be a cover or a sample).
		if f, ok := s.autoFile(ctx, p, key, infoHash); ok {
			fileIndex = f.Index
		}
	}

	res, err := p.Resolve(ctx, key, debrid.ResolveRequest{
		InfoHash:  infoHash,
		Magnet:    sources.BuildMagnet(infoHash, ""),
		FileIndex: fileIndex,
	})
	if err != nil {
		return debrid.ResolveResult{}, redact(err, key)
	}
	if !res.Pending && res.URL != "" {
		err := s.d.Store.SetAvailability(ctx, provider, []Availability{{InfoHash: infoHash, Cached: true, CheckedAt: s.now()}})
		if err != nil {
			s.log.Warn("store availability failed", "provider", provider, "err", err)
		}
	}
	return res, nil
}

// autoFile finds the largest stored audio file of a torrent; when no files are stored it asks
// the provider's cache check once to learn (and store) them.
func (s *Service) autoFile(ctx context.Context, p debrid.Provider, key, hash string) (model.File, bool) {
	files, err := s.d.Store.Files(ctx, []string{hash})
	if err != nil {
		s.log.Warn("load files failed", "err", err)
	}
	if f, ok := largestAudio(files[hash]); ok {
		return f, true
	}

	var res map[string]debrid.Cached
	err = safely(func() error {
		cctx, cancel := context.WithTimeout(ctx, s.d.CheckTimeout)
		defer cancel()
		var err error
		res, err = p.CheckCached(cctx, key, []string{hash})
		return err
	})
	if err != nil {
		s.log.Warn("cache check failed", "provider", p.Name(), "err", redact(err, key))
		return model.File{}, false
	}
	if res == nil {
		return model.File{}, false
	}
	c, cached := res[hash]
	if err := s.d.Store.SetAvailability(ctx, p.Name(), []Availability{{InfoHash: hash, Cached: cached, CheckedAt: s.now()}}); err != nil {
		s.log.Warn("store availability failed", "provider", p.Name(), "err", err)
	}
	if !cached || len(c.Files) == 0 {
		return model.File{}, false
	}
	if err := s.d.Store.SaveFiles(ctx, hash, c.Files); err != nil {
		s.log.Warn("store files failed", "err", err)
	}
	return largestAudio(c.Files)
}
