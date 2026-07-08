package database

import (
	"context"
	"database/sql"
	"fmt"
	"log/slog"
	"time"

	_ "github.com/glebarez/go-sqlite"
)

var DB *sql.DB

type VpnServer struct {
	HostName            string    `json:"hostName"            example:"vpn123456789.opengw.net"`
	IP                  string    `json:"ip"                  example:"1.2.3.4"`
	Port                int       `json:"port"                example:"1194"`
	Score               int64     `json:"score"               example:"95000"`
	Ping                int       `json:"ping"                example:"42"`
	Speed               int64     `json:"speed"               example:"102400000"`
	CountryLong         string    `json:"countryLong"         example:"Japan"`
	CountryShort        string    `json:"countryShort"        example:"JP"`
	Operator            string    `json:"operator"            example:"VPNGate"`
	OpenVpnConfigBase64 string    `json:"openVpnConfigBase64" example:"IyEvdXNyL2Jpbi9lbnYgYmFzaA=="`
	ServerType          string    `json:"serverType"          example:"DATACENTER"    enums:"RESIDENTIAL,ACADEMIC,DATACENTER"`
	Uptime              string    `json:"uptime"              example:"30d"`
	Method              string    `json:"method"              example:"UDP"           enums:"UDP,TCP"`
	IsActive            bool      `json:"isActive"            example:"true"`
	VpnDetected         bool      `json:"vpnDetected"         example:"false"`
	VpnChecked          bool      `json:"vpnChecked"          example:"true"`
	LastSeen            time.Time `json:"lastSeen"            example:"2026-01-15T10:30:00Z"`
	LastScraped         time.Time `json:"lastScraped"         example:"2026-01-15T10:30:00Z"`
	Source              string    `json:"source"              example:"vpngate"`
}

func InitDB(dbPath string) error {
	var err error
	DB, err = sql.Open("sqlite", dbPath)
	if err != nil {
		return fmt.Errorf("failed to open database: %w", err)
	}

	if err = DB.Ping(); err != nil {
		return fmt.Errorf("failed to ping database: %w", err)
	}

	DB.SetMaxOpenConns(1)
	DB.SetMaxIdleConns(1)
	DB.SetConnMaxLifetime(0)

	if _, err = DB.Exec("PRAGMA journal_mode=WAL"); err != nil {
		return fmt.Errorf("failed to enable WAL mode: %w", err)
	}
	if _, err = DB.Exec("PRAGMA busy_timeout=5000"); err != nil {
		return fmt.Errorf("failed to set busy_timeout: %w", err)
	}

	query := `
	CREATE TABLE IF NOT EXISTS servers (
		ip TEXT PRIMARY KEY,
		host_name TEXT NOT NULL,
		port INTEGER NOT NULL,
		score INTEGER NOT NULL,
		ping INTEGER NOT NULL,
		speed INTEGER NOT NULL,
		country_long TEXT NOT NULL,
		country_short TEXT NOT NULL,
		operator TEXT NOT NULL,
		openvpn_config TEXT NOT NULL,
		server_type TEXT NOT NULL,
		uptime TEXT NOT NULL,
		method TEXT NOT NULL,
		is_active INTEGER DEFAULT 0,
		vpn_detected INTEGER DEFAULT 0,
		vpn_checked INTEGER DEFAULT 0,
		last_seen DATETIME NOT NULL,
		last_scraped DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
		source TEXT NOT NULL DEFAULT 'vpngate'
	);
	CREATE INDEX IF NOT EXISTS idx_servers_is_active ON servers(is_active);
	CREATE INDEX IF NOT EXISTS idx_servers_last_scraped ON servers(last_scraped);
	`

	if _, err = DB.Exec(query); err != nil {
		return fmt.Errorf("failed to create tables: %w", err)
	}

	migrations := []string{
		`ALTER TABLE servers ADD COLUMN last_scraped DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP`,
		`ALTER TABLE servers ADD COLUMN source TEXT NOT NULL DEFAULT 'vpngate'`,
		`ALTER TABLE servers ADD COLUMN vpn_detected INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE servers ADD COLUMN vpn_checked INTEGER NOT NULL DEFAULT 0`,
	}
	for _, m := range migrations {
		if _, err := DB.Exec(m); err != nil {
			slog.Debug("migration skipped (column likely exists)", "error", err)
		}
	}

	slog.Info("database initialized", "path", dbPath)
	return nil
}

