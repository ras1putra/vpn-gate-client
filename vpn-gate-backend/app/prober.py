import socket
import logging
import subprocess
import os
import time
import tempfile
import base64
import threading
from typing import Optional
from app.database import get_db_connection, update_probed_liveness

logger = logging.getLogger(__name__)

prober_lock = threading.Lock()


def probe_socket_liveness(ip: str, port: int, method: str, timeout: float = 3.0) -> bool:
    sock_type = socket.SOCK_DGRAM if method.upper() == "UDP" else socket.SOCK_STREAM
    sock = None
    try:
        sock = socket.socket(socket.AF_INET, sock_type)
        sock.settimeout(timeout)
        if method.upper() == "TCP":
            sock.connect((ip, port))
        else:
            sock.sendto(b"\x38\x00\x00\x00\x00\x00\x00\x00", (ip, port))
        return True
    except Exception:
        return False
    finally:
        if sock:
            sock.close()


def probe_openvpn_exit_ip(ip: str, ovpn_base64: str, timeout_sec: int = 20) -> Optional[str]:
    import re

    try:
        subprocess.run(["openvpn", "--version"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    except Exception:
        logger.debug("OpenVPN CLI binary not found on host system, skipping tunnel egress probe")
        return None

    try:
        config_text = base64.b64decode(ovpn_base64).decode("utf-8", errors="ignore")
    except Exception as e:
        logger.error(f"Failed to decode OpenVPN config for {ip}: {e}")
        return None

    ovpn_path = None
    auth_path = None
    proc = None
    exit_ip = None

    try:
        with tempfile.NamedTemporaryFile(mode="w", suffix=".ovpn", delete=False) as f:
            f.write(config_text)
            ovpn_path = f.name

        with tempfile.NamedTemporaryFile(mode="w", suffix=".txt", delete=False) as f:
            f.write("vpn\nvpn\n")
            auth_path = f.name

        cmd = [
            "openvpn", "--config", ovpn_path,
            "--data-ciphers", "AES-256-GCM:AES-128-GCM:AES-128-CBC",
            "--data-ciphers-fallback", "AES-128-CBC",
            "--auth-user-pass", auth_path,
            "--verb", "1"
        ]
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)

        start = time.time()
        while time.time() - start < timeout_sec:
            if proc.poll() is not None:
                break
            time.sleep(2)
            try:
                res = subprocess.run(
                    ["curl", "--interface", "tun0", "-s", "--connect-timeout", "3", "https://api.ipify.org"],
                    capture_output=True,
                    text=True
                )
                out = res.stdout.strip()
                if res.returncode == 0 and out and re.match(r"^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$", out):
                    exit_ip = out
                    break
            except Exception:
                pass
    except Exception as e:
        logger.error(f"OpenVPN probe exception for {ip}: {e}")
    finally:
        if proc and proc.poll() is None:
            proc.terminate()
            try:
                proc.wait(timeout=3)
            except Exception:
                proc.kill()
        for p in [ovpn_path, auth_path]:
            if p and os.path.exists(p):
                os.remove(p)

    return exit_ip


def probe_all_unverified_servers(batch_limit: int = 100):
    if not prober_lock.acquire(blocking=False):
        logger.info("Phase 2: Prober is already running, skipping overlapping execution.")
        return

    try:
        logger.info("Phase 2: Starting combined Liveness & Egress IP Probing...")
        conn = get_db_connection()
        cursor = conn.cursor()

        query = """
        SELECT ip, port, method, openvpn_config
        FROM servers
        WHERE is_active IS NULL
           OR (is_active = 1 AND last_seen < datetime('now', '-1 hour'))
           OR (is_active = 0 AND last_seen < datetime('now', '-6 hours'))
        ORDER BY 
            CASE 
                WHEN is_active IS NULL THEN 1
                WHEN is_active = 1 THEN 2
                ELSE 3
            END ASC,
            last_scraped DESC
        LIMIT ?
        """

        cursor.execute(query, (batch_limit,))
        rows = cursor.fetchall()
        conn.close()

        if not rows:
            logger.info("Phase 2: No unverified servers pending probing.")
            return

        logger.info(f"Phase 2: Probing {len(rows)} unverified servers...")

        active_count = 0
        for row in rows:
            ip = row["ip"]
            port = row["port"]
            method = row["method"]
            ovpn_base64 = row["openvpn_config"]

            # Step 1: Fast socket liveness gate
            is_alive = probe_socket_liveness(ip, port, method)
            if not is_alive:
                update_probed_liveness(ip, is_active=False, exit_ip="")
                continue

            exit_ip = probe_openvpn_exit_ip(ip, ovpn_base64) or ""

            update_probed_liveness(ip, is_active=True, exit_ip=exit_ip)
            active_count += 1

        logger.info(f"Phase 2 complete: Verified {active_count}/{len(rows)} active servers.")
    finally:
        prober_lock.release()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    probe_all_unverified_servers()
