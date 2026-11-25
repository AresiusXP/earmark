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

var _ accounts.Store = (*Postgres)(nil)

// usersLockKey serialises the operations that decide how many (admin) users exist: bootstrap and
// disabling a user.
const usersLockKey int64 = 0x61626B5F75736572 // "abk_user"

const userCols = `u.id::text, u.display_name, u.role, u.disabled, u.created_at, u.last_seen_at,
	u.region, u.languages, u.prefer_format, u.preferred_provider, u.source_mode, u.p2p_enabled`

func scanUser(row pgx.Row, extra ...any) (accounts.User, error) {
	var (
		u    accounts.User
		role string
	)
	dst := append([]any{&u.ID, &u.DisplayName, &role, &u.Disabled, &u.CreatedAt, &u.LastSeenAt,
		&u.Settings.Region, &u.Settings.Languages, &u.Settings.PreferFormat, &u.Settings.PreferredProvider,
		&u.Settings.SourceMode, &u.Settings.P2PEnabled}, extra...)
	if err := row.Scan(dst...); err != nil {
		return accounts.User{}, err
	}
	u.Role = accounts.Role(role)
	return u, nil
}

// idErr maps "not a uuid" and "no rows" to accounts.ErrNotFound.
func idErr(err error) error {
	var pgErr *pgconn.PgError
	if errors.Is(err, pgx.ErrNoRows) || (errors.As(err, &pgErr) && pgErr.Code == "22P02") {
		return accounts.ErrNotFound
	}
	return err
}

