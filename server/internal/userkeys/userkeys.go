// Package userkeys stores each user's own debrid API keys: sealed at rest (AES-256-GCM, key
// derived from CONFIG_SECRET), validated against the provider before they are stored, never
// returned and never logged. GetForUse is the only way to read a key back and is meant for the
// server's own debrid calls: it returns exactly the requesting user's key, with no fallback to
// anybody else's.
package userkeys

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"time"
	"unicode"

	"github.com/AresiusXP/earmark/server/internal/debrid"
)

// Key states, as in the contract's DebridStatus.
const (
	StateOK      = "ok"
	StateInvalid = "invalid"
	StateError   = "error"
)

const (
	minKeyLen       = 8
	maxKeyLen       = 256
	validateTimeout = 20 * time.Second
)

// Errors returned by the Service.
var (
	ErrUnknownProvider = errors.New("userkeys: unknown provider")
	ErrInvalidKey      = errors.New("userkeys: api key must be 8-256 characters without spaces")
	ErrBadKey          = errors.New("userkeys: the provider rejected the api key")
	ErrUnavailable     = errors.New("userkeys: the provider could not be reached")
	ErrNoKey           = errors.New("userkeys: no key stored for this provider")
	ErrUnreadable      = errors.New("userkeys: the stored key cannot be read")
)

// Record is a stored key row. SealedKey never leaves this package and the store.
type Record struct {
	UserID      string
	Provider    string
	SealedKey   string
	Fingerprint string
	State       string
	ValidatedAt *time.Time
	LastError   string
	// SharedWithOthers is set by ListKeys: another user holds the same key of the same provider (same current-format fingerprint).
	SharedWithOthers bool
}

// Store is the persistence of the Service (implemented by store.Postgres). Every method is
// scoped by the user id it receives.
type Store interface {
	// PutKey inserts or replaces the user's key for the provider.
	PutKey(ctx context.Context, r Record) error
	// GetKey returns the user's key row; found is false when there is none.
	GetKey(ctx context.Context, userID, provider string) (r Record, found bool, err error)
	ListKeys(ctx context.Context, userID string) ([]Record, error)
	// DeleteKey removes the key (idempotent).
	DeleteKey(ctx context.Context, userID, provider string) error
	// SetState updates the status of an existing key; false when there is no such key. When
	// validatedAt is nil the previous value stays.
	SetState(ctx context.Context, userID, provider, state string, validatedAt *time.Time, lastError string) (bool, error)
}

// Config configures a Service.
type Config struct {
	Secret    string // CONFIG_SECRET
	Store     Store
	Providers debrid.Registry
	Logger    *slog.Logger
	Now       func() time.Time // tests
}

// Service manages user keys. Safe for concurrent use.
type Service struct {
	sealer    *Sealer
	store     Store
	providers debrid.Registry
	log       *slog.Logger
	now       func() time.Time
}

// New builds a Service.
func New(c Config) (*Service, error) {
	sl, err := NewSealer(c.Secret)
	if err != nil {
		return nil, err
	}
	if c.Store == nil {
		return nil, errors.New("userkeys: store is required")
	}
	s := &Service{sealer: sl, store: c.Store, providers: c.Providers, log: c.Logger, now: c.Now}
	if s.now == nil {
		s.now = time.Now
	}
	if s.log == nil {
		s.log = slog.Default()
	}
	return s, nil
}

// Providers returns the supported provider names, sorted.
func (s *Service) Providers() []string { return s.providers.Names() }

// Status is a key's status as the API shows it. It never carries the key.
type Status struct {
	Provider         string
	Configured       bool
	State            string // ok | invalid | error; empty when not configured
	ValidatedAt      *time.Time
	LastError        string
	SharedWithOthers bool
}

func statusOf(r Record) Status {
	return Status{
		Provider: r.Provider, Configured: true, State: r.State, ValidatedAt: r.ValidatedAt,
		LastError: r.LastError, SharedWithOthers: r.SharedWithOthers,
	}
}

