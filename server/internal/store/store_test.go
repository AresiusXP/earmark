package store

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"math"
	"net/url"
	"os"
	"reflect"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
)

// newSchemaURL creates a fresh schema and returns a database URL whose search_path points
// at it. The schema is dropped when the test ends.
func newSchemaURL(t *testing.T) string {
	t.Helper()
	base := os.Getenv("TEST_DATABASE_URL")
	if base == "" {
		t.Skip("TEST_DATABASE_URL not set")
	}
	ctx := context.Background()
	var b [6]byte
	if _, err := rand.Read(b[:]); err != nil {
		t.Fatal(err)
	}
	schema := "t_" + hex.EncodeToString(b[:])

	admin, err := pgx.Connect(ctx, base)
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	if _, err := admin.Exec(ctx, "CREATE SCHEMA "+schema); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_, _ = admin.Exec(context.Background(), "DROP SCHEMA "+schema+" CASCADE")
		_ = admin.Close(context.Background())
	})

	u, err := url.Parse(base)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	q.Set("search_path", schema)
	u.RawQuery = q.Encode()
	return u.String()
}

func openStore(t *testing.T) *Postgres {
	t.Helper()
	s, err := Open(context.Background(), newSchemaURL(t))
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(s.Close)
	return s
}

func hash(c byte) string {
	b := make([]byte, 40)
	for i := range b {
		b[i] = "0123456789abcdef"[int(c)%16]
	}
	b[0] = "0123456789abcdef"[(int(c)/16)%16]
	return string(b)
}

func sampleBook(asin string) model.Book {
	return model.Book{
		ASIN: asin, Title: "Dune", Subtitle: "Book One", Authors: []string{"Frank Herbert"},
		Narrators: []string{"Scott Brick", "Orlagh Cassidy"}, Series: "Dune", SeriesPosition: "1",
		Language: "en", RuntimeMin: 1275, CoverURL: "https://x/c.jpg", Description: "Spice.",
		Region: "us", ReleaseYear: 2007, Genres: []string{"Sci-Fi", "Classics"},
	}
}

func sampleRelease(h string, seeders int) model.Release {
	unabridged := true
	return model.Release{
		InfoHash: h, Title: "Frank Herbert - Dune [m4b]", Source: "knaben",
		Magnet: "magnet:?xt=urn:btih:" + h, Size: 1 << 33, Seeders: seeders,
		PublishedAt: time.Date(2024, 5, 1, 12, 0, 0, 0, time.UTC),
		Parsed: model.ParsedTitle{
			Authors: []string{"Frank Herbert"}, Title: "Dune", Narrator: "Scott Brick", Year: 2007,
			Format: "m4b", BitrateKbps: 64, Language: "en", Unabridged: &unabridged,
			Series: "Dune", SeriesPosition: "1",
		},
	}
}

