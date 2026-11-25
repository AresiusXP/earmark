package accounts

import (
	"context"
	"errors"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"

	"github.com/AresiusXP/earmark/server/internal/googleid"
)

// GoogleVerifier verifies Google ID tokens and mints their nonces (implemented by
// googleid.Verifier). The tokens only establish identity: Google never creates a user here.
type GoogleVerifier interface {
	NewNonce() (nonce string, expires time.Time, err error)
	Verify(ctx context.Context, idToken string) (googleid.Identity, error)
}

// The rules of Sign in with Google (docs/DESIGN.md):
//   - A new user always needs an invite (GoogleJoin = invite + ID token); the first admin comes
//     from the instance password and can link Google afterwards.
//   - One Google identity belongs to at most one user, a user links at most one identity.
//   - A disabled user cannot sign in with Google.
//   - Unlinking is always allowed: the caller is signed in on a device, and devices, link codes
//     and refresh tokens do not depend on Google, so Google is never the only way in. (A user
//     whose every device is gone and who unlinked Google needs an admin invite, exactly like a
//     user who never linked it.)
//   - The nonce of a token is spent as soon as the token verified; a later failure (unknown
//     invite, identity already taken) needs a fresh nonce and a fresh token.

// GoogleEnabled reports whether Sign in with Google is configured.
func (s *Service) GoogleEnabled() bool { return s.google != nil }

// GoogleNonce returns a single-use nonce (valid 5 minutes) the app passes to Google when it
// asks for the ID token.
func (s *Service) GoogleNonce() (string, time.Time, error) {
	if s.google == nil {
		return "", time.Time{}, ErrGoogleDisabled
	}
	n, exp, err := s.google.NewNonce()
	if err != nil {
		return "", time.Time{}, err
	}
	return n, exp, nil
}

// verifyGoogle takes a token of the Google rate cap and verifies the ID token. The token never
// appears in an error.
func (s *Service) verifyGoogle(ctx context.Context, idToken string) (GoogleIdentity, googleid.Identity, error) {
	if s.google == nil {
		return GoogleIdentity{}, googleid.Identity{}, ErrGoogleDisabled
	}
	if err := admit(s.googleCap); err != nil {
		return GoogleIdentity{}, googleid.Identity{}, err
	}
	id, err := s.google.Verify(ctx, idToken)
	switch {
	case err == nil:
		return GoogleIdentity{Sub: id.Sub, Email: id.Email}, id, nil
	case errors.Is(err, googleid.ErrInvalidToken):
		return GoogleIdentity{}, googleid.Identity{}, ErrGoogleToken
	case errors.Is(err, googleid.ErrBusy):
		return GoogleIdentity{}, googleid.Identity{}, &LimitError{RetryAfter: time.Minute}
	case errors.Is(err, googleid.ErrUnavailable):
		return GoogleIdentity{}, googleid.Identity{}, ErrGoogleUnavailable
	}
	return GoogleIdentity{}, googleid.Identity{}, err
}

// GoogleSignIn signs a device in as the user the Google identity is linked to. ErrGoogleNotLinked
// for an unknown identity (the app then offers "join with an invite"), ErrUnauthorized for a
// disabled user.
func (s *Service) GoogleSignIn(ctx context.Context, idToken, deviceName string) (Session, error) {
	if s.google == nil {
		return Session{}, ErrGoogleDisabled
	}
	deviceName, err := cleanName(deviceName, "device_name", 80)
	if err != nil {
		return Session{}, err
	}
	g, _, err := s.verifyGoogle(ctx, idToken)
	if err != nil {
		return Session{}, err
	}
	refresh, hash, err := newRefresh()
	if err != nil {
		return Session{}, err
	}
	reg, err := s.store.GoogleSignIn(ctx, g, deviceName, hash, s.now())
	if err != nil {
		return Session{}, err
	}
	return s.issue(reg, refresh)
}

// GoogleJoin creates a user from an invite code and links the Google identity, atomically. The
// display name defaults to the Google given name (sanitised, at most 40 characters).
func (s *Service) GoogleJoin(ctx context.Context, idToken, inviteCode, displayName, deviceName string) (Session, error) {
	if s.google == nil {
		return Session{}, ErrGoogleDisabled
	}
	deviceName, err := cleanName(deviceName, "device_name", 80)
	if err != nil {
		return Session{}, err
	}
	explicit := strings.TrimSpace(displayName) != ""
	if explicit {
		if displayName, err = cleanName(displayName, "display_name", 40); err != nil {
			return Session{}, err
		}
	}
	g, id, err := s.verifyGoogle(ctx, idToken)
	if err != nil {
		return Session{}, err
	}
	if !explicit {
		displayName = googleDisplayName(id)
	}
	if err := admit(s.joinCap); err != nil {
		return Session{}, err
	}
	refresh, hash, err := newRefresh()
	if err != nil {
		return Session{}, err
	}
	reg, err := s.store.RedeemInviteGoogle(ctx, hashCode(inviteCode), g, displayName, deviceName, hash, s.now())
	if err != nil {
		return Session{}, err
	}
	return s.issue(reg, refresh)
}

// GoogleLink links the Google identity of idToken to the caller. Linking the identity the caller
// already has is a no-op; ErrGoogleTaken when another user holds it, ErrGoogleAlreadyLinked
// when the caller holds a different one (unlink first).
func (s *Service) GoogleLink(ctx context.Context, p Principal, idToken string) (User, error) {
	if s.google == nil {
		return User{}, ErrGoogleDisabled
	}
	g, _, err := s.verifyGoogle(ctx, idToken)
	if err != nil {
		return User{}, err
	}
	if err := s.store.LinkGoogle(ctx, p.UserID, g, s.now()); err != nil {
		return User{}, err
	}
	return s.store.GetUser(ctx, p.UserID)
}

// GoogleUnlink removes the caller's Google identity (idempotent).
func (s *Service) GoogleUnlink(ctx context.Context, p Principal) (User, error) {
	if s.google == nil {
		return User{}, ErrGoogleDisabled
	}
	if err := s.store.UnlinkGoogle(ctx, p.UserID); err != nil {
		return User{}, err
	}
	return s.store.GetUser(ctx, p.UserID)
}

// googleDisplayName picks a display name for a user who joined with Google and gave none: the
// given name, else the full name, else the part of the email before the "@", else "Listener".
func googleDisplayName(id googleid.Identity) string {
	local, _, _ := strings.Cut(id.Email, "@")
	for _, cand := range []string{id.GivenName, id.Name, local} {
		if n := sanitizeName(cand, 40); n != "" {
			return n
		}
	}
	return "Listener"
}

// sanitizeName drops control, format and invalid characters, folds runs of whitespace to one
// space, trims and cuts to max characters.
func sanitizeName(s string, max int) string {
	var b strings.Builder
	space := false
	for _, r := range s {
		switch {
		case r == utf8.RuneError, unicode.IsControl(r), unicode.Is(unicode.Cf, r), unicode.Is(unicode.Co, r):
			continue
		case unicode.IsSpace(r):
			space = b.Len() > 0
			continue
		}
		if space {
			b.WriteByte(' ')
			space = false
		}
		b.WriteRune(r)
	}
	out := b.String()
	if utf8.RuneCountInString(out) > max {
		out = strings.TrimSpace(string([]rune(out)[:max]))
	}
	return out
}
