package vpncheck

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"os"
	"strings"
	"time"

	"vpn-gate-backend/internal/database"
)

const (
	ipApiURL   = "http://ip-api.com/batch?fields=status,query,isp,as,hosting,proxy"
	vpnApiURL  = "https://vpnapi.io/api/"
	maxRespSize = 1 << 20 // 1MB
)

type ipApiRequest struct {
	Query string `json:"query"`
}

type ipApiResponse struct {
	Query   string `json:"query"`
	Isp     string `json:"isp"`
	As      string `json:"as"`
	Hosting bool   `json:"hosting"`
	Proxy   bool   `json:"proxy"`
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

	if vpnApiKey == "" {
		slog.Info("vpncheck: VPNAPI_KEY not set, skipping stage2")
		return nil
	}

	// Re-query DB to get fresh vpn_detected values after stage1
	freshServers, err := database.GetAllServers(ctx)
	if err != nil {
		slog.Error("vpncheck: failed to re-query servers for stage2", "error", err)
		return nil
	}

	var needVpnapi []database.VpnServer
	for _, s := range freshServers {
		if s.VpnChecked && !s.VpnDetected {
			needVpnapi = append(needVpnapi, s)
		}
	}

	if len(needVpnapi) == 0 {
		return nil
	}

	slog.Info("vpncheck: stage2 starting", "needVpnapi", len(needVpnapi))

	for _, s := range needVpnapi {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		if err := checkVpnApi(ctx, s); err != nil {
			slog.Error("vpncheck: stage2 failed", "ip", s.IP, "error", err)
		}
		select {
		case <-time.After(100 * time.Millisecond):
		case <-ctx.Done():
			return ctx.Err()
		}
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

	req, err := http.NewRequestWithContext(ctx, "POST", ipApiURL, bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to call ip-api.com: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("ip-api.com returned status %d", resp.StatusCode)
	}

	var results []ipApiResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxRespSize)).Decode(&results); err != nil {
		return fmt.Errorf("failed to decode response: %w", err)
	}

	resultMap := make(map[string]ipApiResponse, len(results))
	for _, r := range results {
		if r.Status == "success" {
			resultMap[r.Query] = r
		}
	}

	for _, s := range batch {
		result, found := resultMap[s.IP]
		if !found {
			continue
		}

		vpnDetectedVal := 0
		if result.Hosting {
			vpnDetectedVal = 1
		}

		serverType := classifyServer(result.Isp, result.As, result.Hosting)

		updateCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
		query := `UPDATE servers SET vpn_detected = ?, vpn_checked = 1, isp = ?, "as" = ?, hosting = ?, proxy = ?, server_type = ? WHERE ip = ?`
		if _, err := database.DB.ExecContext(updateCtx, query,
			vpnDetectedVal,
			result.Isp, result.As, boolToInt(result.Hosting), boolToInt(result.Proxy),
			serverType, s.IP); err != nil {
			slog.Error("vpncheck: failed to update", "ip", s.IP, "error", err)
		}
		cancel()
	}

	slog.Info("vpncheck: stage1 completed", "checked", len(batch))
	return nil
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}

func classifyServer(isp, as string, hosting bool) string {
	if hosting {
		return "DATACENTER"
	}

	lowerISP := strings.ToLower(isp)
	lowerAS := strings.ToLower(as)

	academicKeywords := []string{".edu", ".ac.", "school", "univ", "college", "academy", "university", "institute"}
	for _, kw := range academicKeywords {
		if strings.Contains(lowerISP, kw) || strings.Contains(lowerAS, kw) {
			return "ACADEMIC"
		}
	}

	residentialKeywords := []string{
		".isp", ".res", "telecom", "dynamic", "pool", "home",
		"dsl", "cable", "fiber", "user", "dial", "dhcp",
		"kt", "kornet", "sk broadband", "lg uplus", "kta", "skt", "lg",
		"kddi", "ntt", "softbank", "ocn", "so-net", "biglobe",
		"china telecom", "china unicom", "china mobile",
		"nippon telegraph", "tokyo electric", "chubu electric",
	}
	for _, kw := range residentialKeywords {
		if strings.Contains(lowerISP, kw) || strings.Contains(lowerAS, kw) {
			return "RESIDENTIAL"
		}
	}

	return "DATACENTER"
}

func checkVpnApi(ctx context.Context, s database.VpnServer) error {
	url := fmt.Sprintf("%s%s?key=%s", vpnApiURL, s.IP, vpnApiKey)

	req, err := http.NewRequestWithContext(ctx, "GET", url, nil)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to call vpnapi.io: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("vpnapi.io returned status %d", resp.StatusCode)
	}

	var result vpnApiResponse
	if err := json.NewDecoder(io.LimitReader(resp.Body, maxRespSize)).Decode(&result); err != nil {
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
