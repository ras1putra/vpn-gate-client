package validator

import (
	"context"
	"crypto/tls"
	"fmt"
	"log/slog"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"vpn-gate-backend/internal/database"
)

func ValidateAllServers() {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Minute)
	defer cancel()

	servers, err := database.GetAllServers(ctx)
	if err != nil {
		slog.Error("validator: failed to query servers", "error", err)
		return
	}

	slog.Info("validator: starting validation", "total", len(servers))

	var wg sync.WaitGroup
	semaphore := make(chan struct{}, 50)
	var activeCount atomic.Int64

	for _, s := range servers {
		wg.Add(1)
		go func(srv database.VpnServer) {
			defer wg.Done()
			semaphore <- struct{}{}
			defer func() { <-semaphore }()

			isActive := probeVpnConnection(srv.IP, srv.Port, srv.Method)
			serverType := classifyServer(srv.IP, srv.HostName)

			isActiveVal := 0
			if isActive {
				isActiveVal = 1
				activeCount.Add(1)
			}

			updateCtx, updateCancel := context.WithTimeout(ctx, 5*time.Second)
			defer updateCancel()

			query := `UPDATE servers SET is_active = ?, server_type = ?, last_seen = ? WHERE ip = ?`
			if _, dbErr := database.DB.ExecContext(updateCtx, query, isActiveVal, serverType, time.Now(), srv.IP); dbErr != nil {
				slog.Error("validator: failed to update", "ip", srv.IP, "error", dbErr)
			}
		}(s)
	}

	wg.Wait()
	slog.Info("validator: finished", "active", activeCount.Load(), "total", len(servers))
}

func classifyServer(ip string, fallbackHost string) string {
	names, err := net.LookupAddr(ip)
	var rDns string
	if err == nil && len(names) > 0 {
		rDns = strings.ToLower(names[0])
	} else {
		rDns = strings.ToLower(fallbackHost)
	}

	academicKeywords := []string{".edu", ".ac.", "school", "univ", "college", "academy"}
	for _, kw := range academicKeywords {
		if strings.Contains(rDns, kw) {
			return "ACADEMIC"
		}
	}

	residentialKeywords := []string{
		".isp", ".res", "telecom", "dynamic", "pool", "home",
		"dsl", "cable", "fiber", "user", "dial", "dhcp",
	}
	for _, kw := range residentialKeywords {
		if strings.Contains(rDns, kw) {
			return "RESIDENTIAL"
		}
	}

	return "DATACENTER"
}

func probeVpnConnection(ip string, port int, method string) bool {
	address := net.JoinHostPort(ip, fmt.Sprintf("%d", port))

	if strings.ToLower(method) == "tcp" {
		if !probeTcpConnection(address) {
			return false
		}
		return probeTlsAndOpenVpn(address)
	}

	return probeUdpConnection(address)
}

func probeTcpConnection(address string) bool {
	conn, err := net.DialTimeout("tcp", address, 3*time.Second)
	if err != nil {
		return false
	}
	conn.Close()
	return true
}

func probeTlsAndOpenVpn(address string) bool {
	dialer := &net.Dialer{Timeout: 3 * time.Second}
	conn, err := tls.DialWithDialer(dialer, "tcp", address, &tls.Config{
		InsecureSkipVerify: true,
	})
	if err != nil {
		return false
	}
	defer conn.Close()

	// TCP OpenVPN: 2-byte length prefix + P_CONTROL_HARD_RESET_CLIENT_V2
	openVpnReset := []byte{0x00, 0x01, 0x38}
	if _, err = conn.Write(openVpnReset); err != nil {
		return false
	}

	if err := conn.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
		return false
	}

	buf := make([]byte, 128)
	n, err := conn.Read(buf)
	if err != nil || n < 1 {
		return false
	}

	// Valid OpenVPN opcode in high 5 bits (1-10 range)
	opcode := buf[0] >> 3
	return opcode >= 1 && opcode <= 10
}

func probeUdpConnection(address string) bool {
	conn, err := net.DialTimeout("udp", address, 3*time.Second)
	if err != nil {
		return false
	}
	defer conn.Close()

	openVpnPing := []byte{0x38, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
	if _, err = conn.Write(openVpnPing); err != nil {
		return false
	}

	if err := conn.SetReadDeadline(time.Now().Add(3 * time.Second)); err != nil {
		return false
	}

	buf := make([]byte, 64)
	_, err = conn.Read(buf)
	return err == nil
}
