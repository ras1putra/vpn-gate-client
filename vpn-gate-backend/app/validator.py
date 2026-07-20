import os
import re
import time
import logging
import requests
import threading
from curl_cffi import requests as cffi_requests
from typing import Dict, Any, Optional, Tuple
from app.database import get_db_connection, update_security_classification

logger = logging.getLogger(__name__)

validator_lock = threading.Lock()

IP_API_BATCH_URL = "http://ip-api.com/batch?fields=status,query,isp,as,hosting,proxy"
VPNAPI_URL = "https://vpnapi.io/api/"
NODEDATA_URL = "https://nodedata.io/vpn-detection-test"
NODEDATA_API = "https://nodedata.io/demo/api"

VPNAPI_KEY = os.getenv("VPNAPI_KEY", "")


def check_ip_api_batch(ips: list) -> Dict[str, Dict[str, Any]]:
    if not ips:
        return {}

    results = {}
    payload = [{"query": ip} for ip in ips]

    try:
        resp = requests.post(IP_API_BATCH_URL, json=payload, timeout=20)
        if resp.status_code == 200:
            data = resp.json()
            for item in data:
                if item.get("status") == "success":
                    ip = item.get("query")
                    results[ip] = {
                        "isp": item.get("isp", ""),
                        "as_info": item.get("as", ""),
                        "hosting": bool(item.get("hosting", False)),
                        "proxy": bool(item.get("proxy", False))
                    }
    except Exception as e:
        logger.error(f"ip-api.com batch query failed: {e}")

    return results


def check_vpnapi(ip: str) -> bool:
    if not VPNAPI_KEY:
        return True  # Pass if key not configured

    try:
        resp = requests.get(f"{VPNAPI_URL}{ip}?key={VPNAPI_KEY}", timeout=10)
        if resp.status_code == 200:
            data = resp.json()
            security = data.get("security", {})
            return not (security.get("vpn") or security.get("proxy") or security.get("tor"))
    except Exception as e:
        logger.warning(f"vpnapi.io check failed for {ip}: {e}")

    return True


def check_nodedata_curl_cffi(ip: str) -> Optional[bool]:
    logger.info(f"[Layer 3 NodeData] Checking exit IP: {ip}")
    try:
        session = cffi_requests.Session(impersonate="chrome120")
        html_resp = session.get(NODEDATA_URL, timeout=15)
        match = re.search(r'token:"([^"]+)"', html_resp.text)
        if not match:
            logger.warning(f"[Layer 3 NodeData] Could not extract token from {NODEDATA_URL}")
            return None

        token = match.group(1)

        api_resp = session.post(NODEDATA_API, data={"value": ip, "token": token}, timeout=15)
        if api_resp.status_code == 200:
            res_data = api_resp.json()
            if "error" in res_data:
                logger.warning(f"[Layer 3 NodeData] Error returned for {ip}: {res_data.get('error')}")
                return None
            security = res_data.get("security", {})
            is_vpn = bool(security.get("vpn", False))
            is_proxy = bool(security.get("proxy", False))
            provider = security.get("provider", "")

            flagged = is_vpn or is_proxy
            logger.info(
                f"[Layer 3 NodeData] {ip} -> vpn={is_vpn}, proxy={is_proxy}, provider='{provider}' => Flagged={flagged}"
            )
            return flagged
        else:
            logger.warning(f"[Layer 3 NodeData] HTTP {api_resp.status_code} for {ip}")
    except Exception as e:
        logger.warning(f"[Layer 3 NodeData] Check exception for {ip}: {e}")

    return None


def run_3_layered_security_check(limit: int = 50):
    if not validator_lock.acquire(blocking=False):
        logger.info("Phase 3: Security validator is already running, skipping overlapping execution.")
        return

    try:
        logger.info("Phase 3: Running 3-Layered Security Evaluation on active servers...")
        conn = get_db_connection()
        cursor = conn.cursor()

        cursor.execute("""
        SELECT ip, exit_ip FROM servers
        WHERE is_active = 1 AND (vpngate_flagged IS NULL OR is_stealth = 0 OR is_advance_stealth = 0)
        LIMIT ?
        """, (limit,))

        rows = cursor.fetchall()
        conn.close()

        if not rows:
            logger.info("Phase 3: No servers pending security classification.")
            return

        # Map target IP -> exit_ip
        eval_map = {}
        for row in rows:
            target_ip = row["exit_ip"] if row["exit_ip"] else row["ip"]
            eval_map[row["ip"]] = target_ip

        target_ips = list(set(eval_map.values()))

        # Layer 1: ip-api.com Batch (up to 50 IPs in 1 single POST request)
        logger.info(f"[Layer 1 ip-api] Querying batch for {len(target_ips)} exit IPs...")
        layer1_results = check_ip_api_batch(target_ips)

        for entry_ip, exit_ip in eval_map.items():
            l1_data = layer1_results.get(exit_ip, {})
            isp = l1_data.get("isp", "")
            as_info = l1_data.get("as_info", "")
            hosting = l1_data.get("hosting", False)
            proxy = l1_data.get("proxy", False)

            # Layer 1 Check: non-datacenter & non-proxy
            l1_pass = (not hosting) and (not proxy)
            logger.info(f"[Layer 1 ip-api] {exit_ip} -> hosting={hosting}, proxy={proxy}, isp='{isp}' => Pass={l1_pass}")

            # Layer 2 Check: vpnapi.io (if configured)
            l2_pass = check_vpnapi(exit_ip) if l1_pass else False
            logger.info(f"[Layer 2 vpnapi] {exit_ip} -> Pass={l2_pass}")

            is_stealth = l1_pass and l2_pass

            # Layer 3 Check: nodedata.io via curl_cffi (Throttled with 1.5s delay)
            is_advance_stealth = False
            vpngate_flagged = None

            if is_stealth:
                flagged = check_nodedata_curl_cffi(exit_ip)
                vpngate_flagged = flagged
                if flagged is False:
                    is_advance_stealth = True
                time.sleep(1.5)  # Throttling delay for nodedata.io anti-bot rate limits

            logger.info(
                f"[Classification Final] Entry: {entry_ip} | Exit: {exit_ip} => "
                f"is_stealth={is_stealth}, is_advance_stealth={is_advance_stealth}, vpngate_flagged={vpngate_flagged}"
            )

            update_security_classification(
                ip=entry_ip,
                isp=isp,
                as_info=as_info,
                hosting=hosting,
                proxy=proxy,
                vpngate_flagged=vpngate_flagged,
                is_stealth=is_stealth,
                is_advance_stealth=is_advance_stealth
            )

        logger.info("Phase 3 complete: Security evaluation updated.")
    finally:
        validator_lock.release()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    run_3_layered_security_check()
