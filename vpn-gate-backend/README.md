# Zenith VPN Gate Backend

Backend API for Zenith VPN client. Scrapes, validates, and serves VPN Gate server lists.

## Stack

- Go + SQLite
- Swagger/OpenAPI documentation
- Docker support (dev + prod)

## Project Structure

```
cmd/server/         Entry point, HTTP handlers, middleware
internal/database/  SQLite schema, CRUD operations
internal/scraper/   VPN Gate API scraper
internal/validator/ Server probe (TCP/UDP/TLS/OpenVPN)
internal/vpncheck/  VPN detection (ip-api.com + vpnapi.io)
docs/               Swagger generated docs
```

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Server listen port |
| `DB_PATH` | `vpn.db` | SQLite database file path |
| `VPNAPI_KEY` | _(empty)_ | vpnapi.io API key (optional, for accurate VPN detection) |

## Run Locally

```bash
go run ./cmd/server
```

## Run with Air (hot-reload)

```bash
air
```

## Run with Docker

```bash
# Development (hot-reload, DB inside container)
docker compose -f docker-compose.dev.yml up --build

# Production (DB mounted to ./data/vpn.db)
docker compose -f docker-compose.prod.yml up --build -d
```

## API Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/servers` | Active servers (filter: `?type=`, `?country=`) |
| GET | `/api/servers/all` | All servers |
| GET | `/api/health` | Health check |
| GET | `/docs/` | Swagger UI |

## Scheduler

| Task | Interval | Description |
|------|----------|-------------|
| Scrape | 30 min | Fetch VPN Gate API → upsert DB |
| Probe | 10 min | 4-layer probe (TCP/UDP/TLS/OpenVPN) |
| Stale cleanup | 10 min | Mark inactive if probe fails for 4h+ |
| VPN detection | 10 min | Check unchecked IPs (ip-api.com batch + optional vpnapi.io) |

## VPN Detection

Two-stage pipeline, runs once per IP on first insert:

1. **ip-api.com** (free, batch) — flags datacenter/hosting IPs
2. **vpnapi.io** (optional, free 1k/day) — flags residential VPNs

Set `VPNAPI_KEY` env var to enable stage 2.

## Swagger

Generate docs:

```bash
swag init -d cmd/server
```
