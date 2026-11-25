package appupdate

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

var apkBytes = []byte("PK\x03\x04 pretend this is an apk")

func sum(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }

func goodInfo() Info {
	return Info{
		VersionName: "1.2.3", VersionCode: 10203, SHA256: sum(apkBytes), Size: int64(len(apkBytes)),
		Notes: "## Changes\n- things", ReleasedAt: "2026-11-18T12:00:00Z",
	}
}

// bundle writes update.json (from info, as given) and audiobooks.apk (apk, nil to skip) into a new dir.
func bundle(t *testing.T, info any, apk []byte) string {
	t.Helper()
	dir := t.TempDir()
	if info != nil {
		var raw []byte
		if s, ok := info.(string); ok {
			raw = []byte(s)
		} else {
			raw, _ = json.Marshal(info)
		}
		if err := os.WriteFile(filepath.Join(dir, MetaFile), raw, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	if apk != nil {
		if err := os.WriteFile(filepath.Join(dir, APKFile), apk, 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return dir
}

func quiet() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestLoadVerifiesTheBundle(t *testing.T) {
	bad := goodInfo()
	bad.SHA256 = strings.Repeat("0", 64)
	short := goodInfo()
	short.Size++
	badCode := goodInfo()
	badCode.VersionCode = 10204
	badName := goodInfo()
	badName.VersionName = "v1.2"
	upper := goodInfo()
	upper.SHA256 = strings.ToUpper(upper.SHA256)
	noSize := goodInfo()
	noSize.Size = 0
	longNotes := goodInfo()
	longNotes.Notes = strings.Repeat("é", maxNotes+10)

	cases := []struct {
		name string
		dir  string
		ok   bool
	}{
		{"valid", bundle(t, goodInfo(), apkBytes), true},
		{"uppercase sha is normalised", bundle(t, upper, apkBytes), true},
		{"long notes are capped", bundle(t, longNotes, apkBytes), true},
		{"missing dir", filepath.Join(t.TempDir(), "nope"), false},
		{"empty dir", t.TempDir(), false},
		{"update.json without apk", bundle(t, goodInfo(), nil), false},
		{"apk without update.json", bundle(t, nil, apkBytes), false},
		{"corrupt sha", bundle(t, bad, apkBytes), false},
		{"apk bytes changed", bundle(t, goodInfo(), append([]byte("X"), apkBytes[1:]...)), false},
		{"size mismatch", bundle(t, short, apkBytes), false},
		{"truncated apk", bundle(t, goodInfo(), apkBytes[:5]), false},
		{"version_code mismatch", bundle(t, badCode, apkBytes), false},
		{"bad version_name", bundle(t, badName, apkBytes), false},
		{"zero size", bundle(t, noSize, apkBytes), false},
		{"invalid json", bundle(t, "{nope", apkBytes), false},
		{"empty json", bundle(t, "", apkBytes), false},
		{"sha not hex", bundle(t, `{"version_name":"1.2.3","version_code":10203,"sha256":"zz","size":3}`, apkBytes), false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			s := Load(c.dir, quiet())
			defer s.Close()
			info, ok := s.Info()
			if ok != c.ok {
				t.Fatalf("Info ok = %v, want %v (%+v)", ok, c.ok, info)
			}
			r, _, aok := s.APK()
			if aok != c.ok || (r == nil) == c.ok {
				t.Fatalf("APK ok = %v, reader %v", aok, r)
			}
			if !c.ok {
				if info != (Info{}) {
					t.Errorf("disabled service leaks info %+v", info)
				}
				return
			}
			if info.SHA256 != sum(apkBytes) || info.VersionCode != 10203 {
				t.Errorf("info = %+v", info)
			}
			if n := len([]rune(info.Notes)); n > maxNotes {
				t.Errorf("notes = %d runes", n)
			}
			got, err := io.ReadAll(r)
			if err != nil || string(got) != string(apkBytes) {
				t.Errorf("apk read = %q, %v", got, err)
			}
		})
	}
}

func TestLoadDefaultsToDefaultDir(t *testing.T) {
	// /update does not exist on the dev machine and in CI; either way Load must not panic.
	Load("", nil).Close()
}

func TestNilServiceIsEmpty(t *testing.T) {
	var s *Service
	if _, ok := s.Info(); ok {
		t.Error("nil service has info")
	}
	if _, _, ok := s.APK(); ok {
		t.Error("nil service has an apk")
	}
	if err := s.Close(); err != nil {
		t.Error(err)
	}
}

func TestVersionCode(t *testing.T) {
	cases := []struct {
		in   string
		code int
		ok   bool
	}{
		{"0.1.0", 100, true},
		{"1.2.3", 10203, true},
		{"12.34.56", 123456, true},
		{"1.2.3-rc.1", 10203, true},
		{"1.2", 0, false},
		{"1.2.3.4", 0, false},
		{"01.2.3", 0, false},
		{"1.100.0", 0, false},
		{"100.0.0", 0, false},
		{"1.2.100", 0, false},
		{"1.99.99", 19999, true},
		{"99.99.99", 999999, true},
		{"v1.2.3", 0, false},
		{"", 0, false},
	}
	for _, c := range cases {
		code, ok := VersionCode(c.in)
		if code != c.code || ok != c.ok {
			t.Errorf("VersionCode(%q) = %d, %v; want %d, %v", c.in, code, ok, c.code, c.ok)
		}
	}
}
