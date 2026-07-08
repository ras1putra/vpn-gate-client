package vpncheck

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"time"

	"vpn-gate-backend/internal/database"
)

type ipApiRequest struct {
	Query string `json:"query"`
}

type ipApiResponse struct {
	Query   string `json:"query"`
	Hosting bool   `json:"Hosting"`
	Status  string `json:"status"`
}

type vpnApiResponse struct {
	Security struct {
		VPN   bool `json:"vpn"`
		Proxy bool `json:"proxy"`
		Tor   bool `json:"tor"`
	} `json:"security"`
}

var vpnApiKey = os.Getenv("VPNAPI_KEY")

func CheckVpnDetection(ctx context.Context, servers []database.VpnServer) error {
	if len(servers) == 0 {
		return nil
	}

	var unchecked []database.VpnServer
	for _, s := range servers {
		if !s.VpnChecked {
			unchecked = append(unchecked, s)
		}
	}

	if len(unchecked) == 0 {
		return nil
	}

	slog.Info("vpncheck: stage1 starting", "unchecked", len(unchecked))

	// Stage 1: ip-api.com batch (hosting check)
	batches := chunkServers(unchecked, 100)
	for _, batch := range batches {
		if err := checkHostingBatch(ctx, batch); err != nil {
			slog.Error("vpncheck: stage1 batch failed", "error", err)
			continue
		}
	}

	// Stage 2: vpnapi.io (optional, non-blocking)
	if vpnApiKey == "" {
		slog.Info("vpncheck: VPNAPI_KEY not set, skipping stage2")
		return nil
	}

	var needVpnapi []database.VpnServer
	for _, s := range unchecked {
		if !s.VpnDetected {
			needVpnapi = append(needVpnapi, s)
		}
	}

	if len(needVpnapi) == 0 {
		return nil
	}

	slog.Info("vpncheck: stage2 starting", "needVpnapi", len(needVpnapi))

	for _, s := range needVpnapi {
		if err := checkVpnApi(ctx, s); err != nil {
			slog.Error("vpncheck: stage2 failed", "ip", s.IP, "error", err)
		}
		time.Sleep(100 * time.Millisecond)
	}

	return nil
}

func chunkServers(servers []database.VpnServer, size int) [][]database.VpnServer {
	var chunks [][]database.VpnServer
	for i := 0; i < len(servers); i += size {
		end := i + size
		if end > len(servers) {
			end = len(servers)
		}
		chunks = append(chunks, servers[i:end])
	}
	return chunks
}

func checkHostingBatch(ctx context.Context, batch []database.VpnServer) error {
	queries := make([]ipApiRequest, len(batch))
	for i, s := range batch {
		queries[i] = ipApiRequest{Query: s.IP}
	}

	body, err := json.Marshal(queries)
	if err != nil {
		return fmt.Errorf("failed to marshal batch: %w", err)
	}

	req, err := http.NewRequestWithContext(ctx, "POST", "http://ip-api.com/batch?fields=status,hosting,query", bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	client := &http.Client{Timeout: 15 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to call ip-api.com: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("ip-api.com returned status %d", resp.StatusCode)
	}

	var results []ipApiResponse
	if err := json.NewDecoder(resp.Body).Decode(&results); err != nil {
		return fmt.Errorf("failed to decode response: %w", err)
	}

	resultMap := make(map[string]bool, len(results))
	for _, r := range results {
		if r.Status == "success" {
			resultMap[r.Query] = r.Hosting
		}
	}

	for _, s := range batch {
		isHosting, found := resultMap[s.IP]
		if !found {
			continue
		}

		vpnDetectedVal := 0
		if isHosting {
			vpnDetectedVal = 1
		}

		updateCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		query := `UPDATE servers SET vpn_detected = ?, vpn_checked = 1 WHERE ip = ?`
		if _, err := database.DB.ExecContext(updateCtx, query, vpnDetectedVal, s.IP); err != nil {
			slog.Error("vpncheck: failed to update", "ip", s.IP, "error", err)
		}
		cancel()
	}

	slog.Info("vpncheck: stage1 completed", "checked", len(batch))
	return nil
}

func checkVpnApi(ctx context.Context, s database.VpnServer) error {
	url := fmt.Sprintf("https://vpnapi.io/api/%s?key=%s", s.IP, vpnApiKey)

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return fmt.Errorf("failed to call vpnapi.io: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("vpnapi.io returned status %d", resp.StatusCode)
	}

	var result vpnApiResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return fmt.Errorf("failed to decode vpnapi response: %w", err)
	}

	vpnDetected := result.Security.VPN || result.Security.Proxy || result.Security.Tor
	vpnDetectedVal := 0
	if vpnDetected {
		vpnDetectedVal = 1
	}

	updateCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	query := `UPDATE servers SET vpn_detected = ?, vpn_checked = 1 WHERE ip = ?`
	if _, err := database.DB.ExecContext(updateCtx, query, vpnDetectedVal, s.IP); err != nil {
		return fmt.Errorf("failed to update: %w", err)
	}

	slog.Info("vpncheck: stage2 result", "ip", s.IP, "vpn", result.Security.VPN, "proxy", result.Security.Proxy, "tor", result.Security.Tor)
	return nil
}
