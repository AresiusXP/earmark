package main

import (
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"slices"
	"strconv"
	"strings"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/appapi"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/appupdate"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/sources"
)

// config is read from the environment (see helm/stremio-audiobooks and docs/DESIGN.md).
type config struct {
	Port             int
	AdminPort        int // serves /metrics; 0 disables the admin listener
	LogLevel         slog.Level
	PublicURL        string
	DatabaseURL      string
	ConfigSecret     string
	InstancePassword string
	AllowOpenConfig  bool // explicit opt-in to run without INSTANCE_PASSWORD
	// GoogleClientIDs are the accepted audiences of Google ID tokens (GOOGLE_CLIENT_ID, plus the
	// comma list GOOGLE_CLIENT_IDS); empty disables Sign in with Google. Client IDs are public.
	GoogleClientIDs []string
	// ABBBaseURLs are the AudioBook Bay mirrors in order of preference; empty disables ABB.
	ABBBaseURLs []string
	// TorrentsCSV and SolidTorrents enable those sources (default on; env value "off" disables).
	TorrentsCSV   bool
	SolidTorrents bool
	// UpdateDir holds the APK bundle (update.json + audiobooks.apk) served by /v1/app/update.
	UpdateDir string
	// UpdateMaxBytes is the largest APK the server will bundle (APP_UPDATE_MAX_BYTES, default 200 MiB).
	UpdateMaxBytes int64
	// AddsPerHour is the cold-add budget of a debrid key for /v1 prepare/download.
	AddsPerHour int
	// LinkHosts adds CDN host suffixes per provider to the redirect allowlist of /v1 downloads.
	LinkHosts map[string][]string
}

func loadConfig(getenv func(string) string) (config, error) {
	var errs []error
	c := config{Port: 8080, AdminPort: 9090}

	intVar := func(name string, dst *int) {
		if v := getenv(name); v != "" {
			n, err := strconv.Atoi(v)
			if err != nil || n < 0 || n > 65535 {
				errs = append(errs, fmt.Errorf("%s: invalid port %q", name, v))
				return
			}
			*dst = n
		}
	}
	intVar("PORT", &c.Port)
	intVar("ADMIN_PORT", &c.AdminPort)
	if c.Port == 0 {
		errs = append(errs, errors.New("PORT must not be 0"))
	}
	if c.AdminPort == c.Port {
		errs = append(errs, errors.New("ADMIN_PORT must differ from PORT"))
	}

	if v := getenv("LOG_LEVEL"); v != "" {
		if err := c.LogLevel.UnmarshalText([]byte(v)); err != nil {
			errs = append(errs, fmt.Errorf("LOG_LEVEL: %w", err))
		}
	}

	c.PublicURL = strings.TrimRight(getenv("PUBLIC_URL"), "/")
	if u, err := url.Parse(c.PublicURL); err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Path != "" {
		errs = append(errs, errors.New("PUBLIC_URL must be an absolute http(s) URL without a path"))
	}

	c.DatabaseURL = getenv("DATABASE_URL")
	if c.DatabaseURL == "" {
		errs = append(errs, errors.New("DATABASE_URL is required"))
	}
	c.ConfigSecret = getenv("CONFIG_SECRET")
	if len(c.ConfigSecret) < 32 {
		errs = append(errs, errors.New("CONFIG_SECRET must be at least 32 bytes"))
	}
	c.InstancePassword = getenv("INSTANCE_PASSWORD")
	c.AllowOpenConfig = getenv("ALLOW_OPEN_CONFIG") == "true"
	if c.InstancePassword == "" && !c.AllowOpenConfig {
		// Without a password anyone can create an install link (and make the server call
		// debrid APIs with their own key). Running open must be a conscious choice.
		errs = append(errs, errors.New("INSTANCE_PASSWORD is required (or set ALLOW_OPEN_CONFIG=true to run an open instance)"))
	}

	gids, err := parseGoogleClientIDs(getenv("GOOGLE_CLIENT_ID"), getenv("GOOGLE_CLIENT_IDS"))
	if err != nil {
		errs = append(errs, err)
	}
	c.GoogleClientIDs = gids

	abb, err := parseABBBaseURLs(getenv("ABB_BASE_URLS"))
	if err != nil {
		errs = append(errs, err)
	}
	c.ABBBaseURLs = abb

	for _, sw := range []struct {
		name string
		dst  *bool
	}{{"TORRENTS_CSV", &c.TorrentsCSV}, {"SOLIDTORRENTS", &c.SolidTorrents}} {
		on, err := parseSwitch(sw.name, getenv(sw.name))
		if err != nil {
			errs = append(errs, err)
		}
		*sw.dst = on
	}

	c.UpdateDir = strings.TrimSpace(getenv("APP_UPDATE_DIR")) // empty: appupdate.DefaultDir (/update)
	c.UpdateMaxBytes = appupdate.DefaultMaxBytes
	if v := getenv("APP_UPDATE_MAX_BYTES"); v != "" {
		n, err := strconv.ParseInt(strings.TrimSpace(v), 10, 64)
		if err != nil || n < 1 || n > 4<<30 {
			errs = append(errs, fmt.Errorf("APP_UPDATE_MAX_BYTES: invalid value %q (1 to 4294967296 bytes)", v))
		} else {
			c.UpdateMaxBytes = n
		}
	}
	c.AddsPerHour = appapi.DefaultAddsPerHour
	if v := getenv("PREPARE_ADDS_PER_HOUR"); v != "" {
		n, err := strconv.Atoi(v)
		if err != nil || n < 1 || n > 10000 {
			errs = append(errs, fmt.Errorf("PREPARE_ADDS_PER_HOUR: invalid value %q (1-10000)", v))
		} else {
			c.AddsPerHour = n
		}
	}
	hosts, err := appapi.ParseLinkHosts(getenv("DEBRID_LINK_HOSTS"))
	if err != nil {
		errs = append(errs, err)
	}
	c.LinkHosts = hosts
	return c, errors.Join(errs...)
}

