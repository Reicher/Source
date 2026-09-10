package config

import (
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestLoadReadsNormalizedSourceEnvironment(t *testing.T) {
	clearConfigEnvironment(t)
	stateRoot := filepath.Join(t.TempDir(), "state")
	storageRoot := filepath.Join(t.TempDir(), "vaults")
	values := map[string]string{
		"SOURCE_NODE_STATE_ROOT":                stateRoot,
		"SOURCE_GATEWAY_HOST":                   "192.0.2.10",
		"SOURCE_NODE_PORT":                      "18080",
		"SOURCE_HTTPS_PORT":                     "18443",
		"SOURCE_ADMIN_PORT":                     "19090",
		"SOURCE_ADMIN_SESSION_TTL_SECONDS":      "123",
		"SOURCE_PAIRING_INVITATION_TTL_SECONDS": "456",
		"SOURCE_SNAPSHOT_MAX_MIB":               "7",
		"SOURCE_SNAPSHOT_RETENTION_COUNT":       "8",
		"SOURCE_ALLOWED_STORAGE_APPS":           "thoughts,notes",
		"SOURCE_AI_BACKEND_URL":                 "http://model:8080",
		"SOURCE_AI_MODEL":                       "source-test-model",
		"SOURCE_AI_MAX_OUTPUT_TOKENS":           "99",
		"SOURCE_AI_TIMEOUT_SECONDS":             "11",
		"SOURCE_NODE_STORAGE_ROOT":              storageRoot,
	}
	for name, value := range values {
		t.Setenv(name, value)
	}

	cfg, err := Load()
	if err != nil {
		t.Fatal(err)
	}
	if cfg.AdminSessionTTL != 123*time.Second || cfg.PairingInvitationTTL != 456*time.Second {
		t.Fatalf("unexpected TTLs: admin=%s pairing=%s", cfg.AdminSessionTTL, cfg.PairingInvitationTTL)
	}
	if cfg.MaximumSnapshotBytes != 7*1024*1024 || cfg.SnapshotRetention != 8 {
		t.Fatalf("unexpected snapshot config: bytes=%d retention=%d", cfg.MaximumSnapshotBytes, cfg.SnapshotRetention)
	}
	if _, ok := cfg.AllowedStorageApps["notes"]; !ok || len(cfg.AllowedStorageApps) != 2 {
		t.Fatalf("unexpected allowed storage apps: %#v", cfg.AllowedStorageApps)
	}
	if cfg.AIBackendURL != "http://model:8080" || cfg.AIModel != "source-test-model" || cfg.AIMaximumOutputTokens != 99 || cfg.AITimeout != 11*time.Second {
		t.Fatalf("unexpected AI config: %#v", cfg)
	}
	if cfg.DatabasePath != filepath.Join(stateRoot, "source-node.sqlite") || cfg.StorageRoot != storageRoot {
		t.Fatalf("unexpected storage paths: database=%q storage=%q", cfg.DatabasePath, cfg.StorageRoot)
	}
}

func TestLoadReportsNormalizedEnvironmentName(t *testing.T) {
	clearConfigEnvironment(t)
	t.Setenv("SOURCE_AI_TIMEOUT_SECONDS", "invalid")
	_, err := Load()
	if err == nil || !strings.Contains(err.Error(), "SOURCE_AI_TIMEOUT_SECONDS") {
		t.Fatalf("unexpected error: %v", err)
	}
}

func clearConfigEnvironment(t *testing.T) {
	t.Helper()
	for _, name := range []string{
		"SOURCE_NODE_STATE_ROOT",
		"SOURCE_GATEWAY_HOST",
		"SOURCE_NODE_PORT",
		"SOURCE_HTTPS_PORT",
		"SOURCE_ADMIN_PORT",
		"SOURCE_ADMIN_SESSION_TTL_SECONDS",
		"SOURCE_PAIRING_INVITATION_TTL_SECONDS",
		"SOURCE_SNAPSHOT_MAX_MIB",
		"SOURCE_SNAPSHOT_RETENTION_COUNT",
		"SOURCE_AI_MAX_OUTPUT_TOKENS",
		"SOURCE_AI_TIMEOUT_SECONDS",
		"SOURCE_DISCOVERY_ENABLED",
		"SOURCE_ALLOWED_STORAGE_APPS",
		"SOURCE_ADMIN_HOST",
		"SOURCE_ADMIN_CONTAINER_MODE",
		"SOURCE_PAIRING_BASE_URL",
		"SOURCE_NODE_HOST",
		"SOURCE_PAIRING_CA_CERTIFICATE_PATH",
		"SOURCE_NODE_DATABASE_PATH",
		"SOURCE_NODE_STORAGE_ROOT",
		"SOURCE_AI_BACKEND_URL",
		"SOURCE_AI_MODEL",
	} {
		t.Setenv(name, "")
	}
}
