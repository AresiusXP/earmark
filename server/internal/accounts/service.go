package accounts

import (
	"context"
	"crypto/hkdf"
	"crypto/sha256"
	"crypto/subtle"
	"errors"
	"fmt"
	"regexp"
	"sync"
	"time"
)

const (
	// AccessTTL is the lifetime of an access token.
	AccessTTL = time.Hour
	// LinkCodeTTL is how long a link code can be used.
	LinkCodeTTL = 15 * time.Minute
	// DefaultInviteTTL is the default lifetime of an invite.
	DefaultInviteTTL = 72 * time.Hour
	// MaxInviteTTL is the longest lifetime an admin can ask for.
	MaxInviteTTL = 720 * time.Hour

	jwtLabel = "audiobooks-jwt-v1"
	// maxCacheTTL bounds how stale the middleware's view of a device may be.
	maxCacheTTL = 5 * time.Second
	maxNote     = 80
)

var uuidRE = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

// Config configures a Service.
type Config struct {
	// Secret is CONFIG_SECRET (>= 32 bytes). The JWT key is derived from it with HKDF.
	Secret string
	// InstancePassword is checked by Bootstrap. Empty means an open instance (the operator set
	// ALLOW_OPEN_CONFIG): the first caller becomes admin.
	InstancePassword string
	Store            Store
	Now              func() time.Time // tests
	CacheTTL         time.Duration    // middleware cache; default and maximum 5 s
}

// Service is the accounts service. Safe for concurrent use.
type Service struct {
	secret []byte
	pwHash [32]byte
	open   bool
	store  Store
	now    func() time.Time

	// bootstrapFails guards the guessable instance password; inviteRate bounds invite creation.
	// The other public endpoints carry >= 128-bit secrets and only get a global request-rate cap.
	bootstrapFails, inviteRate              *failLimiter
	joinCap, linkCap, refreshCap, googleCap *rateCap

	cacheTTL time.Duration
	mu       sync.Mutex
	cache    map[cacheKey]cacheEntry
}

type cacheKey struct{ user, device string }

type cacheEntry struct {
	role  Role
	until time.Time
}

// Tokens is the token pair of a signed-in device.
type Tokens struct {
	AccessToken  string
	RefreshToken string
	ExpiresIn    int // seconds
}

// Session is what bootstrap, join, link and refresh return.
type Session struct {
	Tokens
	User User
}

// New builds a Service.
func New(c Config) (*Service, error) {
	if len(c.Secret) < 32 {
		return nil, errors.New("accounts: secret must be at least 32 bytes")
	}
	if c.Store == nil {
		return nil, errors.New("accounts: store is required")
	}
	key, err := hkdf.Key(sha256.New, []byte(c.Secret), nil, jwtLabel, 32)
	if err != nil {
		return nil, fmt.Errorf("accounts: derive jwt key: %w", err)
	}
	s := &Service{
		secret: key, pwHash: sha256.Sum256([]byte(c.InstancePassword)), open: c.InstancePassword == "",
		store: c.Store, now: c.Now, cacheTTL: c.CacheTTL, cache: map[cacheKey]cacheEntry{},
	}
	if s.now == nil {
		s.now = time.Now
	}
	if s.cacheTTL <= 0 || s.cacheTTL > maxCacheTTL {
		s.cacheTTL = maxCacheTTL
	}
	s.bootstrapFails = newFailLimiter(5, time.Minute, s.now)
	s.inviteRate = newFailLimiter(10, time.Minute, s.now)
	s.refreshCap = newRateCap(20, 100, s.now)
	s.joinCap = newRateCap(5, 20, s.now)
	s.linkCap = newRateCap(5, 20, s.now)
	s.googleCap = newRateCap(5, 20, s.now)
	return s, nil
}

// Bootstrapped reports whether the first admin exists.
func (s *Service) Bootstrapped(ctx context.Context) (bool, error) { return s.store.Bootstrapped(ctx) }

// take reserves a slot of l; the caller refunds the reservation on success.
func take(l *failLimiter) (reservation, error) {
	res, ok, retry := l.acquire()
	if !ok {
		return 0, &LimitError{RetryAfter: retry}
	}
	return res, nil
}

// admit takes a token of a global request-rate cap.
func admit(c *rateCap) error {
	if ok, retry := c.allow(); !ok {
		return &LimitError{RetryAfter: retry}
	}
	return nil
}

// Bootstrap creates the first admin when password is the instance password. ErrAlreadyBootstrapped
// once any user exists.
func (s *Service) Bootstrap(ctx context.Context, password, displayName, deviceName string) (Session, error) {
	displayName, deviceName, err := cleanNames(displayName, deviceName)
	if err != nil {
		return Session{}, err
	}
	res, err := take(s.bootstrapFails)
	if err != nil {
		return Session{}, err
	}
	if !s.open {
		got := sha256.Sum256([]byte(password))
		if subtle.ConstantTimeCompare(got[:], s.pwHash[:]) != 1 {
			return Session{}, ErrBadPassword
		}
	}
	s.bootstrapFails.refund(res)
	refresh, hash, err := newRefresh()
	if err != nil {
		return Session{}, err
	}
	reg, err := s.store.CreateFirstAdmin(ctx, displayName, deviceName, hash, s.now())
	if err != nil {
		return Session{}, err
	}
	return s.issue(reg, refresh)
}

