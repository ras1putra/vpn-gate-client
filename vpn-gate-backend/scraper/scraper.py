import sys
import os
import sqlite3
import logging
import time
import re
import json
from curl_cffi import requests

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
)

BATCH_SIZE = 50
REQUEST_DELAY = 1.5
LOOP_INTERVAL = 600

NODEDATA_URL = "https://nodedata.io/vpn-detection-test"
NODEDATA_API = "https://nodedata.io/demo/api"


def check_ip(session, ip, token):
    resp = session.post(
        NODEDATA_API,
        data={"value": ip, "token": token},
        timeout=20,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"HTTP {resp.status_code}")

    data = resp.json()
    if "error" in data:
        logging.warning("nodedata error for %s: %s", ip, data["error"])
        return None

    if data.get("security", {}).get("vpn") is True:
        provider = data.get("security", {}).get("provider", "")
        if provider == "VPN Gate":
            return True
        return False

    return False


def process_batch(session, db_path):
    conn = sqlite3.connect(db_path)
    cursor = conn.cursor()

    try:
        cursor.execute(
            "SELECT ip FROM servers WHERE vpngate_flagged IS NULL LIMIT ?",
            (BATCH_SIZE,),
        )
        rows = cursor.fetchall()
    except sqlite3.OperationalError as e:
        logging.error("DB error during query: %s", e)
        conn.close()
        return 0

    if not rows:
        conn.close()
        return 0

    logging.info("Checking %d IPs with NULL vpngate_flagged", len(rows))

    try:
        html = session.get(NODEDATA_URL, timeout=20).text
        match = re.search(r'token:"([^"]+)"', html)
        if not match:
            logging.error("Could not extract token from nodedata.io")
            conn.close()
            return 0
        token = match.group(1)
    except Exception as e:
        logging.error("Failed to fetch token from nodedata.io: %s", e)
        conn.close()
        return 0

    checked = 0
    for (ip,) in rows:
        try:
            time.sleep(REQUEST_DELAY)
            result = check_ip(session, ip, token)
            if result is True:
                cursor.execute(
                    "UPDATE servers SET vpngate_flagged = 1 WHERE ip = ?", (ip,)
                )
                logging.info("VPN Gate confirmed: %s", ip)
                checked += 1
            elif result is False:
                cursor.execute(
                    "UPDATE servers SET vpngate_flagged = 0 WHERE ip = ?", (ip,)
                )
                checked += 1
        except Exception as e:
            logging.error("Failed to check %s: %s", ip, e)

    conn.commit()
    conn.close()
    logging.info("Batch done: %d IPs flagged", checked)
    return checked


def main():
    db_path = os.getenv("DB_PATH", "vpn.db")

    if "--db" in sys.argv:
        idx = sys.argv.index("--db")
        if idx + 1 < len(sys.argv):
            db_path = sys.argv[idx + 1]

    interval = int(os.getenv("SCRAPE_INTERVAL_SECONDS", str(LOOP_INTERVAL)))

    loop_mode = "--loop" in sys.argv or os.getenv("LOOP_MODE", "false").lower() == "true"

    session = requests.Session(impersonate="chrome120")
    session.headers.update({
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept-Language": "en-US,en;q=0.9",
    })

    if loop_mode:
        logging.info(
            "Starting VPN Gate flagger (interval: %ds, DB: '%s')",
            interval,
            db_path,
        )
        while True:
            try:
                count = process_batch(session, db_path)
                if count == 0:
                    logging.info(
                        "No more NULL records. Sleeping %ds...", interval
                    )
                else:
                    logging.info(
                        "Sleeping %ds before next batch...", interval
                    )
            except Exception as e:
                logging.error("Cycle failed: %s", e)

            time.sleep(interval)
    else:
        process_batch(session, db_path)


if __name__ == "__main__":
    main()
