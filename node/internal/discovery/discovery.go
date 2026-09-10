package discovery

import (
	"context"
	"log"
	"sync"
	"time"

	"github.com/grandcat/zeroconf"
	"source.local/node/internal/config"
	"source.local/node/internal/database"
)

const Type = "_source._tcp"

type Service struct {
	db       *database.DB
	cfg      config.Config
	logger   *log.Logger
	mu       sync.Mutex
	server   registration
	register func(string, string, string, int, []string) (registration, error)
}

type registration interface{ Shutdown() }

func New(db *database.DB, cfg config.Config, logger *log.Logger) *Service {
	return &Service{db: db, cfg: cfg, logger: logger, register: func(name, service, domain string, port int, text []string) (registration, error) {
		return zeroconf.Register(name, service, domain, port, text, nil)
	}}
}
func (s *Service) Run(ctx context.Context) error {
	ticker := time.NewTicker(2 * time.Second)
	defer ticker.Stop()
	for {
		if e := s.publishIfReady(); e != nil {
			return e
		}
		select {
		case <-ctx.Done():
			s.Stop()
			return nil
		case <-ticker.C:
		}
	}
}
func (s *Service) publishIfReady() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.server != nil || !s.db.IsInitialized() {
		return nil
	}
	node, e := s.db.GetNodeState(false)
	if e != nil {
		return e
	}
	name := "Source " + tail(node.NodeID, 8)
	server, e := s.register(name, Type, "local.", s.cfg.HTTPSPort, []string{"v=1", "id=" + node.NodeID, "name=" + node.DisplayName, "api=/api/v1"})
	if e != nil {
		return e
	}
	s.server = server
	s.logger.Printf("source node discovery active type=_source._tcp port=%d", s.cfg.HTTPSPort)
	return nil
}
func (s *Service) Stop() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.server != nil {
		s.server.Shutdown()
		s.server = nil
	}
}
func tail(v string, n int) string {
	if len(v) <= n {
		return v
	}
	return v[len(v)-n:]
}
