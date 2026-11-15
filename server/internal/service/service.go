package service

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/sources"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
)

// Default tunables, used when the matching Deps field is zero.
const (
	DefaultReleaseTTL      = 12 * time.Hour
	DefaultEmptyReleaseTTL = time.Hour
	DefaultBookTTL         = 30 * 24 * time.Hour
	DefaultAvailabilityTTL = 6 * time.Hour
	DefaultMetadataTTL     = 10 * time.Minute
	DefaultMetadataEntries = 256
	DefaultPrepareTimeout  = 40 * time.Second
	DefaultCheckTimeout    = 15 * time.Second
	DefaultValidateTimeout = 10 * time.Second

	// maxFilesPerRelease caps the videos a book page exposes for one release (Stremio
	// handles a few hundred episodes; 399 keeps the meta response bounded).
	maxFilesPerRelease = 399
)

// Metadata is the part of *metadata.Client the service uses.
type Metadata interface {
	Search(ctx context.Context, region, language, query string) ([]model.Book, error)
	Discover(ctx context.Context, region, language string, page int) ([]model.Book, error)
	Book(ctx context.Context, region, asin string) (model.Book, error)
}

// Deps are the collaborators and tunables of Service. Zero tunables take the Default* values.
type Deps struct {
	Metadata  Metadata
	Sources   []sources.Source
	Store     Store
	Providers debrid.Registry
	Clock     func() time.Time
	Logger    *slog.Logger

	ReleaseTTL      time.Duration // release search freshness (default 12h)
	EmptyReleaseTTL time.Duration // same, when the last search found no candidates (default 1h)
	BookTTL         time.Duration // stored book metadata freshness (default 30d)
	AvailabilityTTL time.Duration // debrid cache state freshness (default 6h)
	MetadataTTL     time.Duration // in-memory Search/Discover cache (default 10min)
	MetadataEntries int           // bound of that cache (default 256)

	PrepareTimeout  time.Duration // detached book refresh + release search (default 40s)
	CheckTimeout    time.Duration // one provider CheckCached call (default 15s)
	ValidateTimeout time.Duration // one provider Validate call (default 10s)
}

// Service implements API.
type Service struct {
	d       Deps
	log     *slog.Logger
	catalog *bookCache
	flights flightGroup[model.Book]
}

var _ API = (*Service)(nil)

// New builds a Service. Metadata and Store are required.
func New(deps Deps) *Service {
	if deps.Clock == nil {
		deps.Clock = time.Now
	}
	if deps.Logger == nil {
		deps.Logger = slog.New(slog.DiscardHandler)
	}
	def := func(v *time.Duration, d time.Duration) {
		if *v <= 0 {
			*v = d
		}
	}
	def(&deps.ReleaseTTL, DefaultReleaseTTL)
	def(&deps.EmptyReleaseTTL, DefaultEmptyReleaseTTL)
	def(&deps.BookTTL, DefaultBookTTL)
	def(&deps.AvailabilityTTL, DefaultAvailabilityTTL)
	def(&deps.MetadataTTL, DefaultMetadataTTL)
	def(&deps.PrepareTimeout, DefaultPrepareTimeout)
	def(&deps.CheckTimeout, DefaultCheckTimeout)
	def(&deps.ValidateTimeout, DefaultValidateTimeout)
	if deps.MetadataEntries <= 0 {
		deps.MetadataEntries = DefaultMetadataEntries
	}
	return &Service{
		d:       deps,
		log:     deps.Logger,
		catalog: newBookCache(deps.MetadataEntries, deps.Clock),
	}
}

func (s *Service) now() time.Time { return s.d.Clock() }

// Providers lists the supported provider names, sorted.
func (s *Service) Providers() []string { return s.d.Providers.Names() }

// ValidateKeys validates every key concurrently against its provider.
func (s *Service) ValidateKeys(ctx context.Context, keys map[string]string) map[string]error {
	out := map[string]error{}
	var mu sync.Mutex
	var wg sync.WaitGroup
	for name, key := range keys {
		p, ok := s.d.Providers.Get(name)
		if !ok {
			mu.Lock()
			out[name] = fmt.Errorf("service: unknown provider %q", name)
			mu.Unlock()
			continue
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			err := safely(func() error {
				vctx, cancel := context.WithTimeout(ctx, s.d.ValidateTimeout)
				defer cancel()
				return p.Validate(vctx, key)
			})
			if err != nil {
				mu.Lock()
				out[name] = redact(err, key)
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	return out
}

// configuredProviders returns the providers the user configured a key for and that the
// registry knows, sorted by name.
func (s *Service) configuredProviders(cfg userconfig.Config) []string {
	var names []string
	for _, n := range s.d.Providers.Names() {
		if cfg.Debrid[n] != "" {
			names = append(names, n)
		}
	}
	return names
}

// safely runs fn and turns a panic into an error.
func safely(fn func() error) (err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("service: panic: %v", r)
		}
	}()
	return fn()
}

// redactedError hides an API key that a provider error message would otherwise leak while
// keeping errors.Is / errors.As working. It deliberately has no Unwrap method, so walking the
// chain never reaches the original message.
type redactedError struct {
	msg string
	err error
}

func (e *redactedError) Error() string        { return e.msg }
func (e *redactedError) Is(target error) bool { return errors.Is(e.err, target) }
func (e *redactedError) As(target any) bool   { return errors.As(e.err, target) }

// redact returns err unchanged unless its message contains key.
func redact(err error, key string) error {
	if err == nil || key == "" {
		return err
	}
	msg := err.Error()
	for _, k := range []string{key, url.QueryEscape(key), url.PathEscape(key)} {
		msg = strings.ReplaceAll(msg, k, "[redacted]")
	}
	if msg == err.Error() {
		return err
	}
	return &redactedError{msg: msg, err: err}
}