func count(t *testing.T, s *Postgres, query string, args ...any) int {
	t.Helper()
	var n int
	if err := s.pool.QueryRow(context.Background(), query, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

// Links stored before migration 0002 must survive it as plain (non-collection) matches.
func TestMigrationAddsCollectionColumn(t *testing.T) {
	url := newSchemaURL(t)
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	ms, err := loadMigrations()
	if err != nil || len(ms) < 2 {
		t.Fatalf("migrations: %v %v", ms, err)
	}
	h := hash(0x42)
	for _, q := range []string{
		ms[0].sql,
		`CREATE TABLE schema_migrations (version integer PRIMARY KEY, name text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())`,
		`INSERT INTO schema_migrations (version, name) VALUES (1, '` + ms[0].name + `')`,
		`INSERT INTO books (asin) VALUES ('B1')`,
		`INSERT INTO releases (info_hash) VALUES ('` + h + `')`,
		`INSERT INTO book_releases (asin, info_hash, score) VALUES ('B1', '` + h + `', 0.8)`,
	} {
		if _, err := conn.Exec(ctx, q); err != nil {
			t.Fatalf("%s: %v", q, err)
		}
	}
	s, err := Open(ctx, url)
	if err != nil {
		t.Fatalf("Open on a version-1 database: %v", err)
	}
	defer s.Close()
	cands, err := s.Candidates(ctx, "B1")
	if err != nil || len(cands) != 1 || cands[0].Score != 0.8 || cands[0].Collection {
		t.Errorf("candidates after migration = %+v, %v", cands, err)
	}
}

func TestMigrationsIdempotent(t *testing.T) {
	url := newSchemaURL(t)
	ctx := context.Background()
	for i := 0; i < 3; i++ {
		s, err := Open(ctx, url)
		if err != nil {
			t.Fatalf("Open #%d: %v", i, err)
		}
		ms, _ := loadMigrations()
		if n := count(t, s, "SELECT count(*) FROM schema_migrations"); n != len(ms) {
			t.Errorf("schema_migrations rows = %d, want %d", n, len(ms))
		}
		if err := s.Ping(ctx); err != nil {
			t.Errorf("Ping: %v", err)
		}
		s.Close()
	}
}

func TestConcurrentOpen(t *testing.T) {
	url := newSchemaURL(t)
	ctx := context.Background()
	const n = 8
	var wg sync.WaitGroup
	stores := make([]*Postgres, n)
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			stores[i], errs[i] = Open(ctx, url)
		}()
	}
	wg.Wait()
	for i := 0; i < n; i++ {
		if errs[i] != nil {
			t.Fatalf("Open #%d: %v", i, errs[i])
		}
		defer stores[i].Close()
	}
	ms, _ := loadMigrations()
	if got := count(t, stores[0], "SELECT count(*) FROM schema_migrations"); got != len(ms) {
		t.Errorf("schema_migrations rows = %d, want %d", got, len(ms))
	}
}

func TestOpenBadURL(t *testing.T) {
	if _, err := Open(context.Background(), "not a url ://"); err == nil {
		t.Fatal("expected error")
	}
}

func TestBooks(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()

	got, err := s.GetBook(ctx, "B000")
	if err != nil || got != nil {
		t.Fatalf("missing book = %v, %v; want nil, nil", got, err)
	}

	b := sampleBook("B000")
	before := time.Now().Add(-time.Second)
	if err := s.UpsertBook(ctx, b); err != nil {
		t.Fatal(err)
	}
	rec, err := s.GetBook(ctx, "B000")
	if err != nil || rec == nil {
		t.Fatalf("GetBook = %v, %v", rec, err)
	}
	if !reflect.DeepEqual(rec.Book, b) {
		t.Errorf("book = %+v, want %+v", rec.Book, b)
	}
	if rec.FetchedAt.Before(before) || rec.FetchedAt.After(time.Now().Add(time.Second)) {
		t.Errorf("FetchedAt = %v, want ~now", rec.FetchedAt)
	}
	first := rec.FetchedAt

	// Replace: every field overwritten, FetchedAt refreshed, nil slices become empty.
	b2 := model.Book{ASIN: "B000", Title: "Dune (2)"}
	if err := s.UpsertBook(ctx, b2); err != nil {
		t.Fatal(err)
	}
	rec, _ = s.GetBook(ctx, "B000")
	if rec.Book.Title != "Dune (2)" || rec.Book.Series != "" || len(rec.Book.Authors) != 0 || rec.Book.RuntimeMin != 0 {
		t.Errorf("not replaced: %+v", rec.Book)
	}
	if !rec.FetchedAt.After(first) {
		t.Errorf("FetchedAt not refreshed: %v then %v", first, rec.FetchedAt)
	}

	if err := s.UpsertBook(ctx, model.Book{}); err == nil {
		t.Error("empty ASIN accepted")
	}
}

