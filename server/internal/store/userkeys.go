package store

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

var _ userkeys.Store = (*Postgres)(nil)

// Every query on user_debrid_keys is scoped by user_id: a user's key is reachable only with that
// user's id. The one cross-user query is the fingerprint EXISTS in ListKeys (same provider, current v2 fingerprint format only), which exposes a
// boolean and nothing else.

func (p *Postgres) PutKey(ctx context.Context, r userkeys.Record) error {
	_, err := p.pool.Exec(ctx, `INSERT INTO user_debrid_keys (user_id, provider, sealed_key, fingerprint, status, validated_at, last_error)
		VALUES ($1::uuid, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (user_id, provider) DO UPDATE SET sealed_key = EXCLUDED.sealed_key,
			fingerprint = EXCLUDED.fingerprint, status = EXCLUDED.status,
			validated_at = EXCLUDED.validated_at, last_error = EXCLUDED.last_error`,
		r.UserID, r.Provider, r.SealedKey, r.Fingerprint, r.State, r.ValidatedAt, r.LastError)
	if err != nil {
		return fmt.Errorf("store: put debrid key: %w", err)
	}
	return nil
}

func (p *Postgres) GetKey(ctx context.Context, userID, provider string) (userkeys.Record, bool, error) {
	r := userkeys.Record{UserID: userID, Provider: provider}
	err := p.pool.QueryRow(ctx, `SELECT sealed_key, fingerprint, status, validated_at, last_error
		FROM user_debrid_keys WHERE user_id = $1::uuid AND provider = $2`, userID, provider).
		Scan(&r.SealedKey, &r.Fingerprint, &r.State, &r.ValidatedAt, &r.LastError)
	if errors.Is(err, pgx.ErrNoRows) {
		return userkeys.Record{}, false, nil
	}
	if err != nil {
		return userkeys.Record{}, false, fmt.Errorf("store: get debrid key: %w", err)
	}
	return r, true, nil
}

func (p *Postgres) ListKeys(ctx context.Context, userID string) ([]userkeys.Record, error) {
	rows, err := p.pool.Query(ctx, `SELECT k.provider, k.sealed_key, k.fingerprint, k.status, k.validated_at, k.last_error,
		(k.fingerprint LIKE 'v2:%' AND EXISTS (SELECT 1 FROM user_debrid_keys o
			WHERE o.provider = k.provider AND o.fingerprint = k.fingerprint AND o.user_id <> k.user_id))
		FROM user_debrid_keys k WHERE k.user_id = $1::uuid ORDER BY k.provider`, userID)
	if err != nil {
		return nil, fmt.Errorf("store: list debrid keys: %w", err)
	}
	defer rows.Close()
	out := []userkeys.Record{}
	for rows.Next() {
		r := userkeys.Record{UserID: userID}
		if err := rows.Scan(&r.Provider, &r.SealedKey, &r.Fingerprint, &r.State, &r.ValidatedAt, &r.LastError, &r.SharedWithOthers); err != nil {
			return nil, fmt.Errorf("store: list debrid keys: %w", err)
		}
		out = append(out, r)
	}
	return out, rows.Err()
}

func (p *Postgres) DeleteKey(ctx context.Context, userID, provider string) error {
	if _, err := p.pool.Exec(ctx, `DELETE FROM user_debrid_keys WHERE user_id = $1::uuid AND provider = $2`, userID, provider); err != nil {
		return fmt.Errorf("store: delete debrid key: %w", err)
	}
	return nil
}

func (p *Postgres) SetState(ctx context.Context, userID, provider, state string, validatedAt *time.Time, lastError string) (bool, error) {
	tag, err := p.pool.Exec(ctx, `UPDATE user_debrid_keys SET status = $3, validated_at = COALESCE($4, validated_at), last_error = $5
		WHERE user_id = $1::uuid AND provider = $2`, userID, provider, state, validatedAt, lastError)
	if err != nil {
		return false, fmt.Errorf("store: set debrid key state: %w", err)
	}
	return tag.RowsAffected() > 0, nil
}
