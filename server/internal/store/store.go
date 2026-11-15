// Package store is the Postgres implementation of service.Store.
//
// Conventions: "missing" is never an error (nil / zero value / absent map key). Info hashes
// are validated and normalised to lowercase 40-hex; anything else is rejected with an error.
// Writes that reference rows that do not exist (a link or file list for an unknown book or
// release) fail with service.ErrNotFound.
package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
)

var _ service.Store = (*Postgres)(nil)

// Postgres implements service.Store on a pgx connection pool. Safe for concurrent use.
type Postgres struct {
	pool *pgxpool.Pool
}

// Open connects to databaseURL, runs pending migrations and returns the store.
func Open(ctx context.Context, databaseURL string) (*Postgres, error) {
	cfg, err := pgxpool.ParseConfig(databaseURL)
	if err != nil {
		return nil, fmt.Errorf("store: parse database url: %w", err)
	}
	cfg.MaxConns = 10
	cfg.MinConns = 0
	cfg.MaxConnLifetime = time.Hour
	cfg.MaxConnLifetimeJitter = 5 * time.Minute
	cfg.MaxConnIdleTime = 15 * time.Minute
	cfg.HealthCheckPeriod = time.Minute

	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		return nil, fmt.Errorf("store: connect: %w", err)
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, fmt.Errorf("store: ping: %w", err)
	}
	if err := migrate(ctx, pool); err != nil {
		pool.Close()
		return nil, err
	}
	return &Postgres{pool: pool}, nil
}

// Close releases the connection pool.
func (p *Postgres) Close() { p.pool.Close() }

// Ping checks the database is reachable.
func (p *Postgres) Ping(ctx context.Context) error { return p.pool.Ping(ctx) }

var hashRE = regexp.MustCompile(`^[0-9a-f]{40}$`)

// normHash lowercases and validates an info hash.
func normHash(h string) (string, error) {
	n := strings.ToLower(strings.TrimSpace(h))
	if !hashRE.MatchString(n) {
		return "", fmt.Errorf("store: invalid info hash %q", h)
	}
	return n, nil
}