func TestReleasesAndCandidates(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()

	cands, err := s.Candidates(ctx, "B000")
	if err != nil || len(cands) != 0 {
		t.Fatalf("missing candidates = %v, %v", cands, err)
	}

	if err := s.UpsertBook(ctx, sampleBook("B000")); err != nil {
		t.Fatal(err)
	}
	a, b, c := hash(0xa1), hash(0xb2), hash(0xc3)
	rs := []model.Release{sampleRelease(a, 5), sampleRelease(b, 50), sampleRelease(c, 10)}
	rs[2].PublishedAt = time.Time{} // unknown publication date
	if err := s.UpsertReleases(ctx, rs); err != nil {
		t.Fatal(err)
	}
	if err := s.UpsertReleases(ctx, nil); err != nil {
		t.Fatal(err)
	}

	// Order: score desc, then seeders desc.
	ms := []service.Match{{InfoHash: a, Score: 0.9}, {InfoHash: b, Score: 0.9}, {InfoHash: c, Score: 0.95}}
	ms[1].Collection = true
	if err := s.SetMatches(ctx, "B000", ms); err != nil {
		t.Fatal(err)
	}
	cands, err = s.Candidates(ctx, "B000")
	if err != nil {
		t.Fatal(err)
	}
	if len(cands) != 3 || cands[0].Release.InfoHash != c || cands[1].Release.InfoHash != b || cands[2].Release.InfoHash != a {
		t.Fatalf("order wrong: %+v", cands)
	}
	if cands[0].Score != 0.95 {
		t.Errorf("score = %v, want exactly 0.95", cands[0].Score)
	}
	if cands[0].Collection || !cands[1].Collection || cands[2].Collection {
		t.Errorf("collection flags = %v %v %v, want false true false", cands[0].Collection, cands[1].Collection, cands[2].Collection)
	}
	if !reflect.DeepEqual(cands[1].Release, rs[1]) {
		t.Errorf("release round trip:\n got %+v\nwant %+v", cands[1].Release, rs[1])
	}
	if !cands[0].Release.PublishedAt.IsZero() {
		t.Errorf("PublishedAt = %v, want zero", cands[0].Release.PublishedAt)
	}
}

func TestUpsertReleasesSemantics(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	h := hash(0x11)

	if err := s.UpsertBook(ctx, sampleBook("B1")); err != nil {
		t.Fatal(err)
	}
	if err := s.UpsertReleases(ctx, []model.Release{sampleRelease(h, 1)}); err != nil {
		t.Fatal(err)
	}
	if err := s.SaveFiles(ctx, h, []model.File{{Index: 0, Path: "a.mp3", Size: 1}}); err != nil {
		t.Fatal(err)
	}
	var firstSeen, updated1 time.Time
	if err := s.pool.QueryRow(ctx, "SELECT first_seen, updated_at FROM releases WHERE info_hash=$1", h).Scan(&firstSeen, &updated1); err != nil {
		t.Fatal(err)
	}

	upd := sampleRelease(h, 99)
	upd.Title = "new title"
	upd.Size = 42
	upd.PublishedAt = time.Time{}
	upd.Parsed = model.ParsedTitle{Title: "x"}
	// Duplicate in the same batch: last one wins. Upper-case hash is normalised.
	dup := upd
	dup.InfoHash = "  " + upperHex(h) + " "
	dup.Seeders = 100
	if err := s.UpsertReleases(ctx, []model.Release{upd, dup}); err != nil {
		t.Fatal(err)
	}
	if err := s.SetMatches(ctx, "B1", []service.Match{{InfoHash: h, Score: 1}}); err != nil {
		t.Fatal(err)
	}
	cands, _ := s.Candidates(ctx, "B1")
	if len(cands) != 1 {
		t.Fatalf("cands = %+v", cands)
	}
	r := cands[0].Release
	if r.Title != "new title" || r.Size != 42 || r.Seeders != 100 || !r.PublishedAt.IsZero() || r.Parsed.Title != "x" || r.Parsed.Unabridged != nil {
		t.Errorf("not updated: %+v", r)
	}
	var firstSeen2, updated2 time.Time
	if err := s.pool.QueryRow(ctx, "SELECT first_seen, updated_at FROM releases WHERE info_hash=$1", h).Scan(&firstSeen2, &updated2); err != nil {
		t.Fatal(err)
	}
	if !firstSeen2.Equal(firstSeen) {
		t.Errorf("first_seen changed: %v -> %v", firstSeen, firstSeen2)
	}
	if !updated2.After(updated1) {
		t.Errorf("updated_at not bumped")
	}
	files, _ := s.Files(ctx, []string{h})
	if len(files[h]) != 1 {
		t.Errorf("files wiped by upsert: %+v", files)
	}

	// Bad hash anywhere in the batch fails the whole batch.
	bad := sampleRelease(hash(0x22), 1)
	if err := s.UpsertReleases(ctx, []model.Release{bad, {InfoHash: "xyz"}}); err == nil {
		t.Error("bad hash accepted")
	}
	if n := count(t, s, "SELECT count(*) FROM releases"); n != 1 {
		t.Errorf("releases = %d, want 1 (batch must be atomic)", n)
	}
}

