# Aluco IoT Device Monitoring Platform

[![CI](https://github.com/zlzpic/aluco/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/zlzpic/aluco/actions/workflows/ci.yml)
![License](https://img.shields.io/badge/license-Apache--2.0-blue)

> **Aluco** (Ural Owl) — open-source IoT device fleet monitoring platform.
> Devices report telemetry over MQTT; the platform ingests, stores, thresholds, and visualizes in real time.

```
Device → MQTT (EMQX) → aluco-server (Spring Boot) → MySQL + WebSocket → React Dashboard
```

## Table of Contents

1. [Architecture](#architecture)
2. [Why these choices (trade-offs)](#why-these-choices-trade-offs)
3. [Quickstart](#quickstart)
4. [Port Reference](#port-reference)
5. [Default Accounts](#default-accounts)
6. [Verification Checklist](#verification-checklist)
7. [Known Limitations](#known-limitations)
8. [Development](#development)
9. [License](#license)

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

```mermaid
graph LR
    SIM[aluco-sim<br/>200 devices] -->|MQTT telemetry| EMQX[(EMQX broker)]
    EMQX -->|subscribe| ING[Ingestion<br/>MqttIngestor]
    ING --> PROC[TelemetryProcessor<br/>validate + count]
    PROC --> SINK{TelemetrySink<br/>memory | kafka}
    SINK --> FAN[consumer fan-out]
    FAN --> COLD[TimeSeriesStore<br/>mysql | timescale]
    FAN --> HOT[StateStore<br/>mysql | redis]
    HOT --> PUSH[LivePush<br/>WebSocket]
    FAN --> ALERT[AlertingEngine<br/>threshold rules]
    ALERT --> PUSH
    COLD --> DB[(MySQL / TimescaleDB)]
    HOT --> DB
    PUSH --> WEB[React Dashboard]
    ING -->|cmd| EMQX
    EMQX -->|cmdack| ING
    PROM[Prometheus] -.scrape.-> ING
    GRAF[Grafana] -.-> PROM
```

<details>
<summary>ASCII version</summary>

```
aluco-sim ──MQTT──▶ EMQX ◀──telemetry / cmdack──▶ aluco-server ──cmd──▶ EMQX
                                                    │
                       consumer fan-out             │
              ┌───────────────────┬─────────────────┴───────┐
              ▼                   ▼                         ▼
        TimeSeriesStore      StateStore               AlertingEngine
       (mysql|timescale)    (mysql|redis)             (rule state machine)
              │                   │                         │
              └──────▶ MySQL ◀────                         │
                     (telemetry · device_state · alert_event)│
                                                    WebSocket ─▶ aluco-web
              Prometheus ◀──scrape── aluco-server ──▶ Grafana
```

</details>

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

## Why these choices (trade-offs)

A personal project has to spend its complexity budget where the learning signal is highest.
Every choice below is deliberate — including what it costs us. Details live in `docs/adr/`.

| Decision | Why | What we give up | ADR |
|---|---|---|---|
| MQTT + EMQX instead of a self-built broker or raw TCP | De-facto IoT standard: tiny frames for constrained devices, offline sessions, LWT, million-connection scaling; broker absorbs connection lifecycle so the app server stays stateless about devices | One more moving part in the stack; auth semantics delegated to broker config | ADR-0001 |
| **Modular monolith**, boundaries enforced by ArchUnit | One maintainer; microservices' operational tax is unsupportable. Packages (`ingestion/ processing/ device/ push/ api/`) have enforced dependency direction, so a later split is a refactor, not a rewrite | No independent scaling of hot paths; blast radius of a bad deploy is the whole app | ADR-0002 |
| **Three storage seams**, all opt-in (`TelemetrySink`, `StateStore`, `TimeSeriesStore`) | Day-1 runs entirely on MySQL; Redis / Kafka / TimescaleDB each replace exactly one seam and are switched by a single property. Evolution is benchmark-gated, not fashion-gated | Indirection the v1 demo never needs; every seam is two implementations to test | ADR-0009/0010/0011 |
| Redis offline detection via **ZSET score sweep**, not keyspace expiration | Expiration events are best-effort in Redis (dropped under load) — a monitoring platform must not miss state changes; `ZRANGEBYSCORE` over `aluco:last_seen` is deterministic and O(hits) | Up to 15 s detection latency (sweep interval) vs near-instant expiry | ADR-0010 |
| TimescaleDB for telemetry, **MySQL stays the system of record** for metadata | Telemetry is append-mostly and time-windowed: hypertable chunking, continuous aggregates behind `?interval=`, 7-day compression are exactly the workload. Metadata is small, relational, transactional — MySQL is fine and already deployed | A second datasource + second Flyway history; dual-engine ops | ADR-0011 |
| Kafka as **edge buffer only when the wall is hit** | `acks=all` + idempotent producer + DLQ gives replayable ingest, but only pays off past a measured write-rate ceiling; default in-memory sink is bounded and zero-dependency | A second code path (Kafka consumer) that only the benchmark regime exercises | ADR-0009 |
| Naive narrow `telemetry` table in v1, **no partitioning** | "Most reasonable day-one design": measure the real ceiling first, let the load test justify the migration instead of pre-optimizing | Known throughput ceiling; documented and deliberately accepted | ADR-0004 |
| React 18 + ECharts, no SSR | Dashboard is a post-login SPA; ECharts handles streaming time-series out of the box; Vite keeps the build boring | SEO and first-paint server rendering — irrelevant behind a login | — |

---

## Quickstart

### Prerequisites

- Docker Engine 20.10+ and Docker Compose v2
- **OR** WSL2 Ubuntu 22.04 with native MySQL, EMQX, and JDK 21 (see [Development](#development))

### One-Command Demo

```bash
cd deploy
docker compose --profile demo up -d --build   # MySQL, EMQX, server, web, Prometheus, Grafana + 200 simulated devices
# API is reachable ~20s after the containers start (measured, warm images); then open http://localhost
```

> First run pulls ~1.5 GB of base images and builds the three modules (Maven/npm run inside
> containers). The bundled MySQL publishes `127.0.0.1:3307` on the host on purpose, so the stack
> starts cleanly next to a locally installed MySQL; containers still reach it at `mysql:3306`.

### v2 Phase 1 — Command ACK & EMQX Auth

The demo runs with anonymous MQTT by default. To switch the broker to credential mode:

```bash
EMQX_ALLOW_ANONYMOUS=false docker compose up -d   # from deploy/
```

The server then keeps EMQX's built-in credential store in sync with the device table
(`deviceKey` as username, `token` as password) on register / rotate / delete — see
`EmqxAuthService`; ADR-0003 records the original v1 anonymous decision. Note: `aluco-sim`
connects anonymously today, so the simulator only drives an anonymous broker
(tracked under Known Limitations).

**v2 Phase 1 Features:**
- ✅ **Command ACK**: Devices reply on `cmdack` topic; server tracks SENT → ACKED / FAILED / TIMEOUT
- ✅ **EMQX Auth**: `deviceKey` as username, `token` as password; server syncs credentials via EMQX REST API
- ✅ **Token Rotation**: `POST /devices/{key}/token/rotate` invalidates old token immediately
- ✅ **Command Timeout**: 30s timeout monitoring (configurable via `ALUCO_COMMAND_TIMEOUT`)

### v2 Phase 2 — Kafka / Redis / TimescaleDB (Optional, Off by Default)

```bash
# Enable Kafka sink (requires Kafka 3.9 KRaft running)
java -jar aluco-server.jar --aluco.sink.type=kafka --aluco.kafka.bootstrap-servers=localhost:9092

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
| `3307` (127.0.0.1 only) | MySQL 8.4 | Primary database (host debugging; containers use `mysql:3306`) |
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

After `docker compose --profile demo up -d --build`, verify:

- [ ] **Web loads** at http://localhost — login succeeds
- [ ] **200 devices** appear in device list (simulator auto-seeded)
- [ ] **Real-time chart** scrolls on device detail page (WebSocket `telemetry` frames)
- [ ] **Rule `temp GT 30`** fires FIRING alerts within 90s of spike injection, and the
      matching **恢复 / RESOLVED** toast follows — alert recovery is pushed, not just polled
- [ ] **Alert ACK** changes status to ACKED in the alert list
- [ ] **Set interval** command reaches the device and is acknowledged:
      `POST /api/v1/devices/{key}/commands/set-interval` then
      `GET /api/v1/devices/commands/{cmdId}` reports `SENT` → `ACKED` (with `ackedAt`)
- [ ] **Devices go offline** after ~60s without telemetry (`docker compose stop sim`, then
      watch `aluco_offline_flips_total` and the presence badge); they return on `docker compose start sim`
- [ ] **Grafana Aluco dashboard** (http://localhost:3000, provisioning auto-loads it) shows
      `aluco_ingest_received_total` rate > 0 and the `aluco_e2e_latency_seconds` histogram

Measured on a laptop (Docker Desktop, 200 devices @ 1s): ~200 msg/s ingested with
`result="dropped" = 0`, e2e p95 ≈ 40 ms, command round-trip ≈ 20 ms.

---

## Known Limitations

| Limitation | Rationale | Planned Resolution |
|---|---|---|
| EMQX credential mode not exercised end-to-end | Server-side credential sync exists (`EmqxAuthService`), but `aluco-sim` still connects anonymously, so the demo keeps `EMQX_ALLOW_ANONYMOUS=true` | Add device credential auth to aluco-sim, then flip the compose default |
| Command ACK | v1 had no device-side receipt | **v2 ✅**: `cmdack` topic + command table + 30s timeout monitoring; the web UI does not render the `command` WS frame yet (backend + API are complete) |
| Kafka sink writes per message | `KafkaTelemetryConsumer` still calls `writeBatch` + state upsert per record, unlike the buffered in-memory sink | Batch the consumer path the same way, then re-run the ADR-0009 comparison |
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
| `ALUCO_DB_PASSWORD` | `123456` local · `root` in compose | DB password |
| `ALUCO_MQTT_BROKER` | `tcp://localhost:1883` | EMQX address |
| `ALUCO_JWT_SECRET` | *(dev placeholder)* | HMAC key — **override in prod** |
| `ALUCO_EMQX_API_BASE` | `http://localhost:18083` | EMQX REST API base (compose sets `http://emqx:18083`) |
| `ALUCO_EMQX_API_USER` | `admin` | EMQX REST API user (for credential sync) |
| `ALUCO_EMQX_API_PASSWORD` | `public` | EMQX REST API password |
| `ALUCO_SINK_TYPE` | `memory` | `memory` / `kafka` — telemetry sink backend (Phase 2) |
| `ALUCO_STORE_STATE` | `mysql` | `mysql` / `redis` — device state backend (Phase 2) |
| `ALUCO_STORE_TIMESERIES` | `mysql` | `mysql` / `timescale` — time-series backend (Phase 2) |
| `ALUCO_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | Kafka bootstrap servers (Phase 2) |
| `ALUCO_REDIS_URL` | `redis://localhost:6379` | Redis connection URL (Phase 2) |
| `ALUCO_TIMESCALE_JDBC_URL` | `jdbc:postgresql://localhost:5432/aluco` | TimescaleDB JDBC URL (Phase 2) |
| `ALUCO_TIMESCALE_USERNAME` / `_PASSWORD` | `postgres` / *(empty)* | TimescaleDB credentials (Phase 2) |

### Simulator CLI

```bash
java -jar aluco-sim-1.0.0.jar [options]

# Common options
--devices 1000                 # 1000 simulated devices
--interval 1s                  # Report every 1 second per device
--ramp 60s                     # Stagger start over 60 seconds
--metrics temp,humidity        # Metrics to simulate
--spike-probability 0.01       # 1% chance of spike per reading
--seed-devices true            # auto-register devices via REST (default on)
--ack-drop-probability 0       # v2: 0~1, probability to drop cmdack (demo TIMEOUT path)
--api http://localhost:8080/api/v1   # Server REST for auto-seeding
--username admin --password admin123 # Login credentials for seeding
```

> **Note:** the simulator authenticates to the broker anonymously (there is no `--auth`
> flag in the current release); see Known Limitations for the credential-mode roadmap.

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
| `aluco.kafka.lag` | Gauge | **v2** Kafka consumer lag (sum over assigned partitions) |
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
| `GET` | `/devices/commands/{cmdId}` | **v2** Command detail by cmdId |
| `GET` | `/devices/{key}/commands` | **v2** Device command history (paginated) |
| `POST` | `/devices/{key}/token/rotate` | **v2** Rotate device MQTT token (old token invalidated immediately) |
| `GET` | `/rules` | List rules |
| `POST` | `/rules` | Create rule |
| `PATCH` | `/rules/{id}/enabled` | Toggle rule |
| `DELETE` | `/rules/{id}` | Delete rule |
| `GET` | `/alerts` | List alert events |
| `POST` | `/alerts/{id}/ack` | Acknowledge alert |
| `GET` | `/actuator/health` | Health check — **served at the host root**, not under `/api/v1` |
| `GET` | `/actuator/prometheus` | Prometheus scrape — same root path, JWT-exempt |

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
