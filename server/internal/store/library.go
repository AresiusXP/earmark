package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/library"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

var _ library.Store = (*Postgres)(nil)

// Every query on user_library and bookmarks carries the user id: a user reaches their own rows only.

const entryCols = `b.asin, b.title, b.subtitle, b.authors, b.narrators, b.series, b.series_position,
	b.language, b.runtime_min, b.cover_url, b.description, b.region, b.release_year, b.genres,
	l.status, l.release_hash, l.position_ms, l.file_path, l.file_position_ms, l.speed, l.updated_at, l.finished_at`

func scanEntry(row pgx.Row) (library.Entry, error) {
	var e library.Entry
	b := &e.Book
	err := row.Scan(&b.ASIN, &b.Title, &b.Subtitle, &b.Authors, &b.Narrators, &b.Series, &b.SeriesPosition,
		&b.Language, &b.RuntimeMin, &b.CoverURL, &b.Description, &b.Region, &b.ReleaseYear, &b.Genres,
		&e.Status, &e.ReleaseHash, &e.PositionMs, &e.FilePath, &e.FilePositionMs, &e.Speed, &e.UpdatedAt, &e.FinishedAt)
	return e, err
}

func (p *Postgres) ListEntries(ctx context.Context, userID, status string) ([]library.Entry, error) {
	rows, err := p.pool.Query(ctx, `SELECT `+entryCols+` FROM user_library l JOIN books b ON b.asin = l.asin
		WHERE l.user_id = $1::uuid AND ($2 = '' OR l.status = $2)
		ORDER BY l.updated_at DESC, l.asin LIMIT $3`, userID, status, library.MaxEntries)
	if err != nil {
		return nil, fmt.Errorf("store: list library: %w", err)
	}
	defer rows.Close()
	out := []library.Entry{}
	for rows.Next() {
		e, err := scanEntry(rows)
		if err != nil {
			return nil, fmt.Errorf("store: list library: %w", err)
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

func (p *Postgres) GetEntry(ctx context.Context, userID, asin string) (library.Entry, error) {
	return getEntry(ctx, p.pool, userID, asin)
}

type queryRower interface {
	QueryRow(ctx context.Context, sql string, args ...any) pgx.Row
}

func getEntry(ctx context.Context, q queryRower, userID, asin string) (library.Entry, error) {
	e, err := scanEntry(q.QueryRow(ctx, `SELECT `+entryCols+` FROM user_library l JOIN books b ON b.asin = l.asin
		WHERE l.user_id = $1::uuid AND l.asin = $2`, userID, asin))
	if errors.Is(err, pgx.ErrNoRows) {
		return library.Entry{}, library.ErrNotFound
	}
	if err != nil {
		return library.Entry{}, fmt.Errorf("store: get library entry: %w", err)
	}
	return e, nil
}

func (p *Postgres) PutEntry(ctx context.Context, userID string, w library.Write) (library.Entry, error) {
	var out library.Entry
	now := w.Now
	if now.IsZero() {
		now = time.Now()
	}
	err := p.inTx(ctx, "put library entry", func(tx pgx.Tx) error {
		if err := lockKey(ctx, tx, "library:"+userID); err != nil {
			return err
		}
		var exists bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM user_library WHERE user_id = $1::uuid AND asin = $2)`,
			userID, w.ASIN).Scan(&exists); err != nil {
			return err
		}
		if !exists {
			var n int
			if err := tx.QueryRow(ctx, `SELECT count(*) FROM user_library WHERE user_id = $1::uuid`, userID).Scan(&n); err != nil {
				return err
			}
			if n >= library.MaxEntries {
				return library.ErrLimitReached
			}
		}
		// The WHERE makes an older client write a no-op; the stored entry is read back below.
		if _, err := tx.Exec(ctx, `INSERT INTO user_library AS l (user_id, asin, status, release_hash, position_ms,
				file_path, file_position_ms, speed, updated_at, finished_at)
			VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8, $9, CASE WHEN $3 = 'finished' THEN $10::timestamptz END)
			ON CONFLICT (user_id, asin) DO UPDATE SET status = EXCLUDED.status,
				release_hash = EXCLUDED.release_hash, position_ms = EXCLUDED.position_ms,
				file_path = EXCLUDED.file_path, file_position_ms = EXCLUDED.file_position_ms,
				speed = EXCLUDED.speed, updated_at = EXCLUDED.updated_at,
				finished_at = CASE WHEN EXCLUDED.status = 'finished' THEN COALESCE(l.finished_at, EXCLUDED.finished_at) END
			WHERE l.updated_at <= EXCLUDED.updated_at`,
			userID, w.ASIN, w.Status, w.ReleaseHash, w.PositionMs, w.FilePath, w.FilePositionMs, w.Speed, w.UpdatedAt, now); err != nil {
			return err
		}
		e, err := getEntry(ctx, tx, userID, w.ASIN)
		out = e
		return err
	})
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23503" {
		if strings.Contains(pgErr.ConstraintName, "release_hash") {
			return library.Entry{}, library.ErrUnknownRelease
		}
		return library.Entry{}, library.ErrUnknownBook
	}
	if errors.Is(err, library.ErrLimitReached) {
		return library.Entry{}, library.ErrLimitReached
	}
	return out, err
}

func (p *Postgres) DeleteEntry(ctx context.Context, userID, asin string) error {
	if _, err := p.pool.Exec(ctx, `DELETE FROM user_library WHERE user_id = $1::uuid AND asin = $2`, userID, asin); err != nil {
		return fmt.Errorf("store: delete library entry: %w", err)
	}
	return nil
}

func (p *Postgres) ListBookmarks(ctx context.Context, userID, asin string) ([]library.Bookmark, error) {
	rows, err := p.pool.Query(ctx, `SELECT id::text, position_ms, note, created_at, coalesce(client_id, '') FROM bookmarks
		WHERE user_id = $1::uuid AND asin = $2 ORDER BY position_ms, created_at, id LIMIT $3`,
		userID, asin, library.MaxBookmarks)
	if err != nil {
		return nil, fmt.Errorf("store: list bookmarks: %w", err)
	}
	defer rows.Close()
	out := []library.Bookmark{}
	for rows.Next() {
		var b library.Bookmark
		if err := rows.Scan(&b.ID, &b.PositionMs, &b.Note, &b.CreatedAt, &b.ClientID); err != nil {
			return nil, fmt.Errorf("store: list bookmarks: %w", err)
		}
		out = append(out, b)
	}
	return out, rows.Err()
}

func (p *Postgres) CreateBookmark(ctx context.Context, userID, asin string, positionMs int64, note, clientID string) (library.Bookmark, error) {
	var b library.Bookmark
	err := p.inTx(ctx, "create bookmark", func(tx pgx.Tx) error {
		if err := lockKey(ctx, tx, "bookmarks:"+userID+":"+asin); err != nil {
			return err
		}
		var inLibrary bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM user_library WHERE user_id = $1::uuid AND asin = $2)`,
			userID, asin).Scan(&inLibrary); err != nil {
			return err
		}
		if !inLibrary {
			return library.ErrNotFound
		}
		if clientID != "" {
			err := tx.QueryRow(ctx, `SELECT id::text, position_ms, note, created_at, client_id FROM bookmarks
				WHERE user_id = $1::uuid AND asin = $2 AND client_id = $3`, userID, asin, clientID).
				Scan(&b.ID, &b.PositionMs, &b.Note, &b.CreatedAt, &b.ClientID)
			if err == nil {
				return nil // a retry: the bookmark already exists and counts once
			}
			if !errors.Is(err, pgx.ErrNoRows) {
				return err
			}
		}
		var n int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM bookmarks WHERE user_id = $1::uuid AND asin = $2`, userID, asin).Scan(&n); err != nil {
			return err
		}
		if n >= library.MaxBookmarks {
			return library.ErrLimitReached
		}
		return tx.QueryRow(ctx, `INSERT INTO bookmarks (user_id, asin, position_ms, note, client_id)
			VALUES ($1::uuid, $2, $3, $4, NULLIF($5, '')) RETURNING id::text, position_ms, note, created_at, coalesce(client_id, '')`,
			userID, asin, positionMs, note, clientID).Scan(&b.ID, &b.PositionMs, &b.Note, &b.CreatedAt, &b.ClientID)
	})
	switch {
	case errors.Is(err, library.ErrNotFound):
		return library.Bookmark{}, library.ErrNotFound
	case errors.Is(err, library.ErrLimitReached):
		return library.Bookmark{}, library.ErrLimitReached
	}
	return b, err
}

// EnsureSaved inserts a `saved` entry when the user has none, under the same lock and limit as PutEntry.
func (p *Postgres) EnsureSaved(ctx context.Context, userID, asin string) (bool, error) {
	added := false
	err := p.inTx(ctx, "ensure saved library entry", func(tx pgx.Tx) error {
		if err := lockKey(ctx, tx, "library:"+userID); err != nil {
			return err
		}
		var exists bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM user_library WHERE user_id = $1::uuid AND asin = $2)`,
			userID, asin).Scan(&exists); err != nil {
			return err
		}
		if exists {
			return nil
		}
		var n int
		if err := tx.QueryRow(ctx, `SELECT count(*) FROM user_library WHERE user_id = $1::uuid`, userID).Scan(&n); err != nil {
			return err
		}
		if n >= library.MaxEntries {
			return library.ErrLimitReached
		}
		if _, err := tx.Exec(ctx, `INSERT INTO user_library (user_id, asin, status, updated_at) VALUES ($1::uuid, $2, 'saved', 0)`,
			userID, asin); err != nil {
			return err
		}
		added = true
		return nil
	})
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23503" {
		return false, library.ErrUnknownBook
	}
	if errors.Is(err, library.ErrLimitReached) {
		return false, library.ErrLimitReached
	}
	return added, err
}