func (s *Service) provider(name string) (debrid.Provider, error) {
	p, ok := s.providers.Get(name)
	if !ok {
		return nil, ErrUnknownProvider
	}
	return p, nil
}

// validate asks the provider and maps its answer to ErrBadKey or ErrUnavailable. The provider's
// own error text is not kept: it may echo request details.
func (s *Service) validate(ctx context.Context, p debrid.Provider, key string) error {
	ctx, cancel := context.WithTimeout(ctx, validateTimeout)
	defer cancel()
	err := p.Validate(ctx, key)
	switch {
	case err == nil:
		return nil
	case errors.Is(err, debrid.ErrBadKey):
		return ErrBadKey
	default:
		s.log.Warn("debrid key validation failed", "provider", p.Name(), "kind", errKind(err))
		return ErrUnavailable
	}
}

func errKind(err error) string {
	switch {
	case errors.Is(err, context.DeadlineExceeded):
		return "timeout"
	case errors.Is(err, context.Canceled):
		return "canceled"
	case errors.Is(err, debrid.ErrRateLimited):
		return "rate_limited"
	}
	return "other"
}

// CleanKey trims apiKey and checks its shape (8-256 printable characters, no spaces).
func CleanKey(apiKey string) (string, error) {
	k := strings.TrimSpace(apiKey)
	if len(k) < minKeyLen || len(k) > maxKeyLen {
		return "", ErrInvalidKey
	}
	for _, r := range k {
		if unicode.IsSpace(r) || unicode.IsControl(r) {
			return "", ErrInvalidKey
		}
	}
	return k, nil
}

// Put validates the key with the provider and, only when accepted, stores it sealed, replacing
// any earlier key of the user for that provider. ErrBadKey when the provider rejects it,
// ErrUnavailable when the provider cannot be asked (nothing is stored then).
func (s *Service) Put(ctx context.Context, userID, provider, apiKey string) (Status, error) {
	p, err := s.provider(provider)
	if err != nil {
		return Status{}, err
	}
	key, err := CleanKey(apiKey)
	if err != nil {
		return Status{}, err
	}
	if err := s.validate(ctx, p, key); err != nil {
		return Status{}, err
	}
	sealed, err := s.sealer.Seal(userID, provider, key)
	if err != nil {
		return Status{}, err
	}
	now := s.now()
	rec := Record{
		UserID: userID, Provider: provider, SealedKey: sealed, Fingerprint: s.sealer.Fingerprint(provider, key),
		State: StateOK, ValidatedAt: &now,
	}
	if err := s.store.PutKey(ctx, rec); err != nil {
		return Status{}, err
	}
	return s.statusFor(ctx, userID, provider)
}

func (s *Service) statusFor(ctx context.Context, userID, provider string) (Status, error) {
	recs, err := s.store.ListKeys(ctx, userID)
	if err != nil {
		return Status{}, err
	}
	for _, r := range recs {
		if r.Provider == provider {
			return statusOf(r), nil
		}
	}
	return Status{Provider: provider}, nil
}

// List returns one Status per supported provider, in provider-name order.
func (s *Service) List(ctx context.Context, userID string) ([]Status, error) {
	recs, err := s.store.ListKeys(ctx, userID)
	if err != nil {
		return nil, err
	}
	by := make(map[string]Record, len(recs))
	for _, r := range recs {
		by[r.Provider] = r
	}
	names := s.providers.Names()
	out := make([]Status, 0, len(names))
	for _, n := range names {
		if r, ok := by[n]; ok {
			out = append(out, statusOf(r))
		} else {
			out = append(out, Status{Provider: n})
		}
	}
	return out, nil
}

// Delete removes the user's key (idempotent).
func (s *Service) Delete(ctx context.Context, userID, provider string) error {
	if _, err := s.provider(provider); err != nil {
		return err
	}
	return s.store.DeleteKey(ctx, userID, provider)
}

