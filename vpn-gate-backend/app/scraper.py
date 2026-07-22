import csv
import io
import re
import base64
import logging
import requests
from typing import Dict, Any
from app.database import upsert_scraped_server, init_db

logger = logging.getLogger(__name__)

VPNGATE_API_URL = "http://www.vpngate.net/api/iphone/"


def classify_server_type(hostname: str, operator: str, country: str) -> str:
    host_lower = hostname.lower()
    op_lower = operator.lower()

    if "ac.jp" in host_lower or "edu" in host_lower or "univ" in host_lower or "university" in op_lower:
        return "ACADEMIC"
    if "vpngate" in host_lower or "opengw" in host_lower or "home" in host_lower or "user" in host_lower:
        return "RESIDENTIAL"
    return "DATACENTER"


def fetch_and_sync_vpngate() -> int:
    logger.info("Phase 1: Fetching CSV list from VPNGate...")
    try:
        resp = requests.get(VPNGATE_API_URL, timeout=30)
        resp.raise_for_status()
    except Exception as e:
        logger.error(f"Failed to fetch VPNGate CSV: {e}")
        return 0

    lines = resp.text.splitlines()
    data_lines = [line for line in lines if not line.startswith("*")]

    csv_data = "\n".join([line for line in data_lines if line.strip()])
    reader = csv.reader(io.StringIO(csv_data))

    header = None
    count = 0

    for row in reader:
        if not row:
            continue
        if row[0].startswith("#HostName") or row[0] == "HostName":
            header = [h.strip("#") for h in row]
            continue

        if header and len(row) >= 15:
            try:
                hostname = row[0]
                ip = row[1]
                score = int(row[2]) if row[2].isdigit() else 0
                ping = int(row[3]) if row[3].isdigit() else 0
                speed = int(row[4]) if row[4].isdigit() else 0
                country_long = row[5]
                country_short = row[6]
                uptime = row[8]
                operator = row[12]
                ovpn_base64 = row[14]

                try:
                    ovpn_text = base64.b64decode(ovpn_base64).decode("utf-8", errors="ignore")
                except Exception:
                    ovpn_text = ""

                proto_match = re.search(r'^\s*proto\s+(\S+)', ovpn_text, re.MULTILINE | re.IGNORECASE)
                method = "UDP" if proto_match and proto_match.group(1).lower() == "udp" else "TCP"

                port = 1194 if method == "UDP" else 443
                remote_match = re.search(r'^\s*remote\s+\S+\s+(\d+)', ovpn_text, re.MULTILINE)
                if remote_match:
                    port = int(remote_match.group(1))

                server_type = classify_server_type(hostname, operator, country_short)

                server_data = {
                    "ip": ip,
                    "host_name": hostname,
                    "port": port,
                    "score": score,
                    "ping": ping,
                    "speed": speed,
                    "country_long": country_long,
                    "country_short": country_short,
                    "operator": operator,
                    "openvpn_config": ovpn_base64,
                    "server_type": server_type,
                    "uptime": uptime,
                    "method": method
                }

                upsert_scraped_server(server_data)
                count += 1
            except Exception as row_err:
                logger.warning(f"Skipping row parsing error: {row_err}")
                continue

    logger.info(f"Phase 1 complete: Scraped and upserted {count} servers into DB")
    return count


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    init_db()
    fetch_and_sync_vpngate()
