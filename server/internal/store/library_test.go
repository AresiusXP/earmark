package store

import (
	"context"
	"errors"
	"fmt"
	"reflect"
	"sync"
	"testing"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/library"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/model"
)

func mkUser(t *testing.T, s *Postgres, name string) string {
	t.Helper()
	var id string
	if err := s.pool.QueryRow(context.Background(), `INSERT INTO users (display_name, role) VALUES ($1, 'user') RETURNING id::text`, name).Scan(&id); err != nil {
		t.Fatal(err)
	}
	return id
}

func mkBook(t *testing.T, s *Postgres, asin string) {
	t.Helper()
	if err := s.UpsertBook(context.Background(), sampleBook(asin)); err != nil {
		t.Fatal(err)
	}
}

func write(asin string, at int64) library.Write {
	return library.Write{ASIN: asin, Status: library.StatusListening, PositionMs: at, FilePositionMs: at / 2, Speed: 1.25, UpdatedAt: at}
}

func TestLibraryPerUserIsolation(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	a, b := mkUser(t, s, "A"), mkUser(t, s, "B")
	mkBook(t, s, "B000000001")
	if err := s.UpsertReleases(ctx, []model.Release{sampleRelease(hash(1), 1)}); err != nil {
		t.Fatal(err)
	}
	rel := hash(1)
	path := "Disc 1/01.mp3"
	w := write("B000000001", 100)
	w.ReleaseHash, w.FilePath = &rel, &path
	if _, err := s.PutEntry(ctx, a, w); err != nil {
		t.Fatal(err)
	}
	bm, err := s.CreateBookmark(ctx, a, "B000000001", 5000, "note A")
	if err != nil {
		t.Fatal(err)
	}

	// B sees nothing of A's.
	if _, err := s.GetEntry(ctx, b, "B000000001"); !errors.Is(err, library.ErrNotFound) {
		t.Fatalf("B reads A's entry: %v", err)
	}
	if es, err := s.ListEntries(ctx, b, ""); err != nil || len(es) != 0 {
		t.Fatalf("B lists %v %v", es, err)
	}
	if bs, err := s.ListBookmarks(ctx, b, "B000000001"); err != nil || len(bs) != 0 {
		t.Fatalf("B lists A's bookmarks: %v %v", bs, err)
	}
	if _, err := s.CreateBookmark(ctx, b, "B000000001", 1, "x"); !errors.Is(err, library.ErrNotFound) {
		t.Fatalf("B bookmarks a book that is only in A's library: %v", err)
	}
	// B's deletes are no-ops on A's rows.
	if err := s.DeleteBookmark(ctx, b, "B000000001", bm.ID); err != nil {
		t.Fatal(err)
	}
	if err := s.DeleteEntry(ctx, b, "B000000001"); err != nil {
		t.Fatal(err)
	}
	// B's write never touches A's entry, and A's progress is independent of B's.
	wb := write("B000000001", 999)
	wb.Status = library.StatusFinished
	if _, err := s.PutEntry(ctx, b, wb); err != nil {
		t.Fatal(err)
	}
	ea, err := s.GetEntry(ctx, a, "B000000001")
	if err != nil || ea.Status != library.StatusListening || ea.PositionMs != 100 || ea.UpdatedAt != 100 || *ea.FilePath != path || *ea.ReleaseHash != rel || ea.Book.Title != "Dune" {
		t.Fatalf("A's entry changed: %+v %v", ea, err)
	}
	if bs, _ := s.ListBookmarks(ctx, a, "B000000001"); len(bs) != 1 || bs[0].Note != "note A" {
		t.Fatalf("A's bookmark: %+v", bs)
	}
	if n := count(t, s, `SELECT count(*) FROM user_library WHERE user_id = $1::uuid`, b); n != 1 {
		t.Fatalf("B rows = %d", n)
	}

	// Deleting the user takes everything with it.
	if _, err := s.pool.Exec(ctx, `DELETE FROM users WHERE id = $1::uuid`, a); err != nil {
		t.Fatal(err)
	}
	if count(t, s, `SELECT count(*) FROM user_library WHERE user_id = $1::uuid`, a)+count(t, s, `SELECT count(*) FROM bookmarks WHERE user_id = $1::uuid`, a) != 0 {
		t.Fatal("rows of a deleted user remain")
	}
}