func (p *Postgres) Bootstrapped(ctx context.Context) (bool, error) {
	var exists bool
	if err := p.pool.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM users)`).Scan(&exists); err != nil {
		return false, fmt.Errorf("store: bootstrapped: %w", err)
	}
	return exists, nil
}

// insertUserDevice creates a user and its first device inside tx.
func insertUserDevice(ctx context.Context, tx pgx.Tx, displayName string, role accounts.Role, deviceName string, refreshHash []byte, now time.Time) (accounts.Registered, error) {
	d := accounts.DefaultSettings()
	row := tx.QueryRow(ctx, `INSERT INTO users AS u (display_name, role, created_at, last_seen_at, region, languages, prefer_format, source_mode)
		VALUES ($1, $2, $3, $3, $4, $5, $6, $7) RETURNING `+userCols,
		displayName, string(role), now, d.Region, d.Languages, d.PreferFormat, d.SourceMode)
	u, err := scanUser(row)
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: insert user: %w", err)
	}
	var did string
	if err := tx.QueryRow(ctx, `INSERT INTO devices (user_id, name, refresh_hash, created_at, last_seen_at)
		VALUES ($1::uuid, $2, $3, $4, $4) RETURNING id::text`, u.ID, deviceName, refreshHash, now).Scan(&did); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: insert device: %w", err)
	}
	return accounts.Registered{User: u, DeviceID: did}, nil
}

func (p *Postgres) CreateFirstAdmin(ctx context.Context, displayName, deviceName string, refreshHash []byte, now time.Time) (accounts.Registered, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: bootstrap: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock($1)`, usersLockKey); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: bootstrap lock: %w", err)
	}
	var exists bool
	if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM users)`).Scan(&exists); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: bootstrap: %w", err)
	}
	if exists {
		return accounts.Registered{}, accounts.ErrAlreadyBootstrapped
	}
	reg, err := insertUserDevice(ctx, tx, displayName, accounts.RoleAdmin, deviceName, refreshHash, now)
	if err != nil {
		return accounts.Registered{}, err
	}
	if err := tx.Commit(ctx); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: bootstrap: %w", err)
	}
	return reg, nil
}

// requireAdmin re-checks, inside tx and under a share lock on the actor's row, that the actor is
// an enabled admin RIGHT NOW. The Principal a request carries may be stale (an in-flight request,
// the 5 s auth cache of another replica); every admin mutation therefore decides on the row, and
// a concurrent disable of the actor waits for this transaction.
func requireAdmin(ctx context.Context, tx pgx.Tx, actorID string) error {
	var ok bool
	err := tx.QueryRow(ctx, `SELECT role = 'admin' AND NOT disabled FROM users WHERE id = $1::uuid FOR SHARE`, actorID).Scan(&ok)
	if e := idErr(err); errors.Is(e, accounts.ErrNotFound) {
		return accounts.ErrForbidden
	}
	if err != nil {
		return fmt.Errorf("store: check admin: %w", err)
	}
	if !ok {
		return accounts.ErrForbidden
	}
	return nil
}

func (p *Postgres) CreateInvite(ctx context.Context, createdBy string, codeHash []byte, note string, now, expires time.Time) (accounts.Invite, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return accounts.Invite{}, fmt.Errorf("store: create invite: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	if err := requireAdmin(ctx, tx, createdBy); err != nil {
		return accounts.Invite{}, err
	}
	// Old finished rows are dropped here, on the rare admin action, instead of by a janitor.
	_, _ = tx.Exec(ctx, `DELETE FROM invites WHERE used_at IS NULL AND expires_at < $1`, now.Add(-30*24*time.Hour))
	var inv accounts.Invite
	err = tx.QueryRow(ctx, `INSERT INTO invites (code_hash, note, created_by, created_at, expires_at)
		VALUES ($1, $2, $3::uuid, $4, $5) RETURNING id::text, note, created_at, expires_at`,
		codeHash, note, createdBy, now, expires).Scan(&inv.ID, &inv.Note, &inv.CreatedAt, &inv.ExpiresAt)
	if err != nil {
		return accounts.Invite{}, fmt.Errorf("store: create invite: %w", idErr(err))
	}
	if err := tx.Commit(ctx); err != nil {
		return accounts.Invite{}, fmt.Errorf("store: create invite: %w", err)
	}
	return inv, nil
}

func (p *Postgres) ListOpenInvites(ctx context.Context, now time.Time) ([]accounts.Invite, error) {
	rows, err := p.pool.Query(ctx, `SELECT id::text, note, created_at, expires_at FROM invites
		WHERE used_at IS NULL AND expires_at > $1 ORDER BY created_at DESC, id`, now)
	if err != nil {
		return nil, fmt.Errorf("store: list invites: %w", err)
	}
	defer rows.Close()
	out := []accounts.Invite{}
	for rows.Next() {
		var inv accounts.Invite
		if err := rows.Scan(&inv.ID, &inv.Note, &inv.CreatedAt, &inv.ExpiresAt); err != nil {
			return nil, fmt.Errorf("store: list invites: %w", err)
		}
		out = append(out, inv)
	}
	return out, rows.Err()
}

func (p *Postgres) DeleteInvite(ctx context.Context, actorID, id string) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("store: delete invite: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	if err := requireAdmin(ctx, tx, actorID); err != nil {
		return err
	}
	_, err = tx.Exec(ctx, `DELETE FROM invites WHERE id = $1::uuid AND used_at IS NULL`, id)
	if err = idErr(err); err != nil && !errors.Is(err, accounts.ErrNotFound) {
		return fmt.Errorf("store: delete invite: %w", err)
	}
	if errors.Is(err, accounts.ErrNotFound) {
		// A malformed id deleted nothing; the aborted statement poisons tx, so just stop here.
		return nil
	}
	return tx.Commit(ctx)
}

func (p *Postgres) RedeemInvite(ctx context.Context, codeHash []byte, displayName, deviceName string, refreshHash []byte, now time.Time) (accounts.Registered, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: redeem invite: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	// Marking the invite used is the single-use guard: a concurrent redeemer waits for this
	// row lock and then no longer matches used_at IS NULL.
	var inviteID string
	err = tx.QueryRow(ctx, `UPDATE invites SET used_at = $2 WHERE code_hash = $1 AND used_at IS NULL AND expires_at > $2
		RETURNING id::text`, codeHash, now).Scan(&inviteID)
	if errors.Is(err, pgx.ErrNoRows) {
		return accounts.Registered{}, accounts.ErrInvalidCode
	}
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: redeem invite: %w", err)
	}
	reg, err := insertUserDevice(ctx, tx, displayName, accounts.RoleUser, deviceName, refreshHash, now)
	if err != nil {
		return accounts.Registered{}, err
	}
	if _, err := tx.Exec(ctx, `UPDATE invites SET used_by = $2::uuid WHERE id = $1::uuid`, inviteID, reg.User.ID); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: redeem invite: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: redeem invite: %w", err)
	}
	return reg, nil
}

func (p *Postgres) CreateLinkCode(ctx context.Context, userID string, codeHash []byte, now, expires time.Time) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("store: create link code: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	if _, err := tx.Exec(ctx, `DELETE FROM link_codes WHERE user_id = $1::uuid`, userID); err != nil {
		return fmt.Errorf("store: create link code: %w", idErr(err))
	}
	if _, err := tx.Exec(ctx, `INSERT INTO link_codes (code_hash, user_id, created_at, expires_at) VALUES ($1, $2::uuid, $3, $4)`,
		codeHash, userID, now, expires); err != nil {
		return fmt.Errorf("store: create link code: %w", idErr(err))
	}
	return tx.Commit(ctx)
}

func (p *Postgres) RedeemLinkCode(ctx context.Context, codeHash []byte, deviceName string, refreshHash []byte, now time.Time) (accounts.Registered, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: redeem link code: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	var userID string
	err = tx.QueryRow(ctx, `UPDATE link_codes c SET used_at = $2
		WHERE c.code_hash = $1 AND c.used_at IS NULL AND c.expires_at > $2
		  AND EXISTS (SELECT 1 FROM users u WHERE u.id = c.user_id AND NOT u.disabled)
		RETURNING c.user_id::text`, codeHash, now).Scan(&userID)
	if errors.Is(err, pgx.ErrNoRows) {
		return accounts.Registered{}, accounts.ErrInvalidCode
	}
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: redeem link code: %w", err)
	}
	var did string
	if err := tx.QueryRow(ctx, `INSERT INTO devices (user_id, name, refresh_hash, created_at, last_seen_at)
		VALUES ($1::uuid, $2, $3, $4, $4) RETURNING id::text`, userID, deviceName, refreshHash, now).Scan(&did); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: insert device: %w", err)
	}
	u, err := scanUser(tx.QueryRow(ctx, `SELECT `+userCols+` FROM users u WHERE u.id = $1::uuid`, userID))
	if err != nil {
		return accounts.Registered{}, fmt.Errorf("store: redeem link code: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return accounts.Registered{}, fmt.Errorf("store: redeem link code: %w", err)
	}
	return accounts.Registered{User: u, DeviceID: did}, nil
}

func (p *Postgres) RotateRefresh(ctx context.Context, oldHash, newHash []byte, now time.Time) (accounts.Registered, bool, error) {
	var did string
	u, err := scanUser(p.pool.QueryRow(ctx, `UPDATE devices d SET refresh_hash = $2, last_seen_at = $3
		FROM users u WHERE d.refresh_hash = $1 AND d.revoked_at IS NULL AND u.id = d.user_id AND NOT u.disabled
		RETURNING `+userCols+`, d.id::text`, oldHash, newHash, now), &did)
	if errors.Is(err, pgx.ErrNoRows) {
		return accounts.Registered{}, false, nil
	}
	if err != nil {
		return accounts.Registered{}, false, fmt.Errorf("store: rotate refresh: %w", err)
	}
	return accounts.Registered{User: u, DeviceID: did}, true, nil
}

func (p *Postgres) AuthState(ctx context.Context, userID, deviceID string, now time.Time) (accounts.Role, bool, error) {
	var (
		role string
		seen *time.Time
	)
	err := p.pool.QueryRow(ctx, `SELECT u.role, d.last_seen_at FROM devices d JOIN users u ON u.id = d.user_id
		WHERE d.id = $2::uuid AND d.user_id = $1::uuid AND d.revoked_at IS NULL AND NOT u.disabled`,
		userID, deviceID).Scan(&role, &seen)
	if err = idErr(err); errors.Is(err, accounts.ErrNotFound) {
		return "", false, nil
	}
	if err != nil {
		return "", false, fmt.Errorf("store: auth state: %w", err)
	}
	if seen == nil || now.Sub(*seen) > time.Minute {
		// Best effort: last_seen_at is informational.
		_, _ = p.pool.Exec(ctx, `UPDATE devices SET last_seen_at = $3 WHERE id = $2::uuid AND user_id = $1::uuid`, userID, deviceID, now)
		_, _ = p.pool.Exec(ctx, `UPDATE users SET last_seen_at = $2 WHERE id = $1::uuid`, userID, now)
	}
	return accounts.Role(role), true, nil
}

func (p *Postgres) GetUser(ctx context.Context, id string) (accounts.User, error) {
	u, err := scanUser(p.pool.QueryRow(ctx, `SELECT `+userCols+` FROM users u WHERE u.id = $1::uuid`, id))
	if err != nil {
		return accounts.User{}, userErr("get user", err)
	}
	return u, nil
}

// userErr wraps err, keeping accounts.ErrNotFound recognisable.
func userErr(op string, err error) error {
	if e := idErr(err); errors.Is(e, accounts.ErrNotFound) {
		return accounts.ErrNotFound
	}
	return fmt.Errorf("store: %s: %w", op, err)
}

func (p *Postgres) UpdateUser(ctx context.Context, id string, mutate func(*accounts.User) error) (accounts.User, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return accounts.User{}, fmt.Errorf("store: update user: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	u, err := scanUser(tx.QueryRow(ctx, `SELECT `+userCols+` FROM users u WHERE u.id = $1::uuid FOR UPDATE`, id))
	if err != nil {
		return accounts.User{}, userErr("update user", err)
	}
	if err := mutate(&u); err != nil {
		return accounts.User{}, err
	}
	if _, err := tx.Exec(ctx, `UPDATE users SET display_name = $2, region = $3, languages = $4, prefer_format = $5,
		preferred_provider = $6, source_mode = $7, p2p_enabled = $8 WHERE id = $1::uuid`,
		id, u.DisplayName, u.Settings.Region, u.Settings.Languages, u.Settings.PreferFormat,
		u.Settings.PreferredProvider, u.Settings.SourceMode, u.Settings.P2PEnabled); err != nil {
		return accounts.User{}, fmt.Errorf("store: update user: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return accounts.User{}, fmt.Errorf("store: update user: %w", err)
	}
	return u, nil
}

func (p *Postgres) ListDevices(ctx context.Context, userID string) ([]accounts.Device, error) {
	rows, err := p.pool.Query(ctx, `SELECT id::text, name, created_at, last_seen_at FROM devices
		WHERE user_id = $1::uuid AND revoked_at IS NULL ORDER BY created_at, id`, userID)
	if err != nil {
		return nil, userErr("list devices", err)
	}
	defer rows.Close()
	out := []accounts.Device{}
	for rows.Next() {
		var d accounts.Device
		if err := rows.Scan(&d.ID, &d.Name, &d.CreatedAt, &d.LastSeenAt); err != nil {
			return nil, fmt.Errorf("store: list devices: %w", err)
		}
		out = append(out, d)
	}
	return out, rows.Err()
}

func (p *Postgres) RevokeDevice(ctx context.Context, userID, deviceID string, now time.Time) error {
	tag, err := p.pool.Exec(ctx, `UPDATE devices SET revoked_at = COALESCE(revoked_at, $3)
		WHERE id = $2::uuid AND user_id = $1::uuid`, userID, deviceID, now)
	if err != nil {
		return userErr("revoke device", err)
	}
	if tag.RowsAffected() == 0 {
		return accounts.ErrNotFound
	}
	return nil
}

const adminUserSelect = `SELECT ` + userCols + `,
	(SELECT count(*) FROM devices d WHERE d.user_id = u.id AND d.revoked_at IS NULL),
	COALESCE((SELECT array_agg(k.provider ORDER BY k.provider) FROM user_debrid_keys k WHERE k.user_id = u.id), '{}')
	FROM users u`

func scanAdminUser(row pgx.Row) (accounts.AdminUser, error) {
	var (
		count     int
		providers []string
	)
	u, err := scanUser(row, &count, &providers)
	if err != nil {
		return accounts.AdminUser{}, err
	}
	au := accounts.AdminUser{User: u, DeviceCount: count, Debrid: map[string]bool{}}
	for _, p := range providers {
		au.Debrid[p] = true
	}
	return au, nil
}

func (p *Postgres) ListUsers(ctx context.Context) ([]accounts.AdminUser, error) {
	rows, err := p.pool.Query(ctx, adminUserSelect+` ORDER BY u.created_at, u.id`)
	if err != nil {
		return nil, fmt.Errorf("store: list users: %w", err)
	}
	defer rows.Close()
	out := []accounts.AdminUser{}
	for rows.Next() {
		au, err := scanAdminUser(rows)
		if err != nil {
			return nil, fmt.Errorf("store: list users: %w", err)
		}
		out = append(out, au)
	}
	return out, rows.Err()
}

func (p *Postgres) SetDisabled(ctx context.Context, actorID, targetID string, disabled bool) (accounts.AdminUser, error) {
	if disabled && actorID == targetID {
		return accounts.AdminUser{}, accounts.ErrCannotDisableSelf
	}
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return accounts.AdminUser{}, fmt.Errorf("store: set disabled: %w", err)
	}
	defer func() { _ = tx.Rollback(context.WithoutCancel(ctx)) }()
	if _, err := tx.Exec(ctx, `SELECT pg_advisory_xact_lock($1)`, usersLockKey); err != nil {
		return accounts.AdminUser{}, fmt.Errorf("store: set disabled lock: %w", err)
	}
	if err := requireAdmin(ctx, tx, actorID); err != nil {
		return accounts.AdminUser{}, err
	}
	var (
		role string
		was  bool
	)
	if err := tx.QueryRow(ctx, `SELECT role, disabled FROM users WHERE id = $1::uuid FOR UPDATE`, targetID).Scan(&role, &was); err != nil {
		return accounts.AdminUser{}, userErr("set disabled", err)
	}
	if disabled && !was && role == string(accounts.RoleAdmin) {
		var others int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM users WHERE role = 'admin' AND NOT disabled AND id <> $1::uuid`, targetID).Scan(&others); err != nil {
			return accounts.AdminUser{}, fmt.Errorf("store: set disabled: %w", err)
		}
		if others == 0 {
			return accounts.AdminUser{}, accounts.ErrLastAdmin
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE users SET disabled = $2 WHERE id = $1::uuid`, targetID, disabled); err != nil {
		return accounts.AdminUser{}, fmt.Errorf("store: set disabled: %w", err)
	}
	au, err := scanAdminUser(tx.QueryRow(ctx, adminUserSelect+` WHERE u.id = $1::uuid`, targetID))
	if err != nil {
		return accounts.AdminUser{}, fmt.Errorf("store: set disabled: %w", err)
	}
	if err := tx.Commit(ctx); err != nil {
		return accounts.AdminUser{}, fmt.Errorf("store: set disabled: %w", err)
	}
	return au, nil
}