func (p *Postgres) DeleteBookmark(ctx context.Context, userID, asin, id string) error {
	_, err := p.pool.Exec(ctx, `DELETE FROM bookmarks WHERE user_id = $1::uuid AND asin = $2 AND id::text = $3`, userID, asin, id)
	if err != nil {
		return fmt.Errorf("store: delete bookmark: %w", err)
	}
	return nil
}

// ---- client-reported torrent files ----------------------------------------------------------

type clientFile struct {
	Index int    `json:"index"`
	Path  string `json:"path"`
	Size  int64  `json:"size"`
}

// ClientFiles returns the client-reported file lists per hash (absent when none).
func (p *Postgres) ClientFiles(ctx context.Context, infoHashes []string) (map[string][]model.File, error) {
	out := map[string][]model.File{}
	if len(infoHashes) == 0 {
		return out, nil
	}
	hs, err := normHashes(infoHashes)
	if err != nil {
		return nil, err
	}
	rows, err := p.pool.Query(ctx, `SELECT info_hash, files FROM release_client_files WHERE info_hash = ANY($1)`, hs)
	if err != nil {
		return nil, fmt.Errorf("store: client files: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var (
			h   string
			raw []byte
		)
		if err := rows.Scan(&h, &raw); err != nil {
			return nil, fmt.Errorf("store: client files: %w", err)
		}
		fs, err := decodeClientFiles(raw)
		if err != nil {
			return nil, fmt.Errorf("store: client files %s: %w", h, err)
		}
		out[h] = fs
	}
	return out, rows.Err()
}