func UpsertServer(ctx context.Context, s VpnServer) error {
	query := `
	INSERT INTO servers (ip, host_name, port, score, ping, speed, country_long, country_short, operator, openvpn_config, server_type, uptime, method, is_active, vpn_detected, vpn_checked, last_seen, last_scraped, source)
	VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	ON CONFLICT(ip) DO UPDATE SET
		host_name = excluded.host_name,
		port = excluded.port,
		score = excluded.score,
		ping = excluded.ping,
		speed = excluded.speed,
		country_long = excluded.country_long,
		country_short = excluded.country_short,
		operator = excluded.operator,
		openvpn_config = excluded.openvpn_config,
		server_type = excluded.server_type,
		uptime = excluded.uptime,
		method = excluded.method,
		is_active = excluded.is_active,
		last_seen = excluded.last_seen,
		last_scraped = excluded.last_scraped,
		source = excluded.source;
	`

	_, err := DB.ExecContext(ctx, query,
		s.IP, s.HostName, s.Port, s.Score, s.Ping, s.Speed,
		s.CountryLong, s.CountryShort, s.Operator, s.OpenVpnConfigBase64,
		s.ServerType, s.Uptime, s.Method,
		boolToInt(s.IsActive), boolToInt(s.VpnDetected), boolToInt(s.VpnChecked),
		s.LastSeen, s.LastScraped, s.Source,
	)
	if err != nil {
		return fmt.Errorf("failed to upsert server %s: %w", s.IP, err)
	}
	return nil
}

func UpdateServerStatus(ctx context.Context, ip string, isActive bool) error {
	query := `UPDATE servers SET is_active = ?, last_seen = ? WHERE ip = ?`
	_, err := DB.ExecContext(ctx, query, boolToInt(isActive), time.Now(), ip)
	if err != nil {
		return fmt.Errorf("failed to update server status for %s: %w", ip, err)
	}
	return nil
}

func MarkStaleServersInactive(ctx context.Context, threshold time.Duration) (int64, error) {
	cutoff := time.Now().Add(-threshold)
	query := `UPDATE servers SET is_active = 0 WHERE is_active = 1 AND last_seen < ?`
	res, err := DB.ExecContext(ctx, query, cutoff)
	if err != nil {
		return 0, fmt.Errorf("failed to mark stale servers: %w", err)
	}
	rowsAffected, err := res.RowsAffected()
	if err != nil {
		return 0, fmt.Errorf("failed to get rows affected: %w", err)
	}
	return rowsAffected, nil
}

func GetActiveServers(ctx context.Context, serverType string, countryShort string) ([]VpnServer, error) {
	query := `SELECT ip, host_name, port, score, ping, speed, country_long, country_short, operator, openvpn_config, server_type, uptime, method, is_active, vpn_detected, vpn_checked, last_seen, last_scraped, source
	          FROM servers WHERE is_active = 1`
	var args []any

	if serverType != "" {
		query += ` AND server_type = ?`
		args = append(args, serverType)
	}
	if countryShort != "" {
		query += ` AND country_short = ?`
		args = append(args, countryShort)
	}

	return scanServers(ctx, query, args...)
}

func GetAllServers(ctx context.Context) ([]VpnServer, error) {
	query := `SELECT ip, host_name, port, score, ping, speed, country_long, country_short, operator, openvpn_config, server_type, uptime, method, is_active, vpn_detected, vpn_checked, last_seen, last_scraped, source FROM servers`
	return scanServers(ctx, query)
}

func scanServers(ctx context.Context, query string, args ...any) ([]VpnServer, error) {
	rows, err := DB.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("failed to query servers: %w", err)
	}
	defer rows.Close()

	var list []VpnServer
	for rows.Next() {
		var s VpnServer
		var isActiveVal, vpnDetectedVal, vpnCheckedVal int
		if err := rows.Scan(
			&s.IP, &s.HostName, &s.Port, &s.Score, &s.Ping, &s.Speed,
			&s.CountryLong, &s.CountryShort, &s.Operator, &s.OpenVpnConfigBase64,
			&s.ServerType, &s.Uptime, &s.Method,
			&isActiveVal, &vpnDetectedVal, &vpnCheckedVal,
			&s.LastSeen, &s.LastScraped, &s.Source,
		); err != nil {
			return nil, fmt.Errorf("failed to scan server row: %w", err)
		}
		s.IsActive = isActiveVal == 1
		s.VpnDetected = vpnDetectedVal == 1
		s.VpnChecked = vpnCheckedVal == 1
		list = append(list, s)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("rows iteration error: %w", err)
	}
	return list, nil
}

func boolToInt(b bool) int {
	if b {
		return 1
	}
	return 0
}
