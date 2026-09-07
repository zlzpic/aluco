# Aluco IoT Device Monitoring Platform

> **Aluco** (Ural Owl) — open-source IoT device fleet monitoring platform.
> Devices report telemetry over MQTT; the platform ingests, stores, thresholds, and visualizes in real time.

```
Device → MQTT (EMQX) → aluco-server (Spring Boot) → MySQL + WebSocket → React Dashboard
```

## Table of Contents

1. [Architecture](#architecture)
2. [Quickstart](#quickstart)
3. [Port Reference](#port-reference)
4. [Default Accounts](#default-accounts)
5. [Verification Checklist](#verification-checklist)
6. [Known Limitations](#known-limitations)
7. [Development](#development)
8. [License](#license)

---

## Architecture

### Components

| Component | Tech | Role |
|---|---|---|
| **aluco-server** | Spring Boot 3.4 / Java 21 | REST API · WebSocket · MQTT ingestion · alerting engine |
| **aluco-sim** | Java CLI (Paho + picocli) | Device fleet simulator |
| **aluco-web** | React 18 + TypeScript + Vite 5 | Dashboard UI |
| **EMQX** | 5.8.x | MQTT broker |
| **MySQL** | 8.4 | Primary datastore |
| **Redis** | 7.x | *(Phase 2 — optional)* Device state cache |
| **Kafka** | 3.9 KRaft | *(Phase 2 — optional)* Telemetry edge buffer |
| **TimescaleDB** | 2.17 on PG 16 | *(Phase 2 — optional)* Time-series storage |
| **Prometheus** | v2.55 | Metrics scraping |
| **Grafana** | 11.x | Observability dashboards |

### Data Flow

```
aluco-sim ──MQTT──▶ EMQX ──MQTT──▶ aluco-server
                                          │
                                  ┌────────┴──────────┐
                                  │                   │
                           Cold Path            Hot Path
                          (batch write)        (state + push)
                                  │                   │
                              MySQL ───────────── WebSocket ──▶ aluco-web
                            (telemetry/
                         device_state/
                         alert_event)
                                  │
                           Prometheus ──▶ Grafana
```

### Package Structure (server)

```
com.aluco.server
├── alerting/      # Rule engine · AlertEvent/Rule services
├── api/           # REST controllers · Auth · Exception handling
├── device/        # Device CRUD · StateStore · OfflineDetection
├── ingestion/     # MQTT client · Command publisher
├── processing/    # Telemetry processor · Sink · TimeSeriesStore
├── push/          # WebSocket handler · LivePush
└── common/        # Shared DTOs · EnvelopeCodec · JwtService
```

---

## Quickstart

### Prerequisites

- Docker Engine 20.10+ and Docker Compose v2
- **OR** WSL2 Ubuntu 22.04 with native MySQL, EMQX, and JDK 21 (see [Development](#development))

### v1 Quickstart (Baseline)

```bash
# Start core services (MySQL, EMQX, server, web, Prometheus, Grafana)
docker compose up -d
# Wait ~30s for health checks, then open http://localhost
```

### v2 Phase 1 — Command ACK & EMQX Auth (Optional)

```bash
# Enable EMQX authentication (disabled by default in compose)
docker compose up -d
docker compose exec -e EMQX_ALLOW_ANONYMOUS=false emqx ...

# Or run server with explicit auth profile
java -jar aluco-server.jar --aluco.mqtt.auth=on
```

**v2 Phase 1 Features:**
- ✅ **Command ACK**: Devices reply on `cmdack` topic; server tracks SENT → ACKED / FAILED / TIMEOUT
- ✅ **EMQX Auth**: `deviceKey` as username, `token` as password; server syncs credentials via EMQX REST API
- ✅ **Token Rotation**: `POST /devices/{key}/token/rotate` invalidates old token immediately
- ✅ **Command Timeout**: 30s timeout monitoring (configurable via `ALUCO_COMMAND_TIMEOUT`)

### v2 Phase 2 — Kafka / Redis / TimescaleDB (Optional, Off by Default)

```bash
# Enable Kafka sink (requires Kafka 3.9 KRaft running)
java -jar aluco-server.jar --aluco.sink.type=kafka --aluco.kafka.bootstrap=localhost:9092

# Enable Redis state store (requires Redis 7 running)
java -jar aluco-server.jar --aluco.store.state=redis --aluco.redis.url=redis://localhost:6379

# Enable TimescaleDB time-series (requires PG 16 + TimescaleDB 2.17)
java -jar aluco-server.jar --aluco.store.timeseries=timescale --aluco.timescale.jdbc-url=jdbc:postgresql://localhost:5432/aluco
```

> ⚠️ **Phase 2 is opt-in.** Default configuration uses in-memory sink + MySQL. See [docs/adr/](docs/adr/) for migration rationale and [docs/benchmarks/](docs/benchmarks/) for load test data.

### Run the Simulator

```bash
# Start 200 simulated devices (demo profile, auto-seeds via REST)
docker compose --profile demo up -d sim

# Check simulator logs
docker compose logs -f sim

# Stop simulator
docker compose stop sim
```

### First Login

1. Open **http://localhost**
2. Login with `admin` / `admin123`
3. Go to **Devices** — you should see ~200 devices registered by the simulator
4. Go to **Rules** → **Create Rule**: `temp GT 30` (all devices)
5. Watch **Alerts** page — spike events appear as simulator injects temperature spikes

---

## Port Reference

| Port | Service | Purpose |
|---|---|---|
| `80` | web (nginx) | Dashboard |
| `8080` | aluco-server | REST API · WebSocket |
| `3306` | MySQL 8.4 | Primary database |
| `1883` | EMQX | MQTT (devices) |
| `18083` | EMQX Dashboard | Broker management UI |
| `9090` | Prometheus | Metrics |
| `3000` | Grafana | Dashboards |

---

## Default Accounts

| System | Username | Password | Notes |
|---|---|---|---|
| Aluco web | `admin` | `admin123` | Seed data; change in production |
| EMQX Dashboard | `admin` | `public` | Docker image default; change immediately |
| Grafana | `admin` | `admin` | Prompted to change on first login |

> ⚠️ **All credentials above are for local development only. Never use in production.**

---

## Verification Checklist

After `docker compose up -d`, verify:

- [ ] **Web loads** at http://localhost — login succeeds
- [ ] **200 devices** appear in device list (simulator auto-seeded)
- [ ] **Real-time chart** scrolls on device detail page
- [ ] **Rule `temp GT 30`** fires FIRING alerts within 90s of spike injection
- [ ] **Alert ACK** changes status to ACKED in the alert list
- [ ] **Set interval** command shows SENT → ACKED transition
- [ ] **Device goes offline** after 60s without telemetry (check presence badge)
- [ ] **Grafana Aluco dashboard** shows `aluco.ingest.received > 0` and `aluco.e2e.latency` histogram

---

## Known Limitations

| Limitation | Rationale | Planned Resolution |
|---|---|---|
| EMQX authentication optional | v1 simplified auth; app-layer validates device_key | v2: EMQX built-in auth + ACL enabled by default |
| Command ACK (v1) | Simulator logs receipt only | **v2 ✅**: `cmdack` topic + command table + timeout monitoring |
| Single-server deployment | v1 targets modular monolith | v3+: microservice split (architecture evolved) |
| MySQL only (default) | Day-1 naive design | **v2 ✅**: TimescaleDB migration path available (off by default) |
| telemetry narrow table (no partitioning) | Capacity ceiling TBD by load test | v2: measured threshold triggers partitioning ADR |
| Kafka / Redis (optional) | v1 baseline uses in-memory sink | **v2 ✅**: Pluggable TelemetrySink (Kafka) and StateStore (Redis) — disabled by default, enable via config |

---

## Development

### Build from Source

```bash
# Build server
cd aluco-server
./mvnw clean package -DskipTests

# Build simulator
cd aluco-sim
mvn clean package

# Build web
cd aluco-web
npm install && npm run build
```

### Run Tests

```bash
cd aluco-server
./mvnw test           # Unit tests + ArchUnit
```

### Environment Variables

All sensitive values are configurable via env vars; defaults target localhost.

| Variable | Default | Purpose |
|---|---|---|
| `ALUCO_DB_URL` | `jdbc:mysql://localhost:3306/aluco` | MySQL JDBC URL |
| `ALUCO_DB_USER` | `root` | DB username |
| `ALUCO_DB_PASSWORD` | `root` | DB password |
| `ALUCO_MQTT_BROKER` | `tcp://localhost:1883` | EMQX address |
| `ALUCO_JWT_SECRET` | *(dev placeholder)* | HMAC key — **override in prod** |
| `ALUCO_EMQX_API_USER` | `admin` | EMQX REST API user (for credential sync) |
| `ALUCO_EMQX_API_PASSWORD` | `public` | EMQX REST API password |
| `ALUCO_MQTT_AUTH` | `on` | `on` = require device token; `off` = insecure dev profile |
| `ALUCO_SINK_TYPE` | `memory` | `memory` / `kafka` — telemetry sink backend (Phase 2) |
| `ALUCO_STORE_STATE` | `mysql` | `mysql` / `redis` — device state backend (Phase 2) |
| `ALUCO_STORE_TIMESERIES` | `mysql` | `mysql` / `timescale` — time-series backend (Phase 2) |
| `ALUCO_KAFKA_BOOTSTRAP` | `localhost:9092` | Kafka bootstrap servers (Phase 2) |
| `ALUCO_REDIS_URL` | `redis://localhost:6379` | Redis connection URL (Phase 2) |
| `ALUCO_TIMESCALE_JDBC_URL` | *(unset)* | TimescaleDB JDBC URL (Phase 2) |

### Simulator CLI

```bash
java -jar aluco-sim-1.0.0.jar [options]

# Common options
--devices 1000                 # 1000 simulated devices
--interval 1s                  # Report every 1 second per device
--ramp 60s                     # Stagger start over 60 seconds
--metrics temp,humidity        # Metrics to simulate
--spike-probability 0.01       # 1% chance of spike per reading
--auth                         # Use device token as MQTT password (v2 default)
--ack-drop-probability 0       # v2: 0~1, probability to drop cmdack (demo TIMEOUT path)
--api http://localhost:8080/api/v1   # Server REST for auto-seeding
--username admin --password admin123 # Login credentials for seeding
```

> **v2 Changes:** `--auth` is now `true` by default. Simulator connects with `deviceKey` as username and `token` as password.

---

## Observability

### Prometheus Metrics

| Metric | Type | Description |
|---|---|---|
| `aluco.ingest.received` | Counter | Raw MQTT messages (tags: `result=ok\|dropped`) |
| `aluco.sink.dropped` | Counter | Queue overflow drops |
| `aluco.e2e.latency` | Timer (histogram) | Envelope ts → processing complete |
| `aluco.persist.batch.size` | DistributionSummary | Batch insert sizes |
| `aluco.rule.eval` | Timer | Per-rule evaluation latency |
| `aluco.ws.sessions` | Gauge | Active WebSocket sessions |
| `aluco.alerts.firing` | Gauge | Currently FIRING alert events |
| `aluco.kafka.lag` | Gauge | **v2** Kafka consumer lag (max) |
| `aluco.emqx.sync.failures` | Counter | **v2** EMQX credential sync failures |
| `aluco.command.timeout` | Counter | **v2** Commands timed out waiting for ACK |
| `aluco.offline.flips` | Counter | **v2** Devices flipped from online → offline |

### Grafana Dashboards

Pre-provisioned via `deploy/grafana/provisioning/`:
- **Aluco Overview** — device counts, ingestion rate, alert summary
- **Aluco JVM** — server JVM metrics (heap, GC, threads)

---

## API Reference

### Base URL

```
http://localhost:8080/api/v1
```

### Authentication

All `/api/**` endpoints (except `/auth/login`) require a JWT Bearer token:

```
Authorization: Bearer <token>
```

**Login**

```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

**Response**

```json
{ "token": "<JWT>", "expiresAt": 1753000000000 }
```

### Key Endpoints

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/auth/login` | Login |
| `GET` | `/devices` | List devices (paginated) |
| `POST` | `/devices` | Create device (returns token) |
| `GET` | `/devices/{key}` | Device detail |
| `DELETE` | `/devices/{key}` | Delete device |
| `GET` | `/devices/{key}/state` | Latest state |
| `GET` | `/devices/{key}/telemetry` | Historical data (down-sampled) |
| `POST` | `/devices/{key}/commands/set-interval` | Downlink command (returns `cmdId`) |
| `GET` | `/commands/{cmdId}` | **v2** Command detail by cmdId |
| `GET` | `/devices/{key}/commands` | **v2** Device command history (paginated) |
| `POST` | `/devices/{key}/token/rotate` | **v2** Rotate device MQTT token (old token invalidated immediately) |
| `GET` | `/rules` | List rules |
| `POST` | `/rules` | Create rule |
| `PATCH` | `/rules/{id}/enabled` | Toggle rule |
| `DELETE` | `/rules/{id}` | Delete rule |
| `GET` | `/alerts` | List alert events |
| `POST` | `/alerts/{id}/ack` | Acknowledge alert |
| `GET` | `/actuator/health` | Health check |
| `GET` | `/actuator/prometheus` | Prometheus scrape |

### Telemetry Query

```bash
curl "http://localhost:8080/api/v1/devices/TH-0001/telemetry?metric=temp&from=1752739200000&to=1752825600000&interval=1m"
```

Response:

```json
{
  "metric": "temp",
  "points": [{ "ts": 1752739200000, "val": 22.5 }],
  "truncated": false
}
```

`interval` values: `raw` (≤10,000 pts) · `1m` · `5m` · `1h` · `1d`

### WebSocket

Connect at `ws://localhost:8080/ws/live?token=<JWT>`

**Client → Server**

```json
{ "type": "subscribe",   "deviceIds": ["TH-0001"] }
{ "type": "unsubscribe", "deviceIds": ["TH-0001"] }
```

**Server → Client**

```json
{ "type": "telemetry", "deviceId": "TH-0001", "ts": 1752739200000, "metrics": { "temp": 35.2 } }
{ "type": "alert", "event": { "id": 101, "ruleName": "高温告警", "deviceId": "TH-0001", "status": "FIRING", "value": 35.2, "triggeredAt": 1752739200000 } }
{ "type": "presence", "deviceId": "TH-0001", "online": false }
```

---

## MQTT Topics

| Topic | Direction | QoS | Purpose |
|---|---|---|---|
| `aluco/{siteId}/{deviceKey}/telemetry` | Device → Server | 1 | Telemetry report |
| `aluco/{siteId}/{deviceKey}/cmd` | Server → Device | 1 | Downlink command |
| `aluco/{siteId}/{deviceKey}/cmdack` | Device → Server | 1 | **v2** Command ACK (ADR-0006) |

### Command ACK (v2)

```json
{ "v": 1, "cmdId": "uuid", "deviceId": "TH-0001", "ts": 1752739200000,
  "status": "ACKED", "message": "interval=5s applied" }
```
`status`: `ACKED` / `FAILED`. Server monitors ACK with 30s timeout.

### Telemetry Envelope

```json
{
  "v": 1,
  "deviceId": "TH-0001",
  "ts": 1752739200000,
  "seq": 42,
  "metrics": { "temp": 35.2, "humidity": 61.0 }
}
```

---

## License

Apache-2.0 — see [LICENSE](LICENSE).