func decodeClientFiles(raw []byte) ([]model.File, error) {
	var cf []clientFile
	if err := json.Unmarshal(raw, &cf); err != nil {
		return nil, err
	}
	fs := make([]model.File, len(cf))
	for i, f := range cf {
		fs[i] = model.File{Index: f.Index, Path: f.Path, Size: f.Size}
	}
	return fs, nil
}

// SaveClientFiles stores the list when the hash has none yet and returns the stored list (the
// earlier one when it already existed: created is false then). An unknown release is
// library.ErrUnknownRelease.
func (p *Postgres) SaveClientFiles(ctx context.Context, infoHash string, files []model.File) (stored []model.File, created bool, err error) {
	h, err := normHash(infoHash)
	if err != nil {
		return nil, false, err
	}
	cf := make([]clientFile, len(files))
	for i, f := range files {
		cf[i] = clientFile{Index: f.Index, Path: f.Path, Size: f.Size}
	}
	raw, err := json.Marshal(cf)
	if err != nil {
		return nil, false, err
	}
	err = p.inTx(ctx, "save client files", func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, `INSERT INTO release_client_files (info_hash, files) VALUES ($1, $2)
			ON CONFLICT (info_hash) DO NOTHING`, h, raw)
		if err != nil {
			return err
		}
		created = tag.RowsAffected() == 1
		var cur []byte
		if err := tx.QueryRow(ctx, `SELECT files FROM release_client_files WHERE info_hash = $1`, h).Scan(&cur); err != nil {
			return err
		}
		stored, err = decodeClientFiles(cur)
		return err
	})
	if isFKViolation(err) {
		return nil, false, library.ErrUnknownRelease
	}
	return stored, created, err
}

