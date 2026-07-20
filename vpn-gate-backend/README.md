# Zenith VPN Gate Backend (Python + FastAPI)

A 100% Pure Python backend built with **FastAPI**, **`uv`**, and **`curl_cffi`**. Scrapes VPN Gate servers, performs combined liveness & OpenVPN egress IP probing, and classifies servers into a **2-Tier Stealth System** optimized for gaming compatibility.

---

## ⚡ Features

- **FastAPI REST API**: High-performance asynchronous API with automatic Swagger UI documentation at `/docs`.
- **Egress IP Probing**: Captures actual exit NAT IPs (`exit_ip`) rather than relying on public entry IPs.
- **2-Tier Stealth Classification**:
  - **`is_stealth` (Standard Stealth)**: Passed Layer 1 (`ip-api.com` non-datacenter ISP) and Layer 2 (`vpnapi.io` risk check). Provides a large pool of usable servers for 90%+ of games.
  - **`is_advance_stealth` (Advanced Stealth)**: Passed Layer 3 (`nodedata.io` via `curl_cffi` TLS impersonation). For strict games with aggressive VPN detection.
- **`uv` Managed**: Uses Astral's `uv` package manager for fast, reliable Python dependencies.

---

## 🚀 Quick Start (Local Development)

### 1. Install `uv` and dependencies
```bash
# Install dependencies using uv
uv sync
```

### 2. Run API Server
```bash
uv run uvicorn app.main:app --reload --port 8080
```
Visit the interactive API docs at `http://localhost:8080/docs`.

---

## 🐳 Docker Environments

### Development (with Hot Reload & Local App Mounting)
```bash
docker-compose -f docker-compose.dev.yml up -d --build
```

### Production (Optimized Multi-Worker Deployment)
```bash
docker-compose -f docker-compose.prod.yml up -d --build
```

---

## 📡 API Endpoints Summary

| Method | Endpoint | Description |
| :--- | :--- | :--- |
| `GET` | `/api/servers` | Query active servers (`?type=STEALTH`, `?type=ADVANCE_STEALTH`, `?country=JP`) |
| `GET` | `/api/servers/all` | List all known servers |
| `GET` | `/api/servers/ip/{ip}` | Get full server detail including Base64 OpenVPN config |
| `GET` | `/api/health` | Health check endpoint |
