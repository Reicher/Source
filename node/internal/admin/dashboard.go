package admin

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const version = "0.5.0"

var processStarted = time.Now()

func (h *Handler) dashboardResponse(w http.ResponseWriter, r *http.Request) {
	if _, err := h.authenticated(r, false); err != nil {
		h.fail(w, err)
		return
	}
	value, err := h.dashboard(r.Context())
	if err != nil {
		h.fail(w, err)
		return
	}
	adminJSON(w, http.StatusOK, value)
}

func (h *Handler) dashboard(ctx context.Context) (map[string]any, error) {
	node, err := h.db.GetNodeState(false)
	if err != nil {
		return nil, err
	}
	users, err := h.db.ListUsers()
	if err != nil {
		return nil, err
	}
	public := make([]map[string]any, 0, len(users))
	clients := 0
	for _, user := range users {
		clients += user.ClientCount
		public = append(public, map[string]any{
			"id": user.ID, "displayName": user.DisplayName, "quotaBytes": user.QuotaBytes,
			"createdAt": user.CreatedAt, "disabledAt": user.DisabledAt,
			"recoveryConfigured": user.RecoveryConfigured,
			"storageUsedBytes":   user.StorageUsedBytes, "clientCount": user.ClientCount,
		})
	}
	return map[string]any{
		"node": map[string]any{
			"displayName": node.DisplayName, "nodeId": node.NodeID,
			"fingerprint": tail(node.NodeID, 12), "version": version,
			"createdAt": time.UnixMilli(node.CreatedAt).UTC().Format(time.RFC3339Nano),
		},
		"status": map[string]any{
			"service": "online", "processUptimeSeconds": int(time.Since(processStarted).Seconds()),
			"systemUptimeSeconds": systemUptime(), "cpu": cpuStatus(),
			"memory": memoryStatus(), "disk": diskStatus(h.cfg.StorageRoot),
			"temperatureCelsius": temperature(),
			"ai":                 map[string]any{"available": h.ai.Status(ctx), "model": h.cfg.AIModel},
			"lan":                map[string]any{"host": h.cfg.Host, "port": h.cfg.Port, "pairingEndpoint": h.cfg.PairingBaseURL},
			"users":              len(users), "pairedClients": clients,
		},
		"users": public,
	}, nil
}

func tail(value string, length int) string {
	if len(value) <= length {
		return value
	}
	return value[len(value)-length:]
}

func diskStatus(root string) map[string]any {
	var status syscall.Statfs_t
	if syscall.Statfs(root, &status) != nil {
		return map[string]any{"totalBytes": nil, "usedBytes": nil, "availableBytes": nil}
	}
	total := uint64(status.Blocks) * uint64(status.Bsize)
	free := uint64(status.Bfree) * uint64(status.Bsize)
	available := uint64(status.Bavail) * uint64(status.Bsize)
	return map[string]any{"totalBytes": total, "usedBytes": total - free, "availableBytes": available}
}

func memoryStatus() map[string]any {
	if values := procMemory(); values != nil {
		return values
	}
	var memory runtime.MemStats
	runtime.ReadMemStats(&memory)
	return map[string]any{"totalBytes": memory.Sys, "usedBytes": memory.Alloc, "availableBytes": memory.Sys - memory.Alloc}
}

func procMemory() map[string]any {
	body, err := os.ReadFile("/proc/meminfo")
	if err != nil {
		return nil
	}
	values := map[string]uint64{}
	for _, line := range strings.Split(string(body), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		value, parseErr := strconv.ParseUint(fields[1], 10, 64)
		if parseErr == nil {
			values[strings.TrimSuffix(fields[0], ":")] = value * 1024
		}
	}
	total, ok := values["MemTotal"]
	if !ok {
		return nil
	}
	available := values["MemAvailable"]
	return map[string]any{"totalBytes": total, "usedBytes": total - min(total, available), "availableBytes": available}
}

func cpuStatus() map[string]any {
	model := runtime.GOARCH
	if body, err := os.ReadFile("/proc/cpuinfo"); err == nil {
		for _, line := range strings.Split(string(body), "\n") {
			key, value, found := strings.Cut(line, ":")
			if found && (strings.TrimSpace(key) == "model name" || strings.TrimSpace(key) == "Hardware") {
				model = strings.TrimSpace(value)
				break
			}
		}
	}
	loads := []float64{}
	if body, err := os.ReadFile("/proc/loadavg"); err == nil {
		fields := strings.Fields(string(body))
		for _, field := range fields[:min(3, len(fields))] {
			if value, parseErr := strconv.ParseFloat(field, 64); parseErr == nil {
				loads = append(loads, value)
			}
		}
	}
	return map[string]any{"model": model, "cores": runtime.NumCPU(), "loadAverage": loads}
}

func systemUptime() int64 {
	if body, err := os.ReadFile("/proc/uptime"); err == nil {
		var seconds float64
		if _, err = fmt.Sscanf(string(body), "%f", &seconds); err == nil {
			return int64(seconds)
		}
	}
	return int64(time.Since(processStarted).Seconds())
}

func temperature() any {
	entries, err := filepath.Glob("/sys/class/thermal/thermal_zone*/temp")
	if err != nil {
		return nil
	}
	for _, path := range entries {
		body, readErr := os.ReadFile(path)
		if readErr != nil {
			continue
		}
		value, parseErr := strconv.ParseFloat(strings.TrimSpace(string(body)), 64)
		if parseErr == nil && value > 0 {
			if value > 1000 {
				value /= 1000
			}
			return int64(value + 0.5)
		}
	}
	return nil
}