// ---- reported file durations ----------------------------------------------------------------

// SaveFileDurations stores the durations whose (release, path) has none yet (first writer wins, a
// stored value is never replaced) and returns, in input order, the stored duration of every path:
// the caller's own when it was new, the earlier one otherwise. The caller has already checked that
// every path is in a known file list of the release. An unknown release is library.ErrUnknownRelease.
func (p *Postgres) SaveFileDurations(ctx context.Context, infoHash string, ds []library.FileDuration) ([]library.FileDuration, error) {
	h, err := normHash(infoHash)
	if err != nil {
		return nil, err
	}
	paths := make([]string, len(ds))
	durs := make([]int64, len(ds))
	for i, d := range ds {
		paths[i], durs[i] = d.Path, d.DurationMs
	}
	out := make([]library.FileDuration, 0, len(ds))
	err = p.inTx(ctx, "save file durations", func(tx pgx.Tx) error {
		out = out[:0]
		if len(ds) == 0 {
			return nil
		}
		// One report at a time per release: two overlapping reports cannot deadlock on each other's rows.
		if err := lockKey(ctx, tx, "durations:"+h); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, `INSERT INTO release_file_durations (info_hash, path, duration_ms)
			SELECT $1, t.p, t.d FROM unnest($2::text[], $3::bigint[]) AS t(p, d)
			ON CONFLICT (info_hash, path) DO NOTHING`, h, paths, durs); err != nil {
			return err
		}
		rows, err := tx.Query(ctx, `SELECT path, duration_ms FROM release_file_durations WHERE info_hash = $1 AND path = ANY($2)`, h, paths)
		if err != nil {
			return err
		}
		stored := map[string]int64{}
		for rows.Next() {
			var (
				path string
				d    int64
			)
			if err := rows.Scan(&path, &d); err != nil {
				rows.Close()
				return err
			}
			stored[path] = d
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, d := range ds {
			if v, ok := stored[d.Path]; ok {
				out = append(out, library.FileDuration{Path: d.Path, DurationMs: v})
			}
		}
		return nil
	})
	if isFKViolation(err) {
		return nil, library.ErrUnknownRelease
	}
	if err != nil {
		return nil, err
	}
	return out, nil
}

// FileDurations returns the reported durations per release hash and normalised path (absent: none).
func (p *Postgres) FileDurations(ctx context.Context, infoHashes []string) (map[string]map[string]int64, error) {
	out := map[string]map[string]int64{}
	if len(infoHashes) == 0 {
		return out, nil
	}
	hs, err := normHashes(infoHashes)
	if err != nil {
		return nil, err
	}
	rows, err := p.pool.Query(ctx, `SELECT info_hash, path, duration_ms FROM release_file_durations WHERE info_hash = ANY($1)`, hs)
	if err != nil {
		return nil, fmt.Errorf("store: file durations: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var (
			h, path string
			d       int64
		)
		if err := rows.Scan(&h, &path, &d); err != nil {
			return nil, fmt.Errorf("store: file durations: %w", err)
		}
		if out[h] == nil {
			out[h] = map[string]int64{}
		}
		out[h][path] = d
	}
	return out, rows.Err()
}

// ---- releases by hash -----------------------------------------------------------------------

