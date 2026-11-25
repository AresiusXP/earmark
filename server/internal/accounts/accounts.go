// Package accounts implements the users of the instance: bootstrap with the instance password,
// invites, devices with rotating refresh tokens, link codes and the access-token middleware
// (docs/DESIGN.md "App direction", contract in api/openapi.yaml).
//
// Secrets: invite codes, link codes and refresh tokens exist in clear only in the response that
// creates them; the database holds their SHA-256. Access tokens are HS256 JWTs signed with a key
// derived from CONFIG_SECRET (HKDF, own label); the middleware also checks the device and the user
// row, so revoking a device or disabling a user takes effect at once.
package accounts

import (
	"context"
	"errors"
	"time"
)

// Role is a user's role.
type Role string

const (
	RoleAdmin Role = "admin"
	RoleUser  Role = "user"
)

// Errors returned by the Service. Handlers map them to the contract's error codes.
var (
	ErrBadPassword         = errors.New("accounts: wrong instance password")
	ErrInvalidCode         = errors.New("accounts: unknown, used or expired code")
	ErrBadRefresh          = errors.New("accounts: invalid refresh token")
	ErrTooManyAttempts     = errors.New("accounts: too many failed attempts")
	ErrAlreadyBootstrapped = errors.New("accounts: instance already has users")
	ErrForbidden           = errors.New("accounts: admin only")
	ErrNotFound            = errors.New("accounts: not found")
	ErrCannotDisableSelf   = errors.New("accounts: you cannot disable yourself")
	ErrLastAdmin           = errors.New("accounts: you cannot disable the last admin")
	ErrInvalidToken        = errors.New("accounts: invalid access token")
	ErrUnauthorized        = errors.New("accounts: unauthorized")
)

// ValidationError is a rejected input (HTTP 400); Msg is safe to show to the caller.
type ValidationError struct{ Msg string }

func (e *ValidationError) Error() string { return "accounts: " + e.Msg }

// LimitError is returned (wrapping ErrTooManyAttempts) when a global limiter refuses.
type LimitError struct{ RetryAfter time.Duration }

func (e *LimitError) Error() string { return ErrTooManyAttempts.Error() }
func (e *LimitError) Unwrap() error { return ErrTooManyAttempts }

// Settings are the user's own choices, stored with the account.
type Settings struct {
	Region            string
	Languages         []string
	PreferFormat      string  // "", "m4b", "mp3"
	PreferredProvider *string // nil = none
	SourceMode        string  // auto | debrid | p2p
	P2PEnabled        bool
}

// DefaultSettings are the settings of a new account.
func DefaultSettings() Settings {
	return Settings{Region: "us", Languages: []string{"en"}, SourceMode: "auto"}
}

// User is an account.
type User struct {
	ID          string
	DisplayName string
	Role        Role
	Disabled    bool
	CreatedAt   time.Time
	LastSeenAt  *time.Time
	Settings    Settings
}

// AdminUser is a user as the admin list shows it.
type AdminUser struct {
	User
	DeviceCount int
	// Debrid maps provider to "has a key" for the providers the user holds a key for; the
	// caller adds false entries for the rest. Never the key.
	Debrid map[string]bool
}

// Device is one signed-in device of a user.
type Device struct {
	ID         string
	Name       string
	CreatedAt  time.Time
	LastSeenAt *time.Time
	Current    bool
}

// Invite is an unused, unexpired invite. The code is only known at creation.
type Invite struct {
	ID        string
	Note      string
	CreatedAt time.Time
	ExpiresAt time.Time
}

// Registered is a user together with the device a sign-in created or rotated.
type Registered struct {
	User     User
	DeviceID string
}

// Principal is the authenticated caller; see PrincipalFrom.
type Principal struct {
	UserID   string
	DeviceID string
	Role     Role
}

type ctxKey struct{}

// PrincipalFrom returns the caller set by Middleware.
func PrincipalFrom(ctx context.Context) (Principal, bool) {
	p, ok := ctx.Value(ctxKey{}).(Principal)
	return p, ok
}

// WithPrincipal returns ctx carrying p (for tests of packages that sit behind the middleware).
func WithPrincipal(ctx context.Context, p Principal) context.Context {
	return context.WithValue(ctx, ctxKey{}, p)
}

// Store is the persistence of the Service (implemented by store.Postgres). Every method that
// touches a per-user row is scoped by the user id it receives. "Not found" is ErrNotFound unless
// a method says otherwise.
type Store interface {
	// Bootstrapped reports whether any user exists.
	Bootstrapped(ctx context.Context) (bool, error)
	// CreateFirstAdmin creates the admin and its first device, atomically and only while no user
	// exists (ErrAlreadyBootstrapped otherwise, even under concurrent calls).
	CreateFirstAdmin(ctx context.Context, displayName, deviceName string, refreshHash []byte, now time.Time) (Registered, error)

	CreateInvite(ctx context.Context, createdBy string, codeHash []byte, note string, now, expires time.Time) (Invite, error)
	ListOpenInvites(ctx context.Context, now time.Time) ([]Invite, error)
	// DeleteInvite removes an unused invite; it is idempotent (no error when absent or used).
	DeleteInvite(ctx context.Context, id string) error
	// RedeemInvite marks the invite used and creates the user and its first device in one
	// transaction; ErrInvalidCode when the code is unknown, used or expired.
	RedeemInvite(ctx context.Context, codeHash []byte, displayName, deviceName string, refreshHash []byte, now time.Time) (Registered, error)

	// CreateLinkCode stores a new code for userID and drops the user's earlier unused ones.
	CreateLinkCode(ctx context.Context, userID string, codeHash []byte, now, expires time.Time) error
	// RedeemLinkCode marks the code used and creates a device for its (enabled) user;
	// ErrInvalidCode when unknown, used, expired or the user is disabled.
	RedeemLinkCode(ctx context.Context, codeHash []byte, deviceName string, refreshHash []byte, now time.Time) (Registered, error)

	// RotateRefresh atomically replaces the refresh hash of a non-revoked device of an enabled
	// user. ok is false for an unknown, already rotated or revoked token.
	RotateRefresh(ctx context.Context, oldHash, newHash []byte, now time.Time) (r Registered, ok bool, err error)
	// AuthState returns the role when the device exists, is not revoked, belongs to userID and
	// the user is enabled; ok is false otherwise. It also refreshes last_seen_at (best effort).
	AuthState(ctx context.Context, userID, deviceID string, now time.Time) (role Role, ok bool, err error)

	GetUser(ctx context.Context, id string) (User, error)
	// UpdateUser applies mutate to the user row under a row lock and stores the result.
	UpdateUser(ctx context.Context, id string, mutate func(*User) error) (User, error)
	ListDevices(ctx context.Context, userID string) ([]Device, error)
	// RevokeDevice revokes one of userID's devices (idempotent); ErrNotFound when the device
	// does not exist or belongs to someone else.
	RevokeDevice(ctx context.Context, userID, deviceID string, now time.Time) error

	ListUsers(ctx context.Context) ([]AdminUser, error)
	// SetDisabled flips the disabled flag. ErrCannotDisableSelf and ErrLastAdmin are enforced
	// here, under a lock, so concurrent requests cannot leave the instance without an admin.
	SetDisabled(ctx context.Context, actorID, targetID string, disabled bool) (AdminUser, error)
}
