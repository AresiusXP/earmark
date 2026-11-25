// Command addon is the Stremio audiobooks addon server.
package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/AresiusXP/stremio-audiobooks/server/internal/accounts"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/appapi"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/appupdate"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid/realdebrid"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/debrid/torbox"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/httpapi"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/metadata"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/service"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/sources"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/store"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userconfig"
	"github.com/AresiusXP/stremio-audiobooks/server/internal/userkeys"
)

// version is set at build time with -ldflags "-X main.version=...".
var version = "dev"

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "addon:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg, err := loadConfig(os.Getenv)
	if err != nil {
		return fmt.Errorf("configuration: %w", err)
	}
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: cfg.LogLevel}))
	slog.SetDefault(logger)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	openCtx, cancel := context.WithTimeout(ctx, 2*time.Minute)
	db, err := store.Open(openCtx, cfg.DatabaseURL)
	cancel()
	if err != nil {
		return fmt.Errorf("store: %w", err)
	}
	defer db.Close()

	sealer, err := userconfig.NewSealer(cfg.ConfigSecret)
	if err != nil {
		return err
	}

	srcs := []sources.Source{sources.NewKnaben()}
	if len(cfg.ABBBaseURLs) > 0 {
		srcs = append(srcs, sources.NewABB(cfg.ABBBaseURLs))
	}
	if cfg.TorrentsCSV {
		srcs = append(srcs, sources.NewTorrentsCSV())
	}
	if cfg.SolidTorrents {
		srcs = append(srcs, sources.NewSolidTorrents())
	}
	providers := debrid.NewRegistry(torbox.New("", nil), realdebrid.New("", nil))
	svc := service.New(service.Deps{
		Metadata:  metadata.New(),
		Sources:   srcs,
		Store:     db,
		UserStore: db,
		Providers: providers,
		Logger:    logger,
	})
	acc, err := accounts.New(accounts.Config{Secret: cfg.ConfigSecret, InstancePassword: cfg.InstancePassword, Store: db})
	if err != nil {
		return err
	}
	keys, err := userkeys.New(userkeys.Config{Secret: cfg.ConfigSecret, Store: db, Providers: providers, Logger: logger})
	if err != nil {
		return err
	}
	v1 := appapi.New(appapi.Deps{Accounts: acc, Keys: keys, Version: version, Logger: logger})
	update := appupdate.Load(cfg.UpdateDir, logger)
	defer update.Close()
	v1.MountApp(appapi.AppDeps{
		Catalog: svc, Store: db, Library: db, Providers: providers, Update: update, PublicURL: cfg.PublicURL,
		AddsPerHour: cfg.AddsPerHour, LinkHosts: cfg.LinkHosts, Logger: logger,
	})
	api := httpapi.New(httpapi.Deps{
		API: svc, Sealer: sealer, PublicURL: cfg.PublicURL, InstancePassword: cfg.InstancePassword,
		Logger: logger, Version: version, V1: v1,
	})

	servers := []*http.Server{{
		Addr:              ":" + strconv.Itoa(cfg.Port),
		Handler:           publicHandler(api),
		ReadHeaderTimeout: 10 * time.Second,
		ReadTimeout:       30 * time.Second, // slow-body clients (the largest body is 8 KB)
		IdleTimeout:       2 * time.Minute,
	}}
	if cfg.AdminPort != 0 {
		servers = append(servers, &http.Server{
			Addr:              ":" + strconv.Itoa(cfg.AdminPort),
			Handler:           adminHandler(api),
			ReadHeaderTimeout: 10 * time.Second,
		})
	}

	errc := make(chan error, len(servers))
	for _, s := range servers {
		ln, err := net.Listen("tcp", s.Addr)
		if err != nil {
			return err
		}
		logger.Info("listening", "addr", s.Addr, "version", version)
		go func() { errc <- s.Serve(ln) }()
	}

	select {
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
		logger.Info("shutting down")
	}
	shutCtx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()
	for _, s := range servers {
		_ = s.Shutdown(shutCtx)
	}
	return nil
}

// publicHandler is what the Ingress reaches: everything but /metrics.
func publicHandler(api http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/metrics" {
			http.NotFound(w, r)
			return
		}
		api.ServeHTTP(w, r)
	})
}

// adminHandler serves /metrics and /healthz on the internal port only.
func adminHandler(api http.Handler) http.Handler {
	mux := http.NewServeMux()
	mux.Handle("GET /metrics", api)
	mux.Handle("GET /healthz", api)
	return mux
}