func TestLibraryLastWriteWins(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	u := mkUser(t, s, "A")
	mkBook(t, s, "B000000001")
	asin := "B000000001"

	got, err := s.PutEntry(ctx, u, write(asin, 1000))
	if err != nil || got.PositionMs != 1000 {
		t.Fatalf("%+v %v", got, err)
	}
	old := write(asin, 500)
	old.Status = library.StatusSaved
	got, err = s.PutEntry(ctx, u, old)
	if err != nil || got.UpdatedAt != 1000 || got.PositionMs != 1000 || got.Status != library.StatusListening {
		t.Fatalf("an older write must be ignored and the stored entry returned: %+v %v", got, err)
	}
	same := write(asin, 1000)
	same.PositionMs = 1234
	if got, _ = s.PutEntry(ctx, u, same); got.PositionMs != 1234 {
		t.Fatalf("an equal timestamp is applied (idempotent retries): %+v", got)
	}
	newer := write(asin, 2000)
	newer.Status = library.StatusFinished
	newer.Speed = 2
	if got, _ = s.PutEntry(ctx, u, newer); got.Status != library.StatusFinished || got.UpdatedAt != 2000 || got.Speed != 2 {
		t.Fatalf("%+v", got)
	}
	// A write without release/file clears them (the entry is replaced as a whole).
	if got, _ = s.PutEntry(ctx, u, write(asin, 3000)); got.ReleaseHash != nil || got.FilePath != nil {
		t.Fatalf("%+v", got)
	}

	// Ordering and the status filter.
	for i, asin := range []string{"B000000002", "B000000003"} {
		mkBook(t, s, asin)
		w := write(asin, int64(5000+i))
		w.Status = library.StatusSaved
		if _, err := s.PutEntry(ctx, u, w); err != nil {
			t.Fatal(err)
		}
	}
	es, _ := s.ListEntries(ctx, u, "")
	var order []string
	for _, e := range es {
		order = append(order, e.Book.ASIN)
	}
	if !reflect.DeepEqual(order, []string{"B000000003", "B000000002", "B000000001"}) {
		t.Fatalf("most recently touched first: %v", order)
	}
	if es, _ = s.ListEntries(ctx, u, library.StatusSaved); len(es) != 2 {
		t.Fatalf("saved = %d", len(es))
	}
	if es, _ = s.ListEntries(ctx, u, library.StatusFinished); len(es) != 0 {
		t.Fatalf("finished = %d (the 3000 write replaced status... as listening)", len(es))
	}
}