// Join creates a user from a single-use invite code.
func (s *Service) Join(ctx context.Context, inviteCode, displayName, deviceName string) (Session, error) {
	displayName, deviceName, err := cleanNames(displayName, deviceName)
	if err != nil {
		return Session{}, err
	}
	if err := admit(s.joinCap); err != nil {
		return Session{}, err
	}
	refresh, hash, err := newRefresh()
	if err != nil {
		return Session{}, err
	}
	reg, err := s.store.RedeemInvite(ctx, hashCode(inviteCode), displayName, deviceName, hash, s.now())
	if err != nil {
		return Session{}, err
	}
	return s.issue(reg, refresh)
}

// Link adds a device to an existing user with a single-use link code.
func (s *Service) Link(ctx context.Context, linkCode, deviceName string) (Session, error) {
	deviceName, err := cleanName(deviceName, "device_name", 80)
	if err != nil {
		return Session{}, err
	}
	if err := admit(s.linkCap); err != nil {
		return Session{}, err
	}
	refresh, hash, err := newRefresh()
	if err != nil {
		return Session{}, err
	}
	reg, err := s.store.RedeemLinkCode(ctx, hashCode(linkCode), deviceName, hash, s.now())
	if err != nil {
		return Session{}, err
	}
	return s.issue(reg, refresh)
}

// Refresh rotates the refresh token and returns a fresh pair. A token that was already rotated
// (or belongs to a revoked device or disabled user) is ErrBadRefresh.
func (s *Service) Refresh(ctx context.Context, refreshToken string) (Session, error) {
	old, ok := hashRefresh(refreshToken)
	if !ok {
		return Session{}, ErrBadRefresh
	}
	if err := admit(s.refreshCap); err != nil {
		return Session{}, err
	}
	refresh, hash, err := newRefresh()
	if err != nil {
		return Session{}, err
	}
	reg, ok, err := s.store.RotateRefresh(ctx, old, hash, s.now())
	if err != nil {
		return Session{}, err
	}
	if !ok {
		return Session{}, ErrBadRefresh
	}
	return s.issue(reg, refresh)
}

func (s *Service) issue(reg Registered, refresh string) (Session, error) {
	now := s.now()
	access, err := sign(s.secret, Claims{Sub: reg.User.ID, Dev: reg.DeviceID, Iat: now.Unix(), Exp: now.Add(AccessTTL).Unix()})
	if err != nil {
		return Session{}, err
	}
	return Session{Tokens: Tokens{AccessToken: access, RefreshToken: refresh, ExpiresIn: int(AccessTTL.Seconds())}, User: reg.User}, nil
}

func cleanNames(displayName, deviceName string) (string, string, error) {
	d, err := cleanName(displayName, "display_name", 40)
	if err != nil {
		return "", "", err
	}
	dev, err := cleanName(deviceName, "device_name", 80)
	if err != nil {
		return "", "", err
	}
	return d, dev, nil
}

// Authenticate validates an access token, then checks that the device is not revoked and the
// user is not disabled (answers are cached for at most five seconds; revoking or disabling
// through this Service drops the cache at once).
func (s *Service) Authenticate(ctx context.Context, token string) (Principal, error) {
	c, err := verify(s.secret, token, s.now())
	if err != nil {
		return Principal{}, ErrInvalidToken
	}
	key := cacheKey{c.Sub, c.Dev}
	now := s.now()
	s.mu.Lock()
	e, hit := s.cache[key]
	s.mu.Unlock()
	if hit && now.Before(e.until) {
		return Principal{UserID: c.Sub, DeviceID: c.Dev, Role: e.role}, nil
	}
	if !uuidRE.MatchString(c.Sub) || !uuidRE.MatchString(c.Dev) {
		return Principal{}, ErrInvalidToken
	}
	role, ok, err := s.store.AuthState(ctx, c.Sub, c.Dev, now)
	if err != nil {
		return Principal{}, err
	}
	if !ok {
		s.mu.Lock()
		delete(s.cache, key)
		s.mu.Unlock()
		return Principal{}, ErrUnauthorized
	}
	s.mu.Lock()
	if len(s.cache) >= 4096 {
		clear(s.cache)
	}
	s.cache[key] = cacheEntry{role: role, until: now.Add(s.cacheTTL)}
	s.mu.Unlock()
	return Principal{UserID: c.Sub, DeviceID: c.Dev, Role: role}, nil
}

func (s *Service) forget(user, device string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if device != "" {
		delete(s.cache, cacheKey{user, device})
		return
	}
	for k := range s.cache {
		if k.user == user {
			delete(s.cache, k)
		}
	}
}

// ---- the caller ----------------------------------------------------------------------------

