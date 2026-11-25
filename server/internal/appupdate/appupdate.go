// Package appupdate serves the Android APK that the release pipeline bakes into the server image
// (docs/DESIGN.md, "App direction", phase 5). The repository is private, so the app cannot download GitHub
// release assets (and the app must be able to update before anything else works): it asks the server, which carries the APK of its own version.
//
// The bundle is a directory with update.json (metadata) and audiobooks.apk. It is verified once at
// start-up; anything missing or inconsistent just means "no update bundled", never an error.
package appupdate

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"time"
)

const (
	// DefaultDir is where the Dockerfile puts the bundle.
	DefaultDir = "/update"

	MetaFile = "update.json"
	APKFile  = "audiobooks.apk"

	maxMetaBytes = 256 << 10
	maxNotes     = 4000
)

// Info is the metadata of the bundled APK (the content of update.json).
type Info struct {
	VersionName string `json:"version_name"`
	VersionCode int    `json:"version_code"`
	SHA256      string `json:"sha256"`
	Size        int64  `json:"size"`
	Notes       string `json:"notes"`
	ReleasedAt  string `json:"released_at"`
}

// ReleasedTime is released_at as a time, zero when it does not parse.
func (i Info) ReleasedTime() time.Time {
	t, _ := time.Parse(time.RFC3339, i.ReleasedAt)
	return t
}

// Service holds the verified bundle, or nothing. It is safe for concurrent use.
type Service struct {
	info Info
	file *os.File // read-only handle opened at start-up; nil when nothing is bundled
}

var (
	versionRE = regexp.MustCompile(`^(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)(-[0-9A-Za-z.-]+)?$`)
	sha256RE  = regexp.MustCompile(`^[0-9a-f]{64}$`)
)

// VersionCode derives the Android versionCode from a version name, like app/app/build.gradle.kts:
// X*10000 + Y*100 + Z (a pre-release suffix is ignored). Each part must be 0..99 without leading
// zeros (they would collide with the next field); release.yml and the Gradle build enforce the
// same rule.
func VersionCode(name string) (int, bool) {
	m := versionRE.FindStringSubmatch(name)
	if m == nil {
		return 0, false
	}
	code := 0
	for i, mul := range []int{10000, 100, 1} {
		n, _ := strconv.Atoi(m[i+1])
		code += n * mul
	}
	return code, true
}

// Load reads and verifies the bundle in dir (empty means DefaultDir). It never fails: when the
// directory, a file or a checksum is missing or wrong it logs why (a warning, except for the plain
// "no bundle" case) and returns a Service that reports nothing available.
func Load(dir string, logger *slog.Logger) *Service {
	if logger == nil {
		logger = slog.Default()
	}
	if dir == "" {
		dir = DefaultDir
	}
	info, f, err := load(dir)
	switch {
	case err == nil:
		logger.Info("app update bundled", "version", info.VersionName, "version_code", info.VersionCode, "size", info.Size)
		return &Service{info: info, file: f}
	case errors.Is(err, errNoBundle):
		logger.Info("no app update bundled", "dir", dir)
	default:
		logger.Warn("app update disabled: bundle is unusable", "dir", dir, "err", err)
	}
	return &Service{}
}

var errNoBundle = errors.New("no update.json")

func load(dir string) (Info, *os.File, error) {
	mf, err := os.Open(filepath.Join(dir, MetaFile))
	if errors.Is(err, os.ErrNotExist) {
		return Info{}, nil, errNoBundle
	}
	if err != nil {
		return Info{}, nil, err
	}
	raw, err := io.ReadAll(io.LimitReader(mf, maxMetaBytes+1))
	_ = mf.Close()
	if err != nil {
		return Info{}, nil, fmt.Errorf("read %s: %w", MetaFile, err)
	}
	if len(raw) > maxMetaBytes {
		return Info{}, nil, fmt.Errorf("%s is larger than %d bytes", MetaFile, maxMetaBytes)
	}
	var info Info
	if err := json.Unmarshal(raw, &info); err != nil {
		return Info{}, nil, fmt.Errorf("parse %s: %w", MetaFile, err)
	}
	if err := info.validate(); err != nil {
		return Info{}, nil, fmt.Errorf("%s: %w", MetaFile, err)
	}

	f, err := os.Open(filepath.Join(dir, APKFile))
	if err != nil {
		return Info{}, nil, fmt.Errorf("open %s: %w", APKFile, err)
	}
	st, err := f.Stat()
	if err != nil {
		_ = f.Close()
		return Info{}, nil, err
	}
	if !st.Mode().IsRegular() || st.Size() != info.Size {
		_ = f.Close()
		return Info{}, nil, fmt.Errorf("%s is %d bytes, %s says %d", APKFile, st.Size(), MetaFile, info.Size)
	}
	h := sha256.New()
	if _, err := io.Copy(h, io.NewSectionReader(f, 0, info.Size)); err != nil {
		_ = f.Close()
		return Info{}, nil, fmt.Errorf("hash %s: %w", APKFile, err)
	}
	if got := hex.EncodeToString(h.Sum(nil)); got != info.SHA256 {
		_ = f.Close()
		return Info{}, nil, fmt.Errorf("%s sha256 is %s, %s says %s", APKFile, got, MetaFile, info.SHA256)
	}
	return info, f, nil
}

// validate normalises the checksum and caps the notes; it rejects anything the app could not use.
func (i *Info) validate() error {
	code, ok := VersionCode(i.VersionName)
	if !ok {
		return fmt.Errorf("invalid version_name %q", i.VersionName)
	}
	if i.VersionCode != code {
		return fmt.Errorf("version_code %d does not match version_name %q (%d)", i.VersionCode, i.VersionName, code)
	}
	i.SHA256 = strings.ToLower(i.SHA256)
	if !sha256RE.MatchString(i.SHA256) {
		return errors.New("sha256 must be 64 hex characters")
	}
	if i.Size <= 0 {
		return errors.New("size must be positive")
	}
	i.Notes = strings.ToValidUTF8(i.Notes, "")
	if r := []rune(i.Notes); len(r) > maxNotes {
		i.Notes = string(r[:maxNotes])
	}
	return nil
}

// Info is the bundled update; ok is false when nothing usable is bundled.
func (s *Service) Info() (Info, bool) {
	if s == nil || s.file == nil {
		return Info{}, false
	}
	return s.info, true
}

// APK is a reader over the bundled file: no copy in memory, and the shared read-only handle is
// ReadAt-based, so concurrent requests do not interfere. ok is false when nothing is bundled.
func (s *Service) APK() (r *io.SectionReader, info Info, ok bool) {
	if s == nil || s.file == nil {
		return nil, Info{}, false
	}
	return io.NewSectionReader(s.file, 0, s.info.Size), s.info, true
}

// Close releases the file handle; the Service must not be used afterwards.
func (s *Service) Close() error {
	if s == nil || s.file == nil {
		return nil
	}
	return s.file.Close()
}