const googleClientIDSuffix = ".apps.googleusercontent.com"

// parseGoogleClientIDs merges GOOGLE_CLIENT_ID and the comma list GOOGLE_CLIENT_IDS (order kept,
// duplicates dropped). Every entry must look like a Google OAuth client ID, so a typo fails the
// start instead of silently rejecting every sign-in.
func parseGoogleClientIDs(single, list string) ([]string, error) {
	var out []string
	for _, raw := range append([]string{single}, strings.Split(list, ",")...) {
		id := strings.TrimSpace(raw)
		if id == "" || slices.Contains(out, id) {
			continue
		}
		if !strings.HasSuffix(id, googleClientIDSuffix) || len(id) == len(googleClientIDSuffix) || strings.ContainsAny(id, " \t\"'") {
			return nil, errors.New("GOOGLE_CLIENT_ID(S): every entry must be a Google OAuth client ID (<id>" + googleClientIDSuffix + ")")
		}
		out = append(out, id)
	}
	return out, nil
}

// parseABBBaseURLs reads ABB_BASE_URLS: unset means the default mirrors, "off" disables ABB,
// otherwise a comma list of absolute http(s) URLs without a path.
func parseABBBaseURLs(v string) ([]string, error) {
	v = strings.TrimSpace(v)
	if v == "" {
		return slices.Clone(sources.DefaultABBBaseURLs), nil
	}
	if strings.EqualFold(v, "off") {
		return nil, nil
	}
	var out []string
	for _, raw := range strings.Split(v, ",") {
		raw = strings.TrimRight(strings.TrimSpace(raw), "/")
		if raw == "" {
			continue
		}
		u, err := url.Parse(raw)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.Path != "" || u.RawQuery != "" || u.User != nil {
			// userinfo is rejected: base URLs show up in logged request errors.
			return nil, errors.New("ABB_BASE_URLS: every entry must be an absolute http(s) URL without a path or credentials")
		}
		out = append(out, raw)
	}
	if len(out) == 0 {
		return nil, errors.New(`ABB_BASE_URLS: no URLs (use "off" to disable ABB)`)
	}
	return out, nil
}

// parseSwitch reads an on/off variable: unset, "on", "true" and "1" enable; "off", "false" and
// "0" disable (case-insensitive).
func parseSwitch(name, v string) (bool, error) {
	switch strings.ToLower(strings.TrimSpace(v)) {
	case "", "on", "true", "1":
		return true, nil
	case "off", "false", "0":
		return false, nil
	}
	return true, fmt.Errorf(`%s: invalid value %q (use "off" to disable)`, name, v)
}