// Me returns the user.
func (s *Service) Me(ctx context.Context, userID string) (User, error) {
	return s.store.GetUser(ctx, userID)
}

// UpdateMe applies a partial update of the display name and settings.
func (s *Service) UpdateMe(ctx context.Context, userID string, displayName *string, patch SettingsPatch) (User, error) {
	var name string
	if displayName != nil {
		n, err := cleanName(*displayName, "display_name", 40)
		if err != nil {
			return User{}, err
		}
		name = n
	}
	return s.store.UpdateUser(ctx, userID, func(u *User) error {
		next, err := patch.Apply(u.Settings)
		if err != nil {
			return err
		}
		u.Settings = next
		if displayName != nil {
			u.DisplayName = name
		}
		return nil
	})
}

// Devices lists the caller's devices; Current marks the one that made the request.
func (s *Service) Devices(ctx context.Context, p Principal) ([]Device, error) {
	ds, err := s.store.ListDevices(ctx, p.UserID)
	if err != nil {
		return nil, err
	}
	for i := range ds {
		ds[i].Current = ds[i].ID == p.DeviceID
	}
	return ds, nil
}

// RevokeDevice revokes one of the caller's devices (it may be the current one). Idempotent;
// ErrNotFound for a device that is not the caller's.
func (s *Service) RevokeDevice(ctx context.Context, p Principal, deviceID string) error {
	if !uuidRE.MatchString(deviceID) {
		return ErrNotFound
	}
	if err := s.store.RevokeDevice(ctx, p.UserID, deviceID, s.now()); err != nil {
		return err
	}
	s.forget(p.UserID, deviceID)
	return nil
}

// CreateLinkCode makes a single-use code that signs in another device as the caller.
func (s *Service) CreateLinkCode(ctx context.Context, p Principal) (code string, expires time.Time, err error) {
	code, err = newCode(linkCodeLen)
	if err != nil {
		return "", time.Time{}, err
	}
	now := s.now()
	expires = now.Add(LinkCodeTTL)
	if err := s.store.CreateLinkCode(ctx, p.UserID, hashCode(code), now, expires); err != nil {
		return "", time.Time{}, err
	}
	return code, expires, nil
}

// ---- admin ---------------------------------------------------------------------------------

func requireAdmin(p Principal) error {
	if p.Role != RoleAdmin {
		return ErrForbidden
	}
	return nil
}

// ListUsers lists every user (admin only).
func (s *Service) ListUsers(ctx context.Context, p Principal) ([]AdminUser, error) {
	if err := requireAdmin(p); err != nil {
		return nil, err
	}
	return s.store.ListUsers(ctx)
}

// SetDisabled disables or re-enables a user (admin only). A disabled user's access tokens and
// refresh tokens stop working at once; the devices stay, so re-enabling restores them.
func (s *Service) SetDisabled(ctx context.Context, p Principal, userID string, disabled bool) (AdminUser, error) {
	if err := requireAdmin(p); err != nil {
		return AdminUser{}, err
	}
	if !uuidRE.MatchString(userID) {
		return AdminUser{}, ErrNotFound
	}
	if disabled && userID == p.UserID {
		return AdminUser{}, ErrCannotDisableSelf
	}
	u, err := s.store.SetDisabled(ctx, p.UserID, userID, disabled)
	if err != nil {
		return AdminUser{}, err
	}
	s.forget(userID, "")
	return u, nil
}

// CreateInvite makes a single-use invite (admin only) and returns the one-time code.
func (s *Service) CreateInvite(ctx context.Context, p Principal, note string, ttl time.Duration) (Invite, string, error) {
	if err := requireAdmin(p); err != nil {
		return Invite{}, "", err
	}
	if n := len([]rune(note)); n > maxNote {
		return Invite{}, "", &ValidationError{"note must be at most 80 characters"}
	}
	switch {
	case ttl == 0:
		ttl = DefaultInviteTTL
	case ttl < time.Hour || ttl > MaxInviteTTL:
		return Invite{}, "", &ValidationError{"expires_in_hours must be 1-720"}
	}
	if _, err := take(s.inviteRate); err != nil {
		return Invite{}, "", err
	}
	code, err := newCode(inviteCodeLen)
	if err != nil {
		return Invite{}, "", err
	}
	now := s.now()
	inv, err := s.store.CreateInvite(ctx, p.UserID, hashCode(code), note, now, now.Add(ttl))
	if err != nil {
		return Invite{}, "", err
	}
	return inv, code, nil
}

// ListInvites lists open invites (admin only).
func (s *Service) ListInvites(ctx context.Context, p Principal) ([]Invite, error) {
	if err := requireAdmin(p); err != nil {
		return nil, err
	}
	return s.store.ListOpenInvites(ctx, s.now())
}

// RevokeInvite deletes an unused invite (admin only, idempotent).
func (s *Service) RevokeInvite(ctx context.Context, p Principal, id string) error {
	if err := requireAdmin(p); err != nil {
		return err
	}
	return s.store.DeleteInvite(ctx, p.UserID, id)
}
