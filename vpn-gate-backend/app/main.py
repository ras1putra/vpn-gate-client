import threading
import logging
from typing import Optional, List, Dict, Any
from fastapi import FastAPI, HTTPException, Query, Path, Response, status
from fastapi.middleware.cors import CORSMiddleware
from datetime import datetime

from app.database import (
    init_db,
    get_active_servers,
    get_all_servers,
    get_server_by_ip,
    get_db_connection
)
from app.scheduler import start_scheduler_loop

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s"
)

app = FastAPI(
    title="Zenith VPN Gate API",
    version="1.0.0",
    description="Zenith VPN Gate API."
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["GET", "OPTIONS"],
    allow_headers=["*"],
)


@app.on_event("startup")
def on_startup():
    init_db()
    # Launch pipeline scheduler in daemon background thread
    t = threading.Thread(target=start_scheduler_loop, kwargs={"interval_minutes": 15}, daemon=True)
    t.start()
    logging.info("FastAPI server started & background pipeline scheduler initialized.")


def format_server_response(s: Dict[str, Any], include_config: bool = False) -> Dict[str, Any]:
    return {
        "hostName": s["host_name"],
        "ip": s["ip"],
        "exitIp": s.get("exit_ip", ""),
        "port": s["port"],
        "score": s["score"],
        "ping": s["ping"],
        "speed": s["speed"],
        "countryLong": s["country_long"],
        "countryShort": s["country_short"],
        "operator": s["operator"],
        "openVpnConfigBase64": s["openvpn_config"] if include_config else "",
        "serverType": s["server_type"],
        "isStealth": bool(s.get("is_stealth", 0)),
        "isAdvanceStealth": bool(s.get("is_advance_stealth", 0)),
        "vpngateFlagged": bool(s["vpngate_flagged"]) if s.get("vpngate_flagged") is not None else None,
        "uptime": s["uptime"],
        "method": s["method"],
        "isActive": bool(s["is_active"]) if s["is_active"] is not None else None,
        "lastSeen": s["last_seen"],
        "lastScraped": s["last_scraped"],
        "source": s["source"],
        "isp": s.get("isp", ""),
        "as": s.get("as_info", ""),
        "hosting": bool(s.get("hosting", 0)),
        "proxy": bool(s.get("proxy", 0))
    }


@app.get("/api/servers", summary="List active VPN servers")
def list_active_servers(
    type: Optional[str] = Query(None, description="Filter: STEALTH, ADVANCE_STEALTH, RESIDENTIAL, ACADEMIC, DATACENTER"),
    country: Optional[str] = Query(None, description="Country 2-letter short code (e.g. JP, US, KR)")
):
    stealth_tier = None
    server_type = None

    if type:
        type_upper = type.upper()
        if type_upper in ["STEALTH", "ADVANCE_STEALTH"]:
            stealth_tier = type_upper
        elif type_upper in ["RESIDENTIAL", "ACADEMIC", "DATACENTER"]:
            server_type = type_upper
        else:
            raise HTTPException(status_code=400, detail="Invalid server type query parameter")

    servers = get_active_servers(server_type=server_type, country_short=country, stealth_tier=stealth_tier)
    return [format_server_response(s, include_config=False) for s in servers]


@app.get("/api/servers/all", summary="List all known VPN servers")
def list_all_servers():
    servers = get_all_servers()
    return [format_server_response(s, include_config=False) for s in servers]


@app.get("/api/servers/ip/{ip}", summary="Get full server detail including OpenVPN config")
def get_server(ip: str = Path(..., description="Server IP address")):
    server = get_server_by_ip(ip)
    if not server:
        raise HTTPException(status_code=404, detail="Server not found")
    return format_server_response(server, include_config=True)


@app.get("/api/health", summary="Health check endpoint")
def health_check():
    db_ok = True
    try:
        conn = get_db_connection()
        conn.execute("SELECT 1")
        conn.close()
    except Exception:
        db_ok = False

    if not db_ok:
        raise HTTPException(status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail="Database ping failed")

    return {
        "status": "healthy",
        "db": db_ok,
        "time": datetime.utcnow().isoformat()
    }