func upperHex(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'a' && c <= 'f' {
			b[i] = c - 32
		}
	}
	return string(b)
}

func TestSetMatchesReplace(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	if err := s.UpsertBook(ctx, sampleBook("B1")); err != nil {
		t.Fatal(err)
	}
	if err := s.UpsertBook(ctx, sampleBook("B2")); err != nil {
		t.Fatal(err)
	}
	a, b, unknown := hash(1), hash(2), hash(3)
	if err := s.UpsertReleases(ctx, []model.Release{sampleRelease(a, 1), sampleRelease(b, 2)}); err != nil {
		t.Fatal(err)
	}
	must := func(err error) {
		t.Helper()
		if err != nil {
			t.Fatal(err)
		}
	}
	must(s.SetMatches(ctx, "B1", []service.Match{{InfoHash: a, Score: 0.5}, {InfoHash: b, Score: 0.6}}))
	must(s.SetMatches(ctx, "B2", []service.Match{{InfoHash: a, Score: 0.1}}))

	// Replace B1: b dropped, unknown hash ignored, duplicate keeps the best score.
	must(s.SetMatches(ctx, "B1", []service.Match{
		{InfoHash: a, Score: 0.2}, {InfoHash: a, Score: 0.8}, {InfoHash: unknown, Score: 1},
	}))
	cands, _ := s.Candidates(ctx, "B1")
	if len(cands) != 1 || cands[0].Release.InfoHash != a || cands[0].Score != 0.8 || cands[0].Collection {
		t.Errorf("B1 = %+v", cands)
	}

	// A duplicate keeps the flag of its best-scoring entry.
	must(s.SetMatches(ctx, "B1", []service.Match{
		{InfoHash: a, Score: 0.9, Collection: true}, {InfoHash: a, Score: 0.7, Collection: false},
	}))
	if cands, _ := s.Candidates(ctx, "B1"); len(cands) != 1 || cands[0].Score != 0.9 || !cands[0].Collection {
		t.Errorf("duplicate flag: %+v", cands)
	}
	if other, _ := s.Candidates(ctx, "B2"); len(other) != 1 || other[0].Score != 0.1 {
		t.Errorf("B2 touched: %+v", other)
	}

	// Empty replaces with nothing.
	must(s.SetMatches(ctx, "B1", nil))
	if cands, _ := s.Candidates(ctx, "B1"); len(cands) != 0 {
		t.Errorf("not cleared: %+v", cands)
	}

	// Bad hash fails and leaves the previous links alone.
	must(s.SetMatches(ctx, "B1", []service.Match{{InfoHash: a, Score: 1}}))
	if err := s.SetMatches(ctx, "B1", []service.Match{{InfoHash: b, Score: 1}, {InfoHash: "nope"}}); err == nil {
		t.Error("bad hash accepted")
	}
	if cands, _ := s.Candidates(ctx, "B1"); len(cands) != 1 || cands[0].Release.InfoHash != a {
		t.Errorf("failed SetMatches changed links: %+v", cands)
	}

	// Unknown book.
	err := s.SetMatches(ctx, "NOPE", []service.Match{{InfoHash: a, Score: 1}})
	if !errors.Is(err, service.ErrNotFound) {
		t.Errorf("unknown book err = %v, want ErrNotFound", err)
	}
}

