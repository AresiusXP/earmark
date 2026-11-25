package store

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/accounts"
)

// google_identities (migration 0010): sub is the primary key and user_id is UNIQUE, so one
// identity belongs to at most one user and a user holds at most one identity. Every query that
// reads or writes by user is scoped by user_id; sign-in finds the user by sub only.

// insertGoogle links g to userID inside tx. A unique violation means the sub is held by someone
// else (ErrGoogleTaken); the caller's transaction is then aborted and must be rolled back.
func insertGoogle(ctx context.Context, tx pgx.Tx, userID string, g accounts.GoogleIdentity, now time.Time) error {
	_, err := tx.Exec(ctx, `INSERT INTO google_identities (sub, user_id, email, linked_at) VALUES ($1, $2::uuid, $3, $4)`,
		g.Sub, userID, g.Email, now)
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" {
		return accounts.ErrGoogleTaken
	}
	if err != nil {
		return fmt.Errorf("store: link google: %w", err)
	}
	return nil
}

func (p *Postgres) RedeemInviteGoogle(ctx context.Context, codeHash []byte, g accounts.GoogleIdentity, displayName, deviceName string, refreshHash []byte, now time.Time) (accounts.Registered, error) {
	return p.redeemInvite(ctx, codeHash, &g, displayName, deviceName, refreshHash, now)
}

func (p *Postgres) GoogleSignIn(ctx context.Context, g accounts.GoogleIdentity, deviceName string, refreshHash []byte, now time.Time) (accounts.Registered, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: google sign-in: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	var (
		userID   string
		disabled bool
	)
	err = tx.QueryRow(ctx, `SELECT u.id::text, u.disabled FROM google_identities gi JOIN users u ON u.id = gi.user_id
		WHERE gi.sub = $1 FOR SHARE OF u`, g.Sub).Scan(&userID, &disabled)
	if errors.Is(err, pgx.ErrNoRows) {
		return accounts.Registered{}, accounts.ErrGoogleNotLinked
	}
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: google sign-in: %w", err)
	}
	if disabled {
		return accounts.Registered{}, accounts.ErrUnauthorized
	}
	// The address can change at Google; keep the one we show current.
	if _, err := tx.Exec(ctx, `UPDATE google_identities SET email = $2 WHERE sub = $1 AND email <> $2`, g.Sub, g.Email); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: google sign-in: %w", err)
	}
	var did string
	if err := tx.QueryRow(ctx, `INSERT INTO devices (user_id, name, refresh_hash, created_at, last_seen_at)
		VALUES ($1::uuid, $2, $3, $4, $4) RETURNING id::text`, userID, deviceName, refreshHash, now).Scan(&did); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: insert device: %w", err)
	}
	u, err := scanUser(tx.QueryRow(ctx, `SELECT `+userCols+` FROM users u WHERE u.id = $1::uuid`, userID))
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: google sign-in: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: google sign-in: %w", err)
	}
	return accounts.Registered{User: u, DeviceID: did}, nil
}

func (p *Postgres) LinkGoogle(ctx context.Context, userID string, g accounts.GoogleIdentity, now time.Time) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("store: link google: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	// The user row lock serialises concurrent links of one user.
	var one int
	if err := tx.QueryRow(ctx, `SELECT 1 FROM users WHERE id = $1::uuid FOR UPDATE`, userID).Scan(&one); err != nil {
		return userErr("link google", err)
	}
	var current string
	err = tx.QueryRow(ctx, `SELECT sub FROM google_identities WHERE user_id = $1::uuid`, userID).Scan(&current)
	switch {
	case err == nil && current == g.Sub:
		if _, err := tx.Exec(ctx, `UPDATE google_identities SET email = $2 WHERE sub = $1`, g.Sub, g.Email); err != nil {
			return fmt.Errorf("store: link google: %w", err)
		}
		return tx.Commit(ctx)
	case err == nil:
		return accounts.ErrGoogleAlreadyLinked
	case !errors.Is(err, pgx.ErrNoRows):
		return fmt.Errorf("store: link google: %w", err)
	}
	if err := insertGoogle(ctx, tx, userID, g, now); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (p *Postgres) UnlinkGoogle(ctx context.Context, userID string) error {
	if _, err := p.pool.Exec(ctx, `DELETE FROM google_identities WHERE user_id = $1::uuid`, userID); err != nil {
		return userErr("unlink google", err)
	}
	return nil
}