// Recheck validates the stored key again and records the outcome: ok, invalid (the provider
// rejected it) or error (the provider could not be asked; the key may still be fine). ErrNoKey
// when the user has none.
func (s *Service) Recheck(ctx context.Context, userID, provider string) (Status, error) {
	p, err := s.provider(provider)
	if err != nil {
		return Status{}, err
	}
	rec, found, err := s.store.GetKey(ctx, userID, provider)
	if err != nil {
		return Status{}, err
	}
	if !found {
		return Status{}, ErrNoKey
	}
	key, err := s.sealer.Open(userID, provider, rec.SealedKey)
	if err != nil {
		if _, e := s.store.SetState(ctx, userID, provider, StateInvalid, nil, "stored key cannot be read, set it again"); e != nil {
			return Status{}, e
		}
		return s.statusFor(ctx, userID, provider)
	}
	s.healFingerprint(ctx, rec, key)
	now := s.now()
	switch verr := s.validate(ctx, p, key); {
	case verr == nil:
		_, err = s.store.SetState(ctx, userID, provider, StateOK, &now, "")
	case errors.Is(verr, ErrBadKey):
		_, err = s.store.SetState(ctx, userID, provider, StateInvalid, &now, "the provider rejected the key")
	default:
		_, err = s.store.SetState(ctx, userID, provider, StateError, nil, "the provider could not be reached")
	}
	if err != nil {
		return Status{}, err
	}
	return s.statusFor(ctx, userID, provider)
}

// MarkInvalid records that a debrid call made with the user's key was rejected by the provider.
func (s *Service) MarkInvalid(ctx context.Context, userID, provider string) error {
	now := s.now()
	_, err := s.store.SetState(ctx, userID, provider, StateInvalid, &now, "the provider rejected the key")
	return err
}

// Key is a user's plaintext debrid key. It prints as "[redacted]" in logs and fmt output; read
// it with APIKey only at the moment of the provider call.
type Key struct {
	Provider string
	// Fingerprint identifies the underlying account (equal for users sharing one key); use it to
	// key budgets and cooldowns.
	Fingerprint string
	secret      string
}

// APIKey returns the plaintext key.
func (k Key) APIKey() string { return k.secret }

func (k Key) String() string               { return "[redacted]" }
func (k Key) GoString() string             { return "userkeys.Key{[redacted]}" }
func (k Key) LogValue() slog.Value         { return slog.StringValue("[redacted]") }
func (k Key) MarshalJSON() ([]byte, error) { return []byte(`"[redacted]"`), nil }
func (k Key) MarshalText() ([]byte, error) { return []byte("[redacted]"), nil }
func (k Key) Format(f fmt.State, _ rune)   { _, _ = f.Write([]byte("[redacted]")) }

// GetForUse returns the plaintext key of exactly this user for the provider. ErrNoKey when the
// user has none, ErrBadKey when it is known to be rejected (the caller answers bad_debrid_key),
// ErrUnreadable when it cannot be decrypted (CONFIG_SECRET changed). A key in state "error"
// (provider unreachable at the last check) is still returned. Internal use only.
func (s *Service) GetForUse(ctx context.Context, userID, provider string) (Key, error) {
	if _, err := s.provider(provider); err != nil {
		return Key{}, err
	}
	rec, found, err := s.store.GetKey(ctx, userID, provider)
	if err != nil {
		return Key{}, err
	}
	if !found {
		return Key{}, ErrNoKey
	}
	if rec.State == StateInvalid {
		return Key{}, ErrBadKey
	}
	plain, err := s.sealer.Open(userID, provider, rec.SealedKey)
	if err != nil {
		return Key{}, ErrUnreadable
	}
	fp := s.healFingerprint(ctx, rec, plain)
	return Key{Provider: provider, Fingerprint: fp, secret: plain}, nil
}

// healFingerprint returns the current-format fingerprint of the opened key and, when the stored
// one is in an older format, rewrites the row (best effort: a failure only means the row is
// healed next time; the returned value is right either way).
func (s *Service) healFingerprint(ctx context.Context, rec Record, plain string) string {
	fp := s.sealer.Fingerprint(rec.Provider, plain)
	if rec.Fingerprint != fp {
		rec.Fingerprint = fp
		_ = s.store.PutKey(ctx, rec)
	}
	return fp
}