func TestReleasesSearched(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	at, err := s.ReleasesSearchedAt(ctx, "B1")
	if err != nil || !at.IsZero() {
		t.Fatalf("never searched = %v, %v", at, err)
	}
	t1 := time.Date(2024, 1, 2, 3, 4, 5, 0, time.UTC)
	t2 := t1.Add(time.Hour)
	for _, want := range []time.Time{t1, t2} {
		if err := s.MarkReleasesSearched(ctx, "B1", want); err != nil {
			t.Fatal(err)
		}
		got, err := s.ReleasesSearchedAt(ctx, "B1")
		if err != nil || !got.Equal(want) {
			t.Errorf("got %v, %v; want %v", got, err, want)
		}
	}
	if got, _ := s.ReleasesSearchedAt(ctx, "B2"); !got.IsZero() {
		t.Errorf("B2 = %v", got)
	}
}

func TestFiles(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	a, b, missing := hash(0xa), hash(0xb), hash(0xc)

	empty, err := s.Files(ctx, nil)
	if err != nil || empty == nil || len(empty) != 0 {
		t.Fatalf("Files(nil) = %v, %v", empty, err)
	}
	if err := s.UpsertReleases(ctx, []model.Release{sampleRelease(a, 1), sampleRelease(b, 1)}); err != nil {
		t.Fatal(err)
	}
	// Unsorted input comes back ordered by index.
	first := []model.File{{Index: 2, Path: "c.mp3", Size: 3}, {Index: 0, Path: "a.mp3", Size: 1}, {Index: 1, Path: "b.mp3", Size: 2}}
	if err := s.SaveFiles(ctx, a, first); err != nil {
		t.Fatal(err)
	}
	if err := s.SaveFiles(ctx, b, []model.File{{Index: 0, Path: "only.m4b", Size: 9}}); err != nil {
		t.Fatal(err)
	}
	got, err := s.Files(ctx, []string{a, b, missing})
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := got[missing]; ok || len(got) != 2 {
		t.Errorf("missing hash present: %+v", got)
	}
	want := []model.File{{Index: 0, Path: "a.mp3", Size: 1}, {Index: 1, Path: "b.mp3", Size: 2}, {Index: 2, Path: "c.mp3", Size: 3}}
	if !reflect.DeepEqual(got[a], want) {
		t.Errorf("files a = %+v", got[a])
	}

	// Replace.
	if err := s.SaveFiles(ctx, a, []model.File{{Index: 5, Path: "z.mp3", Size: 7}}); err != nil {
		t.Fatal(err)
	}
	got, _ = s.Files(ctx, []string{upperHex(a)})
	if !reflect.DeepEqual(got[a], []model.File{{Index: 5, Path: "z.mp3", Size: 7}}) {
		t.Errorf("not replaced: %+v", got)
	}
	if other, _ := s.Files(ctx, []string{b}); len(other[b]) != 1 {
		t.Errorf("b touched: %+v", other)
	}

	// Failed replace (duplicate index) rolls back and keeps the old list.
	if err := s.SaveFiles(ctx, a, []model.File{{Index: 1, Path: "x"}, {Index: 1, Path: "y"}}); err == nil {
		t.Error("duplicate index accepted")
	}
	got, _ = s.Files(ctx, []string{a})
	if len(got[a]) != 1 || got[a][0].Index != 5 {
		t.Errorf("rollback failed: %+v", got)
	}

	// Empty list clears.
	if err := s.SaveFiles(ctx, a, nil); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.Files(ctx, []string{a}); len(got) != 0 {
		t.Errorf("not cleared: %+v", got)
	}

	// Unknown release and bad hashes.
	if err := s.SaveFiles(ctx, missing, []model.File{{Index: 0, Path: "p"}}); !errors.Is(err, service.ErrNotFound) {
		t.Errorf("unknown release err = %v, want ErrNotFound", err)
	}
	if err := s.SaveFiles(ctx, "bad", nil); err == nil {
		t.Error("bad hash accepted by SaveFiles")
	}
	if _, err := s.Files(ctx, []string{a, "bad"}); err == nil {
		t.Error("bad hash accepted by Files")
	}
}