func TestLibraryFinishedAt(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	u := mkUser(t, s, "A")
	other := mkUser(t, s, "B")
	asin := "B000000001"
	mkBook(t, s, asin)
	t0 := time.Date(2026, 11, 25, 10, 0, 0, 0, time.UTC)
	put := func(user string, at int64, status string, now time.Time) library.Entry {
		t.Helper()
		w := write(asin, at)
		w.Status, w.Now = status, now
		e, err := s.PutEntry(ctx, user, w)
		if err != nil {
			t.Fatal(err)
		}
		return e
	}
	at := func(e library.Entry) string {
		if e.FinishedAt == nil {
			return "nil"
		}
		return e.FinishedAt.UTC().Format(time.RFC3339)
	}

	if e := put(u, 100, library.StatusListening, t0); e.FinishedAt != nil {
		t.Fatalf("listening has no finished_at: %s", at(e))
	}
	if e := put(u, 200, library.StatusFinished, t0.Add(time.Hour)); at(e) != "2026-11-25T11:00:00Z" {
		t.Fatalf("set when the status becomes finished: %s", at(e))
	}
	if e := put(u, 300, library.StatusFinished, t0.Add(5*time.Hour)); at(e) != "2026-11-25T11:00:00Z" {
		t.Fatalf("kept while later writes keep it finished: %s", at(e))
	}
	// An older (ignored) write changes nothing, even one that would clear it.
	if e := put(u, 250, library.StatusSaved, t0.Add(6*time.Hour)); e.Status != library.StatusFinished || at(e) != "2026-11-25T11:00:00Z" {
		t.Fatalf("ignored write: %s %s", e.Status, at(e))
	}
	// An equal timestamp is a retry: still the first finish time.
	if e := put(u, 300, library.StatusFinished, t0.Add(7*time.Hour)); at(e) != "2026-11-25T11:00:00Z" {
		t.Fatalf("retry: %s", at(e))
	}
	if e := put(u, 400, library.StatusListening, t0.Add(8*time.Hour)); e.FinishedAt != nil {
		t.Fatalf("cleared when listening again: %s", at(e))
	}
	if e := put(u, 500, library.StatusFinished, t0.Add(9*time.Hour)); at(e) != "2026-11-25T19:00:00Z" {
		t.Fatalf("finishing again sets a new time: %s", at(e))
	}
	if e := put(u, 600, library.StatusSaved, t0.Add(10*time.Hour)); e.FinishedAt != nil {
		t.Fatalf("cleared when saved: %s", at(e))
	}
	// Finished on the very first write, and per user.
	if e := put(other, 1, library.StatusFinished, t0); at(e) != "2026-11-25T10:00:00Z" {
		t.Fatalf("first write: %s", at(e))
	}
	got, err := s.GetEntry(ctx, u, asin)
	if err != nil || got.FinishedAt != nil {
		t.Fatalf("user A is unaffected by B: %+v %v", got, err)
	}
	es, err := s.ListEntries(ctx, other, library.StatusFinished)
	if err != nil || len(es) != 1 || es[0].FinishedAt == nil {
		t.Fatalf("list: %+v %v", es, err)
	}
	// The zero Now falls back to the real clock.
	w := write(asin, 700)
	w.Status = library.StatusFinished
	if e, err := s.PutEntry(ctx, u, w); err != nil || e.FinishedAt == nil || time.Since(*e.FinishedAt) > time.Minute {
		t.Fatalf("%+v %v", e, err)
	}
}

func TestLibraryReferencesAndConstraints(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	u := mkUser(t, s, "A")
	mkBook(t, s, "B000000001")

	w := write("B000000009", 1) // book not in the catalog
	if _, err := s.PutEntry(ctx, u, w); !errors.Is(err, library.ErrUnknownBook) {
		t.Fatalf("%v", err)
	}
	ghost := hash(9)
	w = write("B000000001", 1)
	w.ReleaseHash = &ghost
	if _, err := s.PutEntry(ctx, u, w); !errors.Is(err, library.ErrUnknownRelease) {
		t.Fatalf("%v", err)
	}
	for name, mut := range map[string]func(*library.Write){
		"status":   func(w *library.Write) { w.Status = "reading" },
		"speed":    func(w *library.Write) { w.Speed = 4 },
		"position": func(w *library.Write) { w.PositionMs = -1 },
		"path":     func(w *library.Write) { p := string(make([]byte, 513)); w.FilePath = &p },
	} {
		w := write("B000000001", 1)
		mut(&w)
		if _, err := s.PutEntry(ctx, u, w); err == nil {
			t.Errorf("%s: the database accepted it", name)
		}
	}
	// A release that is deleted later only clears the reference.
	if err := s.UpsertReleases(ctx, []model.Release{sampleRelease(hash(1), 1)}); err != nil {
		t.Fatal(err)
	}
	r := hash(1)
	w = write("B000000001", 2)
	w.ReleaseHash = &r
	if _, err := s.PutEntry(ctx, u, w); err != nil {
		t.Fatal(err)
	}
	if _, err := s.pool.Exec(ctx, `DELETE FROM releases WHERE info_hash = $1`, r); err != nil {
		t.Fatal(err)
	}
	if e, err := s.GetEntry(ctx, u, "B000000001"); err != nil || e.ReleaseHash != nil {
		t.Fatalf("%+v %v", e, err)
	}
}