func normHashes(hs []string) ([]string, error) {
	out := make([]string, len(hs))
	for i, h := range hs {
		n, err := normHash(h)
		if err != nil {
			return nil, err
		}
		out[i] = n
	}
	return out, nil
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// isFKViolation reports a foreign key violation (referenced book/release does not exist).
func isFKViolation(err error) bool {
	var pgErr *pgconn.PgError
	return errors.As(err, &pgErr) && pgErr.Code == "23503"
}

// ---- books ------------------------------------------------------------------------------

func (p *Postgres) GetBook(ctx context.Context, asin string) (*service.BookRecord, error) {
	var (
		b  model.Book
		at time.Time
	)
	err := p.pool.QueryRow(ctx, `SELECT asin, title, subtitle, authors, narrators, series,
		series_position, language, runtime_min, cover_url, description, region, release_year,
		genres, fetched_at FROM books WHERE asin = $1`, asin).Scan(
		&b.ASIN, &b.Title, &b.Subtitle, &b.Authors, &b.Narrators, &b.Series,
		&b.SeriesPosition, &b.Language, &b.RuntimeMin, &b.CoverURL, &b.Description, &b.Region,
		&b.ReleaseYear, &b.Genres, &at)
	if errors.Is(err, pgx.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("store: get book: %w", err)
	}
	return &service.BookRecord{Book: b, FetchedAt: at.UTC()}, nil
}

func (p *Postgres) UpsertBook(ctx context.Context, b model.Book) error {
	if b.ASIN == "" {
		return errors.New("store: upsert book: empty ASIN")
	}
	_, err := p.pool.Exec(ctx, `INSERT INTO books (asin, title, subtitle, authors, narrators,
		series, series_position, language, runtime_min, cover_url, description, region,
		release_year, genres, fetched_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14, now())
		ON CONFLICT (asin) DO UPDATE SET
		  title = EXCLUDED.title, subtitle = EXCLUDED.subtitle, authors = EXCLUDED.authors,
		  narrators = EXCLUDED.narrators, series = EXCLUDED.series,
		  series_position = EXCLUDED.series_position, language = EXCLUDED.language,
		  runtime_min = EXCLUDED.runtime_min, cover_url = EXCLUDED.cover_url,
		  description = EXCLUDED.description, region = EXCLUDED.region,
		  release_year = EXCLUDED.release_year, genres = EXCLUDED.genres,
		  fetched_at = now()`,
		b.ASIN, b.Title, b.Subtitle, nonNil(b.Authors), nonNil(b.Narrators), b.Series,
		b.SeriesPosition, b.Language, b.RuntimeMin, b.CoverURL, b.Description, b.Region,
		b.ReleaseYear, nonNil(b.Genres))
	if err != nil {
		return fmt.Errorf("store: upsert book: %w", err)
	}
	return nil
}

// ---- releases ---------------------------------------------------------------------------

// UpsertReleases inserts or updates releases in one transaction. first_seen and the stored
// files are kept for existing rows. Duplicate hashes in rs: the last one wins.
func (p *Postgres) UpsertReleases(ctx context.Context, rs []model.Release) error {
	if len(rs) == 0 {
		return nil
	}
	batch := &pgx.Batch{}
	for _, r := range rs {
		h, err := normHash(r.InfoHash)
		if err != nil {
			return err
		}
		parsed, err := json.Marshal(r.Parsed)
		if err != nil {
			return fmt.Errorf("store: upsert releases: %w", err)
		}
		var published *time.Time
		if !r.PublishedAt.IsZero() {
			t := r.PublishedAt
			published = &t
		}
		batch.Queue(`INSERT INTO releases (info_hash, title, source, magnet, size, seeders,
			published_at, parsed, first_seen, updated_at)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8, now(), now())
			ON CONFLICT (info_hash) DO UPDATE SET
			  title = EXCLUDED.title, source = EXCLUDED.source, magnet = EXCLUDED.magnet,
			  size = EXCLUDED.size, seeders = EXCLUDED.seeders,
			  published_at = EXCLUDED.published_at, parsed = EXCLUDED.parsed,
			  updated_at = now()`,
			h, r.Title, r.Source, r.Magnet, r.Size, r.Seeders, published, parsed)
	}
	return p.inTx(ctx, "upsert releases", func(tx pgx.Tx) error {
		return execBatch(ctx, tx, batch)
	})
}

// SetMatches replaces the book's release links in one transaction. Matches whose hash is not
// a stored release are silently skipped (call UpsertReleases first); an invalid hash is an
// error and a book that is not stored yields service.ErrNotFound (when ms has any entry that
// references an existing release). Duplicate hashes keep the highest score.
func (p *Postgres) SetMatches(ctx context.Context, asin string, ms []service.Match) error {
	best := make(map[string]float64, len(ms))
	order := make([]string, 0, len(ms))
	for _, m := range ms {
		h, err := normHash(m.InfoHash)
		if err != nil {
			return err
		}
		if s, ok := best[h]; !ok {
			best[h] = m.Score
			order = append(order, h)
		} else if m.Score > s {
			best[h] = m.Score
		}
	}
	err := p.inTx(ctx, "set matches", func(tx pgx.Tx) error {
		if err := lockKey(ctx, tx, "book_releases:"+asin); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "DELETE FROM book_releases WHERE asin = $1", asin); err != nil {
			return err
		}
		batch := &pgx.Batch{}
		for _, h := range order {
			batch.Queue(`INSERT INTO book_releases (asin, info_hash, score)
				SELECT $1::text, r.info_hash, $3::double precision
				FROM releases r WHERE r.info_hash = $2::text`, asin, h, best[h])
		}
		return execBatch(ctx, tx, batch)
	})
	if isFKViolation(err) {
		return fmt.Errorf("store: set matches: book %q: %w", asin, service.ErrNotFound)
	}
	return err
}