func TestSaveFilesIfAbsentFirstWriterWins(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	h := hash(0x61)
	if err := s.UpsertReleases(ctx, []model.Release{sampleRelease(h, 1)}); err != nil {
		t.Fatal(err)
	}
	if ok, err := s.SaveFilesIfAbsent(ctx, h, nil); ok || err != nil {
		t.Fatalf("empty list: %v %v", ok, err)
	}
	if ok, err := s.SaveFilesIfAbsent(ctx, h, []model.File{{Index: 1, Path: "x"}, {Index: 1, Path: "y"}}); ok || err == nil {
		t.Fatalf("duplicate index accepted: %v %v", ok, err)
	}
	if got, _ := s.Files(ctx, []string{h}); len(got) != 0 {
		t.Fatalf("a failed write left rows: %v", got)
	}
	if _, err := s.SaveFilesIfAbsent(ctx, hash(0x62), []model.File{{Index: 0, Path: "p"}}); !errors.Is(err, service.ErrNotFound) {
		t.Fatalf("unknown release err = %v", err)
	}

	// Concurrent first writers with different lists: exactly one list is stored, whole.
	lists := make([][]model.File, 8)
	for i := range lists {
		for j := 0; j <= i; j++ {
			lists[i] = append(lists[i], model.File{Index: j * 10, Path: "w" + string(rune('a'+i)) + ".mp3", Size: int64(i)})
		}
	}
	var wg sync.WaitGroup
	var winners atomic.Int32
	for _, l := range lists {
		wg.Add(1)
		go func() {
			defer wg.Done()
			ok, err := s.SaveFilesIfAbsent(ctx, h, l)
			if err != nil {
				t.Error(err)
			}
			if ok {
				winners.Add(1)
			}
		}()
	}
	wg.Wait()
	if winners.Load() != 1 {
		t.Fatalf("winners = %d, want 1", winners.Load())
	}
	got, _ := s.Files(ctx, []string{h})
	stored := got[h]
	matched := false
	for _, l := range lists {
		if reflect.DeepEqual(stored, l) {
			matched = true
		}
	}
	if !matched {
		t.Fatalf("stored list is a mix of writers: %+v", stored)
	}
	if ok, err := s.SaveFilesIfAbsent(ctx, h, []model.File{{Index: 0, Path: "late.mp3"}}); ok || err != nil {
		t.Fatalf("late writer: %v %v", ok, err)
	}
	if again, _ := s.Files(ctx, []string{h}); !reflect.DeepEqual(again[h], stored) {
		t.Fatal("stored list changed")
	}
}

func TestAvailability(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	a, b, c := hash(0x1a), hash(0x1b), hash(0x1c)
	now := time.Now().UTC().Truncate(time.Microsecond)

	empty, err := s.GetAvailability(ctx, "torbox", nil, time.Time{})
	if err != nil || empty == nil || len(empty) != 0 {
		t.Fatalf("empty = %v, %v", empty, err)
	}
	if got, err := s.GetAvailability(ctx, "torbox", []string{a}, time.Time{}); err != nil || len(got) != 0 {
		t.Fatalf("missing = %v, %v", got, err)
	}

	if err := s.SetAvailability(ctx, "torbox", []service.Availability{
		{InfoHash: a, Cached: true, CheckedAt: now.Add(-2 * time.Hour)},
		{InfoHash: b, Cached: false, CheckedAt: now.Add(-time.Minute)},
		{InfoHash: c, Cached: true}, // zero CheckedAt = now
	}); err != nil {
		t.Fatal(err)
	}
	if err := s.SetAvailability(ctx, "realdebrid", []service.Availability{{InfoHash: a, Cached: false, CheckedAt: now}}); err != nil {
		t.Fatal(err)
	}
	if err := s.SetAvailability(ctx, "torbox", nil); err != nil {
		t.Fatal(err)
	}

	all, err := s.GetAvailability(ctx, "torbox", []string{a, b, c}, time.Time{})
	if err != nil || len(all) != 3 {
		t.Fatalf("all = %+v, %v", all, err)
	}
	if !all[a].Cached || all[b].Cached || !all[a].CheckedAt.Equal(now.Add(-2*time.Hour)) || all[a].InfoHash != a {
		t.Errorf("values wrong: %+v", all)
	}

	// newerThan filtering, inclusive at the boundary.
	recent, _ := s.GetAvailability(ctx, "torbox", []string{a, b, c}, now.Add(-time.Hour))
	if len(recent) != 2 || recent[a].InfoHash != "" {
		t.Errorf("recent = %+v", recent)
	}
	exact, _ := s.GetAvailability(ctx, "torbox", []string{a}, now.Add(-2*time.Hour))
	if len(exact) != 1 {
		t.Errorf("boundary not inclusive: %+v", exact)
	}

	// Providers are independent.
	rd, _ := s.GetAvailability(ctx, "realdebrid", []string{a, b}, time.Time{})
	if len(rd) != 1 || rd[a].Cached {
		t.Errorf("realdebrid = %+v", rd)
	}

	// Upsert overwrites.
	if err := s.SetAvailability(ctx, "torbox", []service.Availability{{InfoHash: a, Cached: false, CheckedAt: now}}); err != nil {
		t.Fatal(err)
	}
	upd, _ := s.GetAvailability(ctx, "torbox", []string{a}, time.Time{})
	if upd[a].Cached || !upd[a].CheckedAt.Equal(now) {
		t.Errorf("not updated: %+v", upd)
	}

	// Bad hashes.
	if err := s.SetAvailability(ctx, "torbox", []service.Availability{{InfoHash: a}, {InfoHash: "zz"}}); err == nil {
		t.Error("bad hash accepted by SetAvailability")
	}
	if _, err := s.GetAvailability(ctx, "torbox", []string{"zz"}, time.Time{}); err == nil {
		t.Error("bad hash accepted by GetAvailability")
	}
}

