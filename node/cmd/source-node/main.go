package main

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"source.local/node/internal/admin"
	localai "source.local/node/internal/ai"
	"source.local/node/internal/config"
	"source.local/node/internal/database"
	"source.local/node/internal/discovery"
	"source.local/node/internal/httpapi"
	"source.local/node/internal/pairing"
)

func main() {
	if e := run(); e != nil {
		log.Printf("source node failed: %v", e)
		os.Exit(1)
	}
}
func run() error {
	cfg, e := config.Load()
	if e != nil {
		return e
	}
	if len(os.Args) > 1 && os.Args[1] == "healthcheck" {
		return healthcheck(cfg.Port)
	}
	db, e := database.Open(cfg.DatabasePath)
	if e != nil {
		return e
	}
	defer db.Close()
	logger := log.New(os.Stdout, "", log.LstdFlags)
	ai := localai.New(cfg)
	pairs := pairing.New(db, cfg)
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "discovery":
			return runDiscovery(db, cfg, logger)
		case "status":
			return printStatus(db)
		case "list":
			return printUsers(db)
		default:
			return fmt.Errorf("usage: source-node [discovery|healthcheck|status|list]")
		}
	}
	if e = os.MkdirAll(cfg.StorageRoot, 0700); e != nil {
		return e
	}
	api := &http.Server{Addr: fmt.Sprintf("%s:%d", cfg.Host, cfg.Port), Handler: httpapi.New(db, cfg, ai, pairs, logger), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: max(cfg.LlamaTimeout+5*time.Second, 30*time.Second), IdleTimeout: 5 * time.Second, MaxHeaderBytes: 16 * 1024}
	adm := &http.Server{Addr: fmt.Sprintf("%s:%d", cfg.AdminHost, cfg.AdminPort), Handler: admin.New(db, cfg, ai, pairs, logger), ReadHeaderTimeout: 10 * time.Second, ReadTimeout: 30 * time.Second, IdleTimeout: 5 * time.Second, MaxHeaderBytes: 16 * 1024}
	fail := make(chan error, 2)
	go func() { logger.Printf("source node listening on %s", api.Addr); fail <- api.ListenAndServe() }()
	go func() { logger.Printf("source admin listening on %s", adm.Addr); fail <- adm.ListenAndServe() }()
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	var disc *discovery.Service
	if cfg.DiscoveryEnabled {
		disc = discovery.New(db, cfg, logger)
		go func() { fail <- disc.Run(ctx) }()
	}
	select {
	case <-ctx.Done():
	case e = <-fail:
		if !errors.Is(e, http.ErrServerClosed) {
			return e
		}
	}
	if disc != nil {
		disc.Stop()
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	_ = api.Shutdown(shutdown)
	_ = adm.Shutdown(shutdown)
	return nil
}
func runDiscovery(db *database.DB, cfg config.Config, logger *log.Logger) error {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	return discovery.New(db, cfg, logger).Run(ctx)
}
func printStatus(db *database.DB) error {
	n, e := db.GetNodeState(false)
	if errors.Is(e, sql.ErrNoRows) {
		fmt.Println("uninitialized")
		return nil
	}
	if e != nil {
		return e
	}
	fmt.Printf("%s\t%s\n", n.DisplayName, n.NodeID)
	return nil
}
func printUsers(db *database.DB) error {
	users, e := db.ListUsers()
	if e != nil {
		return e
	}
	for _, u := range users {
		fmt.Printf("%s\t%d client(s)\t%d bytes\t%s\n", u.DisplayName, u.ClientCount, u.QuotaBytes, u.ID)
	}
	return nil
}

func healthcheck(port int) error {
	client := &http.Client{Timeout: 3 * time.Second}
	response, err := client.Get(fmt.Sprintf("http://127.0.0.1:%d/healthz", port))
	if err != nil {
		return err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return fmt.Errorf("health endpoint returned HTTP %d", response.StatusCode)
	}
	return nil
}
