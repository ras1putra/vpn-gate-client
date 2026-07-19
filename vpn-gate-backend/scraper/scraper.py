import sys
import os
import csv
import base64
import sqlite3
import datetime
import logging
from curl_cffi import requests

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")

MIRRORS = [
    "https://www.vpngate.net/api/iphone/",
    "http://www.vpngate.net/api/iphone/",
]

DEFAULT_PORT = 1194
DEFAULT_METHOD = "UDP"

def fetch_vpngate_csv():
    session = requests.Session(impersonate="chrome120")
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language": "en-US,en;q=0.9",
        "Accept": "*/*",
    })

    for mirror in MIRRORS:
        logging.info(f"Fetching VPNGate mirror using curl_cffi: {mirror}")
        try:
            resp = session.get(mirror, timeout=20)
            if resp.status_code == 200 and "HostName" in resp.text:
                logging.info(f"Successfully fetched {len(resp.content)} bytes from {mirror}")
                return resp.text
            else:
                logging.warning(f"Mirror {mirror} returned status {resp.status_code}")
        except Exception as e:
            logging.warning(f"Failed to fetch {mirror}: {e}")

    raise RuntimeError("All VPNGate mirrors failed to respond")

def parse_openvpn_config(config_b64):
    port = DEFAULT_PORT
    method = DEFAULT_METHOD
    try:
        decoded = base64.b64decode(config_b64).decode("utf-8", errors="ignore")
        lower_config = decoded.lower()
        if "proto tcp" in lower_config or "tcp-client" in lower_config:
            method = "TCP"
        
        for line in decoded.splitlines():
            line = line.strip()
            if line.startswith("remote "):
                parts = line.split()
                if len(parts) >= 3 and parts[2].isdigit():
                    port = int(parts[2])
    except Exception:
        pass
    return port, method

def format_uptime(uptime_ms_str):
    try:
        uptime_ms = int(uptime_ms_str)
        if uptime_ms > 0:
            hours = uptime_ms // (1000 * 60 * 60)
            if hours >= 24:
                return f"{hours // 24}d"
            return f"{hours}h"
    except (ValueError, TypeError):
        pass
    return "Unknown"

def sync_to_sqlite(csv_text, db_path="vpn.db"):
    lines = csv_text.splitlines()
    header_found = False
    csv_rows = []

    for line in lines:
        line = line.strip()
        if not line or line.startswith("*"):
            continue
        if line.startswith("#HostName") or line.startswith("HostName"):
            header_found = True
            continue
        if header_found:
            csv_rows.append(line)

    logging.info(f"Parsed {len(csv_rows)} CSV server records")

    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    # Ensure table exists
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
        is_active INTEGER DEFAULT 0,
        vpn_detected INTEGER DEFAULT 0,
        vpn_checked INTEGER DEFAULT 0,
        vpngate_flagged INTEGER NOT NULL DEFAULT 0,
        last_seen DATETIME NOT NULL,
        last_scraped DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        source TEXT NOT NULL DEFAULT 'vpngate',
        isp TEXT NOT NULL DEFAULT '',
        "as" TEXT NOT NULL DEFAULT '',
        hosting INTEGER NOT NULL DEFAULT 0,
        proxy INTEGER NOT NULL DEFAULT 0
    )
    """)

    upsert_query = """
    INSERT INTO servers (
        ip, host_name, port, score, ping, speed, country_long, country_short,
        operator, openvpn_config, server_type, uptime, method, is_active,
        vpn_detected, vpn_checked, vpngate_flagged, last_seen, last_scraped, source
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 1, ?, ?, 'vpngate')
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
        uptime = excluded.uptime,
        method = excluded.method,
        vpngate_flagged = 1,
        last_scraped = excluded.last_scraped,
        source = 'vpngate'
    """

    now_iso = datetime.datetime.now(datetime.timezone.utc).isoformat()
    success_count = 0

    for row_str in csv_rows:
        try:
            reader = csv.reader([row_str])
            record = next(reader)
            if len(record) < 11:
                continue

            host_name = record[0].strip()
            ip = record[1].strip()
            score = int(record[2].strip()) if record[2].strip().isdigit() else 0
            ping = int(record[3].strip()) if record[3].strip().isdigit() else 0
            speed = int(record[4].strip()) if record[4].strip().isdigit() else 0
            country_long = record[5].strip()
            country_short = record[6].strip()
            uptime_text = format_uptime(record[8].strip())
            
            operator = "VPNGate"
            if len(record) > 12 and record[12].strip():
                operator = record[12].strip()

            config_b64 = record[-1].strip()
            if not ip or not config_b64:
                continue

            port, method = parse_openvpn_config(config_b64)

            cursor.execute(upsert_query, (
                ip, host_name, port, score, ping, speed, country_long, country_short,
                operator, config_b64, "DATACENTER", uptime_text, method, now_iso, now_iso
            ))
            success_count += 1
        except Exception as e:
            logging.debug(f"Failed to parse row: {e}")

    conn.commit()
    conn.close()
    logging.info(f"Successfully synced {success_count} VPNGate servers into database at '{db_path}' with vpngate_flagged = 1")
    return success_count

def main():
    db_path = os.getenv("DB_PATH", "vpn.db")
    loop_interval = int(os.getenv("SCRAPE_INTERVAL_SECONDS", "1800"))

    if "--db" in sys.argv:
        idx = sys.argv.index("--db")
        if idx + 1 < len(sys.argv):
            db_path = sys.argv[idx + 1]

    loop_mode = "--loop" in sys.argv or os.getenv("LOOP_MODE", "false").lower() == "true"

    if loop_mode:
        logging.info(f"Starting Python curl_cffi scraper in daemon loop mode (interval: {loop_interval}s, DB: '{db_path}')")
        while True:
            try:
                csv_text = fetch_vpngate_csv()
                sync_to_sqlite(csv_text, db_path)
            except Exception as e:
                logging.error(f"Scrape cycle failed: {e}")
            logging.info(f"Sleeping for {loop_interval} seconds before next scrape cycle...")
            time.sleep(loop_interval)
    else:
        csv_text = fetch_vpngate_csv()
        sync_to_sqlite(csv_text, db_path)

if __name__ == "__main__":
    import time
    main()