// GetRelease returns nil when the hash is unknown.
func (p *Postgres) GetRelease(ctx context.Context, infoHash string) (*model.Release, error) {
	h, err := normHash(infoHash)
	if err != nil {
		return nil, err
	}
	var (
		r         model.Release
		published *time.Time
		parsed    []byte
	)
	err = p.pool.QueryRow(ctx, `SELECT info_hash, title, source, magnet, size, seeders, published_at, parsed
		FROM releases WHERE info_hash = $1`, h).Scan(&r.InfoHash, &r.Title, &r.Source, &r.Magnet, &r.Size, &r.Seeders, &published, &parsed)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("store: get release: %w", err)
	}
	if published != nil {
		r.PublishedAt = published.UTC()
	}
	if len(parsed) > 0 {
		_ = json.Unmarshal(parsed, &r.Parsed)
	}
	return &r, nil
}

// InsertReleaseIfAbsent adds a release a user reported by magnet; an existing row is left
// untouched (created is false).
func (p *Postgres) InsertReleaseIfAbsent(ctx context.Context, r model.Release) (bool, error) {
	h, err := normHash(r.InfoHash)
	if err != nil {
		return false, err
	}
	parsed, err := json.Marshal(r.Parsed)
	if err != nil {
		return false, err
	}
	tag, err := p.pool.Exec(ctx, `INSERT INTO releases (info_hash, title, source, magnet, size, seeders, parsed)
		VALUES ($1, $2, $3, $4, $5, $6, $7) ON CONFLICT (info_hash) DO NOTHING`,
		h, r.Title, r.Source, r.Magnet, max(r.Size, 0), clampInt4(r.Seeders), parsed)
	if err != nil {
		return false, fmt.Errorf("store: insert release: %w", err)
	}
	return tag.RowsAffected() == 1, nil
}

// ---- per-key availability -------------------------------------------------------------------

// GetUserAvailability returns the states recorded for ONE key fingerprint at a provider.
func (p *Postgres) GetUserAvailability(ctx context.Context, fingerprint, provider string, infoHashes []string, newerThan time.Time) (map[string]bool, error) {
	out := map[string]bool{}
	if len(infoHashes) == 0 || fingerprint == "" {
		return out, nil
	}
	hs, err := normHashes(infoHashes)
	if err != nil {
		return nil, err
	}
	rows, err := p.pool.Query(ctx, `SELECT info_hash, cached FROM user_availability
		WHERE fingerprint = $1 AND provider = $2 AND info_hash = ANY($3)
		AND ($4::timestamptz IS NULL OR checked_at >= $4)`, fingerprint, provider, hs, nullTime(newerThan))
	if err != nil {
		return nil, fmt.Errorf("store: get user availability: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var (
			h string
			c bool
		)
		if err := rows.Scan(&h, &c); err != nil {
			return nil, fmt.Errorf("store: get user availability: %w", err)
		}
		out[h] = c
	}
	return out, rows.Err()
}

// SetUserAvailability records the state for ONE key fingerprint at a provider.
func (p *Postgres) SetUserAvailability(ctx context.Context, fingerprint, provider, infoHash string, cached bool, at time.Time) error {
	h, err := normHash(infoHash)
	if err != nil {
		return err
	}
	if fingerprint == "" {
		return errors.New("store: set user availability: empty fingerprint")
	}
	if at.IsZero() {
		at = time.Now()
	}
	_, err = p.pool.Exec(ctx, `INSERT INTO user_availability (fingerprint, provider, info_hash, cached, checked_at)
		VALUES ($1, $2, $3, $4, $5)
		ON CONFLICT (fingerprint, provider, info_hash) DO UPDATE SET cached = EXCLUDED.cached, checked_at = EXCLUDED.checked_at`,
		fingerprint, provider, h, cached, at)
	if err != nil {
		return fmt.Errorf("store: set user availability: %w", err)
	}
	return nil
}

// PruneUserAvailability deletes the per-key cache states checked before olderThan and returns how
// many went. They are only a hint (a stale one is ignored on read anyway), so the table must not
// grow with every key and release ever probed.
func (p *Postgres) PruneUserAvailability(ctx context.Context, olderThan time.Time) (int64, error) {
	tag, err := p.pool.Exec(ctx, `DELETE FROM user_availability WHERE checked_at < $1`, olderThan)
	if err != nil {
		return 0, fmt.Errorf("store: prune user availability: %w", err)
	}
	return tag.RowsAffected(), nil
}
