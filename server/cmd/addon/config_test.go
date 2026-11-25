package main

import (
	"log/slog"
	"slices"
	"strings"
	"testing"
)

func env(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestLoadConfig(t *testing.T) {
	good := map[string]string{
		"PUBLIC_URL": "https://audiobooks.example/", "DATABASE_URL": "postgres://x",
		"CONFIG_SECRET": strings.Repeat("s", 32), "INSTANCE_PASSWORD": "pw",
	}
	c, err := loadConfig(env(good))
	if err != nil {
		t.Fatal(err)
	}
	if c.Port != 8080 || c.AdminPort != 9090 || c.PublicURL != "https://audiobooks.example" || c.LogLevel != slog.LevelInfo {
		t.Fatalf("%+v", c)
	}

	with := func(k, v string) map[string]string {
		m := map[string]string{}
		for kk, vv := range good {
			m[kk] = vv
		}
		m[k] = v
		return m
	}
	for name, m := range map[string]map[string]string{
		"missing db":    with("DATABASE_URL", ""),
		"short secret":  with("CONFIG_SECRET", "short"),
		"bad url":       with("PUBLIC_URL", "audiobooks.example"),
		"url with path": with("PUBLIC_URL", "https://x.example/abk"),
		"bad port":      with("PORT", "99999"),
		"same ports":    with("ADMIN_PORT", "8080"),
		"bad level":     with("LOG_LEVEL", "loud"),
	} {
		if _, err := loadConfig(env(m)); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
	open := with("INSTANCE_PASSWORD", "")
	open["ALLOW_OPEN_CONFIG"] = "true"
	if c, err := loadConfig(env(open)); err != nil || !c.AllowOpenConfig {
		t.Errorf("explicit open instance: %+v %v", c, err)
	}
	if c, err := loadConfig(env(with("ADMIN_PORT", "0"))); err != nil || c.AdminPort != 0 {
		t.Errorf("admin off: %+v %v", c, err)
	}
}

func TestLoadConfigABBBaseURLs(t *testing.T) {
	base := map[string]string{
		"PUBLIC_URL": "https://audiobooks.example", "DATABASE_URL": "postgres://x",
		"CONFIG_SECRET": strings.Repeat("s", 32), "INSTANCE_PASSWORD": "pw",
	}
	for _, tc := range []struct {
		name, val string
		want      []string
		wantErr   bool
	}{
		{"unset: defaults", "", []string{"https://audiobookbay.lu", "https://audiobookbay.is", "https://audiobookbay.fi"}, false},
		{"off", "off", nil, false},
		{"OFF with spaces", " OFF ", nil, false},
		{"list trimmed", " https://a.example/ , http://b.example:8080,,", []string{"https://a.example", "http://b.example:8080"}, false},
		{"single", "https://a.example", []string{"https://a.example"}, false},
		{"no scheme", "a.example", nil, true},
		{"ftp", "ftp://a.example", nil, true},
		{"path", "https://a.example/abb", nil, true},
		{"one bad in list", "https://a.example,nope", nil, true},
		{"only commas", ",,", nil, true},
	} {
		m := map[string]string{"ABB_BASE_URLS": tc.val}
		for k, v := range base {
			m[k] = v
		}
		c, err := loadConfig(env(m))
		if (err != nil) != tc.wantErr {
			t.Errorf("%s: err = %v", tc.name, err)
			continue
		}
		if !tc.wantErr && !slices.Equal(c.ABBBaseURLs, tc.want) {
			t.Errorf("%s: %v, want %v", tc.name, c.ABBBaseURLs, tc.want)
		}
	}
}

func TestABBBaseURLsRejectCredentials(t *testing.T) {
	if _, err := parseABBBaseURLs("https://user:pw@audiobookbay.example"); err == nil {
		t.Fatal("credentials in a mirror URL must be rejected")
	}
}

func TestLoadConfigSourceSwitches(t *testing.T) {
	base := map[string]string{
		"PUBLIC_URL": "https://audiobooks.example", "DATABASE_URL": "postgres://x",
		"CONFIG_SECRET": strings.Repeat("s", 32), "INSTANCE_PASSWORD": "pw",
	}
	for _, tc := range []struct {
		val     string
		want    bool
		wantErr bool
	}{
		{"", true, false}, {"on", true, false}, {"true", true, false}, {"1", true, false},
		{"off", false, false}, {" OFF ", false, false}, {"false", false, false}, {"0", false, false},
		{"nope", true, true},
	} {
		for _, name := range []string{"TORRENTS_CSV", "SOLIDTORRENTS"} {
			m := map[string]string{name: tc.val}
			for k, v := range base {
				m[k] = v
			}
			c, err := loadConfig(env(m))
			if (err != nil) != tc.wantErr {
				t.Errorf("%s=%q: err = %v", name, tc.val, err)
				continue
			}
			got, other := c.TorrentsCSV, c.SolidTorrents
			if name == "SOLIDTORRENTS" {
				got, other = other, got
			}
			if !tc.wantErr && (got != tc.want || !other) {
				t.Errorf("%s=%q: got %v (other %v), want %v (other on)", name, tc.val, got, other, tc.want)
			}
		}
	}
}

func TestLoadConfigAppSettings(t *testing.T) {
	base := map[string]string{
		"PUBLIC_URL": "https://audiobooks.example", "DATABASE_URL": "postgres://x",
		"CONFIG_SECRET": strings.Repeat("s", 32), "INSTANCE_PASSWORD": "pw",
	}
	with := func(kv ...string) func(string) string {
		m := map[string]string{}
		for k, v := range base {
			m[k] = v
		}
		for i := 0; i < len(kv); i += 2 {
			m[kv[i]] = kv[i+1]
		}
		return env(m)
	}
	c, err := loadConfig(with())
	if err != nil || c.AddsPerHour != 30 || c.UpdateDir != "" || len(c.LinkHosts) != 0 {
		t.Fatalf("defaults: %+v %v", c, err)
	}
	c, err = loadConfig(with("PREPARE_ADDS_PER_HOUR", "5", "APP_UPDATE_DIR", " /srv/update ", "DEBRID_LINK_HOSTS", "realdebrid:cdn.example, torbox:tb.example"))
	if err != nil || c.AddsPerHour != 5 || c.UpdateDir != "/srv/update" || !slices.Equal(c.LinkHosts["realdebrid"], []string{"cdn.example"}) || !slices.Equal(c.LinkHosts["torbox"], []string{"tb.example"}) {
		t.Fatalf("%+v %v", c, err)
	}
	for _, kv := range [][]string{
		{"PREPARE_ADDS_PER_HOUR", "0"}, {"PREPARE_ADDS_PER_HOUR", "x"}, {"PREPARE_ADDS_PER_HOUR", "100000"},
		{"DEBRID_LINK_HOSTS", "premiumize:x.example"}, {"DEBRID_LINK_HOSTS", "realdebrid"},
	} {
		if _, err := loadConfig(with(kv...)); err == nil {
			t.Errorf("%v accepted", kv)
		}
	}
}