func (p *Postgres) Candidates(ctx context.Context, asin string) ([]service.Candidate, error) {
	rows, err := p.pool.Query(ctx, `SELECT r.info_hash, r.title, r.source, r.magnet, r.size,
		r.seeders, r.published_at, r.parsed, br.score
		FROM book_releases br JOIN releases r ON r.info_hash = br.info_hash
		WHERE br.asin = $1
		ORDER BY br.score DESC, r.seeders DESC, r.info_hash`, asin)
	if err != nil {
		return nil, fmt.Errorf("store: candidates: %w", err)
	}
	defer rows.Close()
	var out []service.Candidate
	for rows.Next() {
		var (
			c         service.Candidate
			published *time.Time
			parsed    []byte
		)
		if err := rows.Scan(&c.Release.InfoHash, &c.Release.Title, &c.Release.Source,
			&c.Release.Magnet, &c.Release.Size, &c.Release.Seeders, &published, &parsed,
			&c.Score); err != nil {
			return nil, fmt.Errorf("store: candidates: %w", err)
		}
		if published != nil {
			c.Release.PublishedAt = published.UTC()
		}
		if len(parsed) > 0 {
			if err := json.Unmarshal(parsed, &c.Release.Parsed); err != nil {
				return nil, fmt.Errorf("store: candidates: parsed %s: %w", c.Release.InfoHash, err)
			}
		}
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("store: candidates: %w", err)
	}
	return out, nil
}

func (p *Postgres) ReleasesSearchedAt(ctx context.Context, asin string) (time.Time, error) {
	var at time.Time
	err := p.pool.QueryRow(ctx, "SELECT searched_at FROM release_searches WHERE asin = $1", asin).Scan(&at)
	if errors.Is(err, pgx.ErrNoRows) {
		return time.Time{}, nil
	}
	if err != nil {
		return time.Time{}, fmt.Errorf("store: releases searched at: %w", err)
	}
	return at.UTC(), nil
}

func (p *Postgres) MarkReleasesSearched(ctx context.Context, asin string, at time.Time) error {
	_, err := p.pool.Exec(ctx, `INSERT INTO release_searches (asin, searched_at) VALUES ($1, $2)
		ON CONFLICT (asin) DO UPDATE SET searched_at = EXCLUDED.searched_at`, asin, at)
	if err != nil {
		return fmt.Errorf("store: mark releases searched: %w", err)
	}
	return nil
}

// ---- files ------------------------------------------------------------------------------

// SaveFiles replaces the stored file list of a release in one transaction. An unknown release
// yields service.ErrNotFound; duplicate file indexes are an error.
func (p *Postgres) SaveFiles(ctx context.Context, infoHash string, files []model.File) error {
	h, err := normHash(infoHash)
	if err != nil {
		return err
	}
	err = p.inTx(ctx, "save files", func(tx pgx.Tx) error {
		if err := lockKey(ctx, tx, "release_files:"+h); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "DELETE FROM release_files WHERE info_hash = $1", h); err != nil {
			return err
		}
		batch := &pgx.Batch{}
		for _, f := range files {
			batch.Queue("INSERT INTO release_files (info_hash, idx, path, size) VALUES ($1,$2,$3,$4)",
				h, f.Index, f.Path, f.Size)
		}
		return execBatch(ctx, tx, batch)
	})
	if isFKViolation(err) {
		return fmt.Errorf("store: save files: release %s: %w", h, service.ErrNotFound)
	}
	return err
}

func (p *Postgres) Files(ctx context.Context, infoHashes []string) (map[string][]model.File, error) {
	out := map[string][]model.File{}
	if len(infoHashes) == 0 {
		return out, nil
	}
	hs, err := normHashes(infoHashes)
	if err != nil {
		return nil, err
	}
	rows, err := p.pool.Query(ctx, `SELECT info_hash, idx, path, size FROM release_files
		WHERE info_hash = ANY($1) ORDER BY info_hash, idx`, hs)
	if err != nil {
		return nil, fmt.Errorf("store: files: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var (
			h string
			f model.File
		)
		if err := rows.Scan(&h, &f.Index, &f.Path, &f.Size); err != nil {
			return nil, fmt.Errorf("store: files: %w", err)
		}
		out[h] = append(out[h], f)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("store: files: %w", err)
	}
	return out, nil
}

