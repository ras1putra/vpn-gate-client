import os
import sqlite3
import logging
from typing import List, Dict, Any, Optional
from datetime import datetime

logger = logging.getLogger(__name__)

DB_PATH = os.getenv("DB_PATH", "vpn.db")


def get_db_connection(db_path: str = DB_PATH) -> sqlite3.Connection:
    conn = sqlite3.connect(db_path, timeout=30.0, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL;")
    conn.execute("PRAGMA busy_timeout=5000;")
    return conn


def init_db(db_path: str = DB_PATH):
    conn = get_db_connection(db_path)
    cursor = conn.cursor()

    cursor.execute("""
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
        is_active INTEGER DEFAULT NULL,
        exit_ip TEXT DEFAULT '',
        is_stealth INTEGER DEFAULT 0,
        is_advance_stealth INTEGER DEFAULT 0,
        vpngate_flagged INTEGER DEFAULT NULL,
        isp TEXT DEFAULT '',
        as_info TEXT DEFAULT '',
        hosting INTEGER DEFAULT 0,
        proxy INTEGER DEFAULT 0,
        last_seen DATETIME NOT NULL,
        last_scraped DATETIME DEFAULT CURRENT_TIMESTAMP,
        source TEXT DEFAULT 'vpngate'
    );
    """)

    cursor.execute("CREATE INDEX IF NOT EXISTS idx_servers_is_active ON servers(is_active);")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_servers_is_stealth ON servers(is_stealth);")
    cursor.execute("CREATE INDEX IF NOT EXISTS idx_servers_is_advance_stealth ON servers(is_advance_stealth);")

    conn.commit()
    conn.close()
    logger.info(f"Database initialized at {db_path}")


def upsert_scraped_server(server_data: Dict[str, Any], db_path: str = DB_PATH):
    conn = get_db_connection(db_path)
    try:
        cursor = conn.cursor()
        now = datetime.utcnow().isoformat()

        cursor.execute("""
        INSERT INTO servers (
            ip, host_name, port, score, ping, speed, country_long, country_short,
            operator, openvpn_config, server_type, uptime, method, is_active,
            last_seen, last_scraped, source
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, 'vpngate')
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
            last_scraped = excluded.last_scraped;
        """, (
            server_data["ip"], server_data["host_name"], server_data["port"],
            server_data["score"], server_data["ping"], server_data["speed"],
            server_data["country_long"], server_data["country_short"],
            server_data["operator"], server_data["openvpn_config"],
            server_data["server_type"], server_data["uptime"], server_data["method"],
            now, now
        ))

        conn.commit()
    finally:
        conn.close()


def update_probed_liveness(ip: str, is_active: bool, exit_ip: str = "", db_path: str = DB_PATH):
    conn = get_db_connection(db_path)
    try:
        cursor = conn.cursor()
        now = datetime.utcnow().isoformat()

        cursor.execute("""
        UPDATE servers
        SET is_active = ?, exit_ip = ?, last_seen = ?
        WHERE ip = ?
        """, (1 if is_active else 0, exit_ip, now, ip))

        conn.commit()
    finally:
        conn.close()


def update_security_classification(
    ip: str,
    isp: str,
    as_info: str,
    hosting: bool,
    proxy: bool,
    vpngate_flagged: Optional[bool],
    is_stealth: bool,
    is_advance_stealth: bool,
    db_path: str = DB_PATH
):
    conn = get_db_connection(db_path)
    try:
        cursor = conn.cursor()

        cursor.execute("""
        UPDATE servers
        SET isp = ?, as_info = ?, hosting = ?, proxy = ?,
            vpngate_flagged = ?, is_stealth = ?, is_advance_stealth = ?
        WHERE ip = ?
        """, (
            isp, as_info, 1 if hosting else 0, 1 if proxy else 0,
            1 if vpngate_flagged else (0 if vpngate_flagged is False else None),
            1 if is_stealth else 0, 1 if is_advance_stealth else 0,
            ip
        ))

        conn.commit()
    finally:
        conn.close()


def get_active_servers(
    server_type: Optional[str] = None,
    country_short: Optional[str] = None,
    stealth_tier: Optional[str] = None,
    db_path: str = DB_PATH
) -> List[Dict[str, Any]]:
    conn = get_db_connection(db_path)
    cursor = conn.cursor()

    query = "SELECT * FROM servers WHERE is_active = 1 AND exit_ip != '' AND isp != ''"
    params = []

    if server_type:
        query += " AND server_type = ?"
        params.append(server_type)

    if country_short:
        query += " AND country_short = ?"
        params.append(country_short.upper())

    if stealth_tier == "STEALTH":
        query += " AND is_stealth = 1"
    elif stealth_tier == "ADVANCE_STEALTH":
        query += " AND is_advance_stealth = 1"

    query += " ORDER BY score DESC, speed DESC"

    cursor.execute(query, params)
    rows = cursor.fetchall()
    conn.close()

    return [dict(row) for row in rows]


def get_all_servers(db_path: str = DB_PATH) -> List[Dict[str, Any]]:
    conn = get_db_connection(db_path)
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM servers ORDER BY last_scraped DESC")
    rows = cursor.fetchall()
    conn.close()
    return [dict(row) for row in rows]


def get_server_by_ip(ip: str, db_path: str = DB_PATH) -> Optional[Dict[str, Any]]:
    conn = get_db_connection(db_path)
    cursor = conn.cursor()
    cursor.execute("SELECT * FROM servers WHERE ip = ?", (ip,))
    row = cursor.fetchone()
    conn.close()
    return dict(row) if row else None