func TestHashValidation(t *testing.T) {
	tests := []struct {
		in, want string
		ok       bool
	}{
		{hash(0xab), hash(0xab), true},
		{upperHex(hash(0xab)), hash(0xab), true},
		{" " + hash(1) + "\n", hash(1), true},
		{"", "", false},
		{hash(1)[:39], "", false},
		{hash(1) + "0", "", false},
		{"g" + hash(1)[1:], "", false},
	}
	for _, tt := range tests {
		got, err := normHash(tt.in)
		if (err == nil) != tt.ok || got != tt.want {
			t.Errorf("normHash(%q) = %q, %v", tt.in, got, err)
		}
	}
}

func TestDBRejectsUppercaseHash(t *testing.T) {
	s := openStore(t)
	_, err := s.pool.Exec(context.Background(), "INSERT INTO releases (info_hash) VALUES ($1)", upperHex(hash(0xab)))
	if err == nil {
		t.Fatal("check constraint did not reject uppercase hash")
	}
}

func TestCascadeDelete(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	h := hash(0x77)
	if err := s.UpsertBook(ctx, sampleBook("B1")); err != nil {
		t.Fatal(err)
	}
	if err := s.UpsertReleases(ctx, []model.Release{sampleRelease(h, 1)}); err != nil {
		t.Fatal(err)
	}
	if err := s.SetMatches(ctx, "B1", []service.Match{{InfoHash: h, Score: 1}}); err != nil {
		t.Fatal(err)
	}
	if err := s.SaveFiles(ctx, h, []model.File{{Index: 0, Path: "a"}}); err != nil {
		t.Fatal(err)
	}

	// Deleting the book removes its links but keeps the release and files.
	if _, err := s.pool.Exec(ctx, "DELETE FROM books WHERE asin = 'B1'"); err != nil {
		t.Fatal(err)
	}
	if n := count(t, s, "SELECT count(*) FROM book_releases"); n != 0 {
		t.Errorf("book_releases = %d after book delete", n)
	}
	if n := count(t, s, "SELECT count(*) FROM releases"); n != 1 {
		t.Errorf("releases = %d, want 1", n)
	}

	// Deleting a release removes links and files.
	if err := s.UpsertBook(ctx, sampleBook("B1")); err != nil {
		t.Fatal(err)
	}
	if err := s.SetMatches(ctx, "B1", []service.Match{{InfoHash: h, Score: 1}}); err != nil {
		t.Fatal(err)
	}
	if _, err := s.pool.Exec(ctx, "DELETE FROM releases WHERE info_hash = $1", h); err != nil {
		t.Fatal(err)
	}
	if n := count(t, s, "SELECT count(*) FROM book_releases"); n != 0 {
		t.Errorf("book_releases = %d after release delete", n)
	}
	if n := count(t, s, "SELECT count(*) FROM release_files"); n != 0 {
		t.Errorf("release_files = %d after release delete", n)
	}
}