// ---- availability -----------------------------------------------------------------------

// SetAvailability upserts cache states. A zero CheckedAt means "now".
func (p *Postgres) SetAvailability(ctx context.Context, provider string, entries []service.Availability) error {
	if len(entries) == 0 {
		return nil
	}
	batch := &pgx.Batch{}
	for _, e := range entries {
		h, err := normHash(e.InfoHash)
		if err != nil {
			return err
		}
		at := e.CheckedAt
		if at.IsZero() {
			at = time.Now()
		}
		batch.Queue(`INSERT INTO availability (provider, info_hash, cached, checked_at)
			VALUES ($1,$2,$3,$4)
			ON CONFLICT (provider, info_hash) DO UPDATE SET
			  cached = EXCLUDED.cached, checked_at = EXCLUDED.checked_at`,
			provider, h, e.Cached, at)
	}
	return p.inTx(ctx, "set availability", func(tx pgx.Tx) error {
		return execBatch(ctx, tx, batch)
	})
}

func (p *Postgres) GetAvailability(ctx context.Context, provider string, infoHashes []string, newerThan time.Time) (map[string]service.Availability, error) {
	out := map[string]service.Availability{}
	if len(infoHashes) == 0 {
		return out, nil
	}
	hs, err := normHashes(infoHashes)
	if err != nil {
		return nil, err
	}
	rows, err := p.pool.Query(ctx, `SELECT info_hash, cached, checked_at FROM availability
		WHERE provider = $1 AND info_hash = ANY($2) AND ($3::timestamptz IS NULL OR checked_at >= $3)`,
		provider, hs, nullTime(newerThan))
	if err != nil {
		return nil, fmt.Errorf("store: get availability: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var (
			a  service.Availability
			at time.Time
		)
		if err := rows.Scan(&a.InfoHash, &a.Cached, &at); err != nil {
			return nil, fmt.Errorf("store: get availability: %w", err)
		}
		a.CheckedAt = at.UTC()
		out[a.InfoHash] = a
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("store: get availability: %w", err)
	}
	return out, nil
}

// ---- helpers ----------------------------------------------------------------------------

func nullTime(t time.Time) *time.Time {
	if t.IsZero() {
		return nil
	}
	return &t
}

// inTx runs fn in a transaction, rolling back on error. Errors are wrapped with op, keeping
// the chain intact for errors.Is / errors.As.
func (p *Postgres) inTx(ctx context.Context, op string, fn func(tx pgx.Tx) error) error {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return fmt.Errorf("store: %s: %w", op, err)
	}
	if err := fn(tx); err != nil {
		_ = tx.Rollback(context.WithoutCancel(ctx))
		return fmt.Errorf("store: %s: %w", op, err)
	}
	if err := tx.Commit(ctx); err != nil {
		return fmt.Errorf("store: %s: %w", op, err)
	}
	return nil
}

// lockKey takes a transaction-scoped advisory lock so concurrent delete-then-insert
// replacements of the same key serialise instead of colliding on the primary key.
func lockKey(ctx context.Context, tx pgx.Tx, key string) error {
	_, err := tx.Exec(ctx, "SELECT pg_advisory_xact_lock(hashtextextended($1, 0))", key)
	return err
}

// execBatch sends the batch and checks every statement's result.
func execBatch(ctx context.Context, tx pgx.Tx, b *pgx.Batch) error {
	if b.Len() == 0 {
		return nil
	}
	br := tx.SendBatch(ctx, b)
	for i := 0; i < b.Len(); i++ {
		if _, err := br.Exec(); err != nil {
			_ = br.Close()
			return err
		}
	}
	return br.Close()
}
