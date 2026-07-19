package scraper

import (
	"context"
	"encoding/base64"
	"encoding/csv"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand"
	"net/http"
	"strconv"
	"strings"
	"time"

	"vpn-gate-backend/internal/database"
)

const (
	maxBodySize   = 10 << 20 // 10MB
	defaultPort   = 1194
	defaultMethod = "UDP"
	userAgent     = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
)

var Mirrors = []string{
	"https://www.vpngate.net/api/iphone/",
	"http://www.vpngate.net/api/iphone/",
}

func ScrapeAndSync() error {
	rawContent, err := fetchRawCsvWithMirrorRotation()
	if err != nil {
		return err
	}

	lines := strings.Split(rawContent, "\n")
	var csvLines []string
	headerFound := false

	for _, line := range lines {
		trimmed := strings.TrimSpace(line)
		if trimmed == "" || strings.HasPrefix(trimmed, "*") {
			continue
		}
		if strings.HasPrefix(trimmed, "#HostName") || strings.HasPrefix(trimmed, "HostName") {
			headerFound = true
			continue
		}
		if headerFound {
			csvLines = append(csvLines, trimmed)
		}
	}

	if len(csvLines) == 0 {
		return errors.New("no server data rows found in scraped CSV")
	}

	slog.Info("parsed servers from API", "count", len(csvLines))

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()

	successCount := 0
	for _, rowStr := range csvLines {
		s, err := parseServerRow(rowStr)
		if err != nil {
			slog.Debug("failed to parse row", "error", err)
			continue
		}
		if err := database.UpsertServer(ctx, s); err != nil {
			slog.Warn("failed to upsert server", "ip", s.IP, "error", err)
		} else {
			successCount++
		}
	}

	slog.Info("synced servers to database", "count", successCount)
	return nil
}

func fetchRawCsvWithMirrorRotation() (string, error) {
	perm := rand.Perm(len(Mirrors))

	var lastErr error
	for _, idx := range perm {
		mirror := Mirrors[idx]
		slog.Info("fetching VPN list", "mirror", mirror)

		body, err := fetchMirror(mirror)
		if err != nil {
			slog.Warn("mirror failed", "mirror", mirror, "error", err)
			lastErr = err
			continue
		}

		slog.Info("fetched successfully", "mirror", mirror, "bytes", len(body))
		return string(body), nil
	}

	return "", fmt.Errorf("all mirrors failed: %w", lastErr)
}

func fetchMirror(mirror string) ([]byte, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
	defer cancel()

	req, err := http.NewRequestWithContext(ctx, "GET", mirror, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("User-Agent", userAgent)
	req.Header.Set("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
	req.Header.Set("Accept-Language", "en-US,en;q=0.9")
	req.Header.Set("Sec-Ch-Ua", `"Not_A Brand";v="8", "Chromium";v="120", "Google Chrome";v="120"`)
	req.Header.Set("Sec-Ch-Ua-Mobile", "?0")
	req.Header.Set("Sec-Ch-Ua-Platform", `"Windows"`)
	req.Header.Set("Sec-Fetch-Dest", "document")
	req.Header.Set("Sec-Fetch-Mode", "navigate")
	req.Header.Set("Sec-Fetch-Site", "none")
	req.Header.Set("Sec-Fetch-User", "?1")
	req.Header.Set("Upgrade-Insecure-Requests", "1")

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("bad HTTP status: %d", resp.StatusCode)
	}

	bodyBytes, err := io.ReadAll(io.LimitReader(resp.Body, maxBodySize))
	if err != nil {
		return nil, err
	}

	if !strings.Contains(string(bodyBytes), "HostName") {
		return nil, fmt.Errorf("invalid API content (likely redirect or HTML)")
	}

	return bodyBytes, nil
}

func parseServerRow(rowStr string) (database.VpnServer, error) {
	reader := csv.NewReader(strings.NewReader(rowStr))
	record, err := reader.Read()
	if err != nil || len(record) < 11 {
		return database.VpnServer{}, fmt.Errorf("invalid row")
	}

	hostName := strings.TrimSpace(record[0])
	ip := strings.TrimSpace(record[1])
	score, _ := strconv.ParseInt(strings.TrimSpace(record[2]), 10, 64)
	ping, _ := strconv.Atoi(strings.TrimSpace(record[3]))
	speed, _ := strconv.ParseInt(strings.TrimSpace(record[4]), 10, 64)
	countryLong := strings.TrimSpace(record[5])
	countryShort := strings.TrimSpace(record[6])

	uptimeMs, _ := strconv.ParseInt(strings.TrimSpace(record[8]), 10, 64)
	uptimeText := "Unknown"
	if uptimeMs > 0 {
		hours := uptimeMs / (1000 * 60 * 60)
		if hours >= 24 {
			uptimeText = fmt.Sprintf("%dd", hours/24)
		} else {
			uptimeText = fmt.Sprintf("%dh", hours)
		}
	}

	operator := "VPNGate"
	if len(record) > 12 && strings.TrimSpace(record[12]) != "" {
		operator = strings.TrimSpace(record[12])
	}

	openVpnConfigBase64 := strings.TrimSpace(record[len(record)-1])

	if ip == "" || openVpnConfigBase64 == "" {
		return database.VpnServer{}, fmt.Errorf("missing ip or config")
	}

	port := defaultPort
	method := defaultMethod
	decodedBytes, err := base64.StdEncoding.DecodeString(openVpnConfigBase64)
	if err == nil {
		configText := string(decodedBytes)
		if strings.Contains(strings.ToLower(configText), "proto tcp") ||
			strings.Contains(strings.ToLower(configText), "tcp-client") {
			method = "TCP"
		}

		for _, confLine := range strings.Split(configText, "\n") {
			confLine = strings.TrimSpace(confLine)
			if strings.HasPrefix(confLine, "remote ") {
				parts := strings.Fields(confLine)
				if len(parts) >= 3 {
					if pVal, err := strconv.Atoi(parts[2]); err == nil {
						port = pVal
					}
				}
			}
		}
	}

	return database.VpnServer{
		HostName:            hostName,
		IP:                  ip,
		Port:                port,
		Score:               score,
		Ping:                ping,
		Speed:               speed,
		CountryLong:         countryLong,
		CountryShort:        countryShort,
		Operator:            operator,
		OpenVpnConfigBase64: openVpnConfigBase64,
		ServerType:          "DATACENTER",
		Uptime:              uptimeText,
		Method:              method,
		IsActive:            false,
		LastSeen:            time.Now(),
		LastScraped:         time.Now(),
		Source:              "vpngate",
	}, nil
}


