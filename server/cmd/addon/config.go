package main

import (
	"errors"
	"fmt"
	"log/slog"
	"net/url"
	"strconv"
	"strings"
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
	return c, errors.Join(errs...)
}