func TestConcurrentWrites(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	if err := s.UpsertBook(ctx, sampleBook("B1")); err != nil {
		t.Fatal(err)
	}
	var rs []model.Release
	for i := 0; i < 20; i++ {
		rs = append(rs, sampleRelease(hash(byte(i+1)), i))
	}
	var wg sync.WaitGroup
	errs := make(chan error, 40)
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if err := s.UpsertReleases(ctx, rs); err != nil {
				errs <- err
			}
			var ms []service.Match
			for _, r := range rs {
				ms = append(ms, service.Match{InfoHash: r.InfoHash, Score: float64(r.Seeders)})
			}
			if err := s.SetMatches(ctx, "B1", ms); err != nil {
				errs <- err
			}
			if err := s.SaveFiles(ctx, rs[0].InfoHash, []model.File{{Index: 0, Path: "a"}}); err != nil {
				errs <- err
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		t.Error(err)
	}
	if cands, _ := s.Candidates(ctx, "B1"); len(cands) != 20 {
		t.Errorf("cands = %d", len(cands))
	}
}

// One absurd seeder count from an index must not fail the whole batch.
func TestUpsertReleasesClampsSeeders(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	big, neg, ok := sampleRelease(hash(0x21), 1), sampleRelease(hash(0x22), 1), sampleRelease(hash(0x23), 7)
	big.Seeders = 4294967302
	neg.Seeders = -5
	if err := s.UpsertReleases(ctx, []model.Release{big, neg, ok}); err != nil {
		t.Fatalf("batch with out-of-range seeders failed: %v", err)
	}
	for h, want := range map[string]int{hash(0x21): math.MaxInt32, hash(0x22): 0, hash(0x23): 7} {
		var got int
		if err := s.pool.QueryRow(ctx, "SELECT seeders FROM releases WHERE info_hash=$1", h).Scan(&got); err != nil || got != want {
			t.Errorf("%s: seeders = %d, %v; want %d", h[:6], got, err, want)
		}
	}
}

// Real-Debrid rows written to the shared availability table before the "only TorBox is shared"
// rule must be gone after migration 0011; TorBox rows stay.
func TestMigrationDropsLegacyNonTorBoxAvailability(t *testing.T) {
	url := newSchemaURL(t)
	ctx := context.Background()
	conn, err := pgx.Connect(ctx, url)
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close(ctx)
	ms, err := loadMigrations()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := conn.Exec(ctx, `CREATE TABLE schema_migrations (version integer PRIMARY KEY, name text NOT NULL, applied_at timestamptz NOT NULL DEFAULT now())`); err != nil {
		t.Fatal(err)
	}
	for _, m := range ms {
		if m.version >= 11 {
			continue
		}
		if _, err := conn.Exec(ctx, m.sql); err != nil {
			t.Fatalf("%s: %v", m.name, err)
		}
		if _, err := conn.Exec(ctx, `INSERT INTO schema_migrations (version, name) VALUES ($1, $2)`, m.version, m.name); err != nil {
			t.Fatal(err)
		}
	}
	h := hash(0x51)
	for _, p := range []string{"torbox", "realdebrid", "premiumize"} {
		if _, err := conn.Exec(ctx, `INSERT INTO availability (provider, info_hash, cached) VALUES ($1, $2, true)`, p, h); err != nil {
			t.Fatal(err)
		}
	}
	s, err := Open(ctx, url)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer s.Close()
	if n := count(t, s, `SELECT count(*) FROM availability`); n != 1 {
		t.Fatalf("availability rows = %d, want only the TorBox row", n)
	}
	if n := count(t, s, `SELECT count(*) FROM availability WHERE provider = 'torbox'`); n != 1 {
		t.Fatal("the TorBox row must survive")
	}
}
