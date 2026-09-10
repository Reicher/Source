package config

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"
)

type Config struct {
	Host                     string
	Port                     int
	HTTPSPort                int
	DiscoveryEnabled         bool
	AdminHost                string
	AdminPort                int
	AdminSessionTTL          time.Duration
	PairingInvitationTTL     time.Duration
	PairingBaseURL           string
	PairingCACertificatePath string
	SuggestedNodeName        string
	DatabasePath             string
	StorageRoot              string
	MaximumSnapshotBytes     int64
	SnapshotRetention        int
	AllowedStorageApps       map[string]struct{}
	LlamaURL                 string
	LlamaModel               string
	LlamaMaximumOutputTokens int
	LlamaTimeout             time.Duration
	Now                      func() time.Time
}

func Load() (Config, error) {
	stateRoot := env("SOURCE_NODE_STATE_ROOT", "/state")
	gatewayHost := env("SOURCE_GATEWAY_HOST", "127.0.0.1")
	port, err := positiveInt("SOURCE_NODE_PORT", 8080)
	if err != nil {
		return Config{}, err
	}
	httpsPort, err := positiveInt("SOURCE_HTTPS_PORT", 8443)
	if err != nil {
		return Config{}, err
	}
	adminPort, err := positiveInt("SOURCE_ADMIN_PORT", 9090)
	if err != nil {
		return Config{}, err
	}
	adminTTL, err := positiveInt("ADMIN_SESSION_TTL_SECONDS", 8*60*60)
	if err != nil {
		return Config{}, err
	}
	pairingTTL, err := positiveInt("PAIRING_INVITATION_TTL_SECONDS", 5*60)
	if err != nil {
		return Config{}, err
	}
	maxMiB, err := positiveInt("MAX_SNAPSHOT_MIB", 32)
	if err != nil {
		return Config{}, err
	}
	retention, err := positiveInt("SNAPSHOT_RETENTION_COUNT", 20)
	if err != nil {
		return Config{}, err
	}
	maxTokens, err := positiveInt("LLAMA_MAX_OUTPUT_TOKENS", 2048)
	if err != nil {
		return Config{}, err
	}
	llamaTimeout, err := positiveInt("LLAMA_TIMEOUT_SECONDS", 300)
	if err != nil {
		return Config{}, err
	}
	discovery, err := boolean("SOURCE_DISCOVERY_ENABLED", false)
	if err != nil {
		return Config{}, err
	}
	apps, err := identifiers("ALLOWED_STORAGE_APPS", "thoughts,source-client")
	if err != nil {
		return Config{}, err
	}
	adminHost := env("SOURCE_ADMIN_HOST", "127.0.0.1")
	containerAdmin := os.Getenv("SOURCE_ADMIN_CONTAINER_MODE") == "1"
	if adminHost != "127.0.0.1" && adminHost != "::1" && adminHost != "localhost" && !containerAdmin {
		return Config{}, fmt.Errorf("SOURCE_ADMIN_HOST must be loopback (container deployments must explicitly set SOURCE_ADMIN_CONTAINER_MODE=1)")
	}
	pairingBaseURL := os.Getenv("SOURCE_PAIRING_BASE_URL")
	if pairingBaseURL == "" {
		pairingBaseURL = fmt.Sprintf("https://%s:%d/api/v1/pairing", gatewayHost, httpsPort)
	}
	hostname, _ := os.Hostname()
	return Config{
		Host: env("SOURCE_NODE_HOST", "127.0.0.1"), Port: port, HTTPSPort: httpsPort,
		DiscoveryEnabled: discovery, AdminHost: adminHost, AdminPort: adminPort,
		AdminSessionTTL:          time.Duration(adminTTL) * time.Second,
		PairingInvitationTTL:     time.Duration(pairingTTL) * time.Second,
		PairingBaseURL:           pairingBaseURL,
		PairingCACertificatePath: env("SOURCE_PAIRING_CA_CERTIFICATE_PATH", "/artifacts/source-node-ca.crt"),
		SuggestedNodeName:        hostname,
		DatabasePath:             env("SOURCE_NODE_DATABASE_PATH", filepath.Join(stateRoot, "source-node.sqlite")),
		StorageRoot:              env("SOURCE_NODE_STORAGE_ROOT", "/vaults"),
		MaximumSnapshotBytes:     int64(maxMiB) * 1024 * 1024, SnapshotRetention: retention,
		AllowedStorageApps: apps, LlamaURL: env("LLAMA_URL", "http://llama:8080"),
		LlamaModel: env("LLAMA_MODEL", "source-qwen3.5-9b"), LlamaMaximumOutputTokens: maxTokens,
		LlamaTimeout: time.Duration(llamaTimeout) * time.Second, Now: time.Now,
	}, nil
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}

func positiveInt(name string, fallback int) (int, error) {
	raw := os.Getenv(name)
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", name)
	}
	return value, nil
}

func boolean(name string, fallback bool) (bool, error) {
	raw := os.Getenv(name)
	if raw == "" {
		return fallback, nil
	}
	switch raw {
	case "1", "true":
		return true, nil
	case "0", "false":
		return false, nil
	}
	return false, fmt.Errorf("%s must be true/false or 1/0", name)
}

func identifiers(name, fallback string) (map[string]struct{}, error) {
	raw := env(name, fallback)
	values := strings.Split(raw, ",")
	result := make(map[string]struct{}, len(values))
	for _, item := range values {
		value := strings.TrimSpace(item)
		if len(value) < 2 || len(value) > 32 || value[0] < 'a' || value[0] > 'z' {
			return nil, fmt.Errorf("%s must be a comma-separated list of application identifiers", name)
		}
		for _, c := range value[1:] {
			if !((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
				return nil, fmt.Errorf("%s must be a comma-separated list of application identifiers", name)
			}
		}
		result[value] = struct{}{}
	}
	if len(result) == 0 {
		return nil, fmt.Errorf("%s must be a comma-separated list of application identifiers", name)
	}
	return result, nil
}
