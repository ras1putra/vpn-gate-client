# Zenith VPN

VPN client with backend API, stealth mode, and auto-failover.

## Project Structure

```
vpn-gate-backend/     Go API server (SQLite, Swagger)
vpngateclient/        Android client (Kotlin, Jetpack Compose)
.github/workflows/    CI/CD (GHCR image + APK build)
```

## Backend

Scrapes VPN Gate, validates servers, detects VPN/proxy IPs, serves via REST API.

```
cd vpn-gate-backend
cp .env.example .env
go run ./cmd/server
```

Docs: `http://localhost:8080/docs/`

## Android Client

Jetpack Compose, Retrofit, OpenVPN integration.

```
cd vpngateclient
./gradlew assembleDebug
```

APK: `vpngateclient/app/build/outputs/apk/debug/app-debug.apk`

## Docker

```bash
# Dev (hot-reload)
docker compose -f vpn-gate-backend/docker-compose.dev.yml up --build

# Prod (pre-built image from GHCR)
docker compose -f vpn-gate-backend/docker-compose.prod.yml up -d
```

## CI/CD

| Workflow | Trigger | Output |
|----------|---------|--------|
| `build-and-push.yml` | Manual | Docker image → GHCR |
| `build-apk.yml` | Manual | APK artifact |

## API

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/servers` | Active servers (filter: `?type=`, `?country=`) |
| GET | `/api/servers/ip/{ip}` | Server detail with config |
| GET | `/api/servers/all` | All servers |
| GET | `/api/health` | Health check |
| GET | `/docs/` | Swagger UI |

## Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `PORT` | `8080` | Server port |
| `DB_PATH` | `vpn.db` | SQLite path |
| `VPNAPI_KEY` | _(empty)_ | vpnapi.io key (optional) |