func TestLibraryAndBookmarkLimits(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	a, b := mkUser(t, s, "A"), mkUser(t, s, "B")
	// Bulk-fill A's library to the limit with plain SQL (2000 PutEntry round trips would be slow).
	if _, err := s.pool.Exec(ctx, `INSERT INTO books (asin, title) SELECT 'X' || lpad(g::text, 9, '0'), 't' FROM generate_series(1, $1) g`, library.MaxEntries+1); err != nil {
		t.Fatal(err)
	}
	if _, err := s.pool.Exec(ctx, `INSERT INTO user_library (user_id, asin, status, updated_at)
		SELECT $1::uuid, 'X' || lpad(g::text, 9, '0'), 'saved', g FROM generate_series(1, $2) g`, a, library.MaxEntries); err != nil {
		t.Fatal(err)
	}
	extra := "X" + fmt.Sprintf("%09d", library.MaxEntries+1)
	if _, err := s.PutEntry(ctx, a, write(extra, 1)); !errors.Is(err, library.ErrLimitReached) {
		t.Fatalf("the 2001st entry: %v", err)
	}
	if _, err := s.PutEntry(ctx, a, write("X000000001", 10_000)); err != nil {
		t.Fatalf("updating an existing entry at the limit must work: %v", err)
	}
	if _, err := s.PutEntry(ctx, b, write(extra, 1)); err != nil {
		t.Fatalf("B is not limited by A's entries: %v", err)
	}
	// Concurrent first writes with room for one more: exactly one fits, the limit is never exceeded.
	if err := s.DeleteEntry(ctx, a, "X000000001"); err != nil {
		t.Fatal(err)
	}
	var wg sync.WaitGroup
	var mu sync.Mutex
	ok := 0
	for _, asin := range []string{"X000000001", extra} {
		wg.Add(1)
		go func() {
			defer wg.Done()
			if _, err := s.PutEntry(ctx, a, write(asin, 50)); err == nil {
				mu.Lock()
				ok++
				mu.Unlock()
			} else if !errors.Is(err, library.ErrLimitReached) {
				t.Error(err)
			}
		}()
	}
	wg.Wait()
	if n := count(t, s, `SELECT count(*) FROM user_library WHERE user_id = $1::uuid`, a); n != library.MaxEntries || ok != 1 {
		t.Fatalf("entries = %d, ok = %d", n, ok)
	}

	// Bookmarks: 500 per book, per user.
	asin := "X000000003"
	if _, err := s.pool.Exec(ctx, `INSERT INTO bookmarks (user_id, asin, position_ms) SELECT $1::uuid, $2, g FROM generate_series(1, $3) g`, a, asin, library.MaxBookmarks); err != nil {
		t.Fatal(err)
	}
	if _, err := s.CreateBookmark(ctx, a, asin, 1, ""); !errors.Is(err, library.ErrLimitReached) {
		t.Fatalf("the 501st bookmark: %v", err)
	}
	if _, err := s.CreateBookmark(ctx, a, "X000000004", 1, ""); err != nil {
		t.Fatalf("another book has its own limit: %v", err)
	}
	if _, err := s.PutEntry(ctx, b, write(asin, 1)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.CreateBookmark(ctx, b, asin, 1, ""); err != nil {
		t.Fatalf("B has its own bookmarks: %v", err)
	}
}

func TestBookmarks(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	a, b := mkUser(t, s, "A"), mkUser(t, s, "B")
	mkBook(t, s, "B000000001")
	if _, err := s.CreateBookmark(ctx, a, "B000000001", 1, ""); !errors.Is(err, library.ErrNotFound) {
		t.Fatalf("a bookmark needs the book in the library: %v", err)
	}
	if _, err := s.PutEntry(ctx, a, write("B000000001", 1)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.PutEntry(ctx, b, write("B000000001", 1)); err != nil {
		t.Fatal(err)
	}
	b2, _ := s.CreateBookmark(ctx, a, "B000000001", 9000, "late")
	b1, _ := s.CreateBookmark(ctx, a, "B000000001", 1000, "early")
	bb, _ := s.CreateBookmark(ctx, b, "B000000001", 5, "B's")
	bs, err := s.ListBookmarks(ctx, a, "B000000001")
	if err != nil || len(bs) != 2 || bs[0].ID != b1.ID || bs[1].ID != b2.ID || bs[0].Note != "early" {
		t.Fatalf("ordered by position, scoped to A: %+v %v", bs, err)
	}
	// B cannot delete A's bookmark by id, nor through another book's path.
	_ = s.DeleteBookmark(ctx, b, "B000000001", b1.ID)
	_ = s.DeleteBookmark(ctx, a, "B000000002", b1.ID)
	if bs, _ = s.ListBookmarks(ctx, a, "B000000001"); len(bs) != 2 {
		t.Fatal("a bookmark was deleted by the wrong user/book")
	}
	if err := s.DeleteBookmark(ctx, a, "B000000001", "not-a-uuid"); err != nil {
		t.Fatalf("idempotent with any id: %v", err)
	}
	_ = s.DeleteBookmark(ctx, a, "B000000001", b1.ID)
	if bs, _ = s.ListBookmarks(ctx, a, "B000000001"); len(bs) != 1 || bs[0].ID != b2.ID {
		t.Fatalf("%+v", bs)
	}
	// Deleting the library entry removes its bookmarks (A's only).
	_ = s.DeleteEntry(ctx, a, "B000000001")
	if bs, _ = s.ListBookmarks(ctx, a, "B000000001"); len(bs) != 0 {
		t.Fatal("bookmarks outlived the entry")
	}
	if bs, _ = s.ListBookmarks(ctx, b, "B000000001"); len(bs) != 1 || bs[0].ID != bb.ID {
		t.Fatalf("B's bookmark: %+v", bs)
	}
}

func TestUserAvailabilityIsPerFingerprint(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	h := hash(1)
	now := time.Now()
	if err := s.SetUserAvailability(ctx, "fpA", "realdebrid", h, true, now); err != nil {
		t.Fatal(err)
	}
	got, err := s.GetUserAvailability(ctx, "fpA", "realdebrid", []string{h}, time.Time{})
	if err != nil || !got[h] {
		t.Fatalf("%v %v", got, err)
	}
	for _, c := range [][2]string{{"fpB", "realdebrid"}, {"fpA", "torbox"}} {
		if got, _ := s.GetUserAvailability(ctx, c[0], c[1], []string{h}, time.Time{}); len(got) != 0 {
			t.Errorf("%v sees %v", c, got)
		}
	}
	if got, _ := s.GetUserAvailability(ctx, "", "realdebrid", []string{h}, time.Time{}); len(got) != 0 {
		t.Error("empty fingerprint reads rows")
	}
	if err := s.SetUserAvailability(ctx, "", "realdebrid", h, true, now); err == nil {
		t.Error("empty fingerprint accepted")
	}
	// B's write for the same hash does not change A's state.
	_ = s.SetUserAvailability(ctx, "fpB", "realdebrid", h, false, now)
	if got, _ := s.GetUserAvailability(ctx, "fpA", "realdebrid", []string{h}, time.Time{}); !got[h] {
		t.Fatal("A's state was overwritten by B")
	}
	if got, _ := s.GetUserAvailability(ctx, "fpB", "realdebrid", []string{h}, time.Time{}); got[h] {
		t.Fatal("B's state wrong")
	}
	// Freshness window.
	if got, _ := s.GetUserAvailability(ctx, "fpA", "realdebrid", []string{h}, now.Add(time.Minute)); len(got) != 0 {
		t.Fatal("stale state returned")
	}
	// The shared table stays free of per-key providers.
	if n := count(t, s, `SELECT count(*) FROM availability`); n != 0 {
		t.Fatalf("availability rows = %d", n)
	}
}

func TestClientFilesStoredOnce(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	h := hash(1)
	first := []model.File{{Index: 1, Path: "b.mp3", Size: 2}, {Index: 0, Path: "a.mp3", Size: 1}}
	if _, _, err := s.SaveClientFiles(ctx, h, first); !errors.Is(err, library.ErrUnknownRelease) {
		t.Fatalf("unknown release: %v", err)
	}
	if err := s.UpsertReleases(ctx, []model.Release{sampleRelease(h, 1)}); err != nil {
		t.Fatal(err)
	}
	stored, created, err := s.SaveClientFiles(ctx, h, first)
	if err != nil || !created || !reflect.DeepEqual(stored, first) {
		t.Fatalf("%v %v %v", stored, created, err)
	}
	stored, created, err = s.SaveClientFiles(ctx, h, []model.File{{Index: 0, Path: "evil.mp3", Size: 9}})
	if err != nil || created || !reflect.DeepEqual(stored, first) {
		t.Fatalf("a second report must not overwrite: %v %v %v", stored, created, err)
	}
	got, err := s.ClientFiles(ctx, []string{h, hash(2)})
	if err != nil || len(got) != 1 || !reflect.DeepEqual(got[h], first) {
		t.Fatalf("%v %v", got, err)
	}
	// Kept apart from the debrid file list.
	if f, _ := s.Files(ctx, []string{h}); len(f) != 0 {
		t.Fatal("client files leaked into release_files")
	}
	if err := s.SaveFiles(ctx, h, []model.File{{Index: 7, Path: "debrid.mp3", Size: 1}}); err != nil {
		t.Fatal(err)
	}
	if got, _ := s.ClientFiles(ctx, []string{h}); got[h][0].Path != "b.mp3" {
		t.Fatal("debrid files replaced client files")
	}
	// Concurrent first reports: one winner, everybody gets the same stored list.
	h2 := hash(3)
	_ = s.UpsertReleases(ctx, []model.Release{sampleRelease(h2, 1)})
	var wg sync.WaitGroup
	results := make([][]model.File, 6)
	for i := range results {
		wg.Add(1)
		go func() {
			defer wg.Done()
			results[i], _, _ = s.SaveClientFiles(ctx, h2, []model.File{{Index: 0, Path: fmt.Sprintf("p%d.mp3", i), Size: 1}})
		}()
	}
	wg.Wait()
	for _, r := range results[1:] {
		if !reflect.DeepEqual(r, results[0]) {
			t.Fatalf("callers saw different lists: %v vs %v", r, results[0])
		}
	}
}

func TestReleaseByHash(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	if r, err := s.GetRelease(ctx, hash(1)); err != nil || r != nil {
		t.Fatalf("%v %v", r, err)
	}
	if _, err := s.GetRelease(ctx, "nothex"); err == nil {
		t.Fatal("invalid hash accepted")
	}
	r := model.Release{InfoHash: hash(1), Title: "From app", Source: "app", Magnet: "magnet:?xt=urn:btih:" + hash(1)}
	if created, err := s.InsertReleaseIfAbsent(ctx, r); err != nil || !created {
		t.Fatalf("%v %v", created, err)
	}
	other := r
	other.Title = "Other"
	if created, err := s.InsertReleaseIfAbsent(ctx, other); err != nil || created {
		t.Fatalf("an existing release is kept: %v %v", created, err)
	}
	got, err := s.GetRelease(ctx, hash(1))
	if err != nil || got.Title != "From app" || got.Magnet != r.Magnet {
		t.Fatalf("%+v %v", got, err)
	}
	// A search later takes over the row with the source's data.
	if err := s.UpsertReleases(ctx, []model.Release{sampleRelease(hash(1), 7)}); err != nil {
		t.Fatal(err)
	}
	if got, _ = s.GetRelease(ctx, hash(1)); got.Source != "knaben" || got.Seeders != 7 || got.Parsed.Format != "m4b" {
		t.Fatalf("%+v", got)
	}
}

func TestPruneUserAvailability(t *testing.T) {
	s := openStore(t)
	ctx := context.Background()
	now := time.Date(2026, 11, 25, 12, 0, 0, 0, time.UTC)
	h1, h2, h3 := hash(0x71), hash(0x72), hash(0x73)
	for h, age := range map[string]time.Duration{h1: 31 * 24 * time.Hour, h2: 29 * 24 * time.Hour, h3: 90 * 24 * time.Hour} {
		if err := s.SetUserAvailability(ctx, "fp", "realdebrid", h, true, now.Add(-age)); err != nil {
			t.Fatal(err)
		}
	}
	n, err := s.PruneUserAvailability(ctx, now.Add(-30*24*time.Hour))
	if err != nil || n != 2 {
		t.Fatalf("pruned %d, %v; want 2", n, err)
	}
	got, err := s.GetUserAvailability(ctx, "fp", "realdebrid", []string{h1, h2, h3}, time.Time{})
	if err != nil || len(got) != 1 || !got[h2] {
		t.Fatalf("remaining = %v, %v; want only %s", got, err, h2)
	}
	if n, _ := s.PruneUserAvailability(ctx, now.Add(-30*24*time.Hour)); n != 0 {
		t.Fatalf("second prune removed %d", n)
	}
}
