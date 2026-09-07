# Aluco v2 Migration Verification Guide

> **Purpose**: This document records the verification steps for v2 migration features, including contract fixes, tombstone semantics, EMQX authentication, and optional Phase 2 backends.

---

## Table of Contents

1. [Contract Fixes (6 items)](#1-contract-fixes-6-items)
2. [Tombstone Semantics](#2-tombstone-semantics)
3. [EMQX Authentication (Phase 1)](#3-emqx-authentication-phase-1)
4. [Command ACK (Phase 1)](#4-command-ack-phase-1)
5. [Token Rotation (Phase 1)](#5-token-rotation-phase-1)
6. [Phase 2 Backends (Optional)](#6-phase-2-backends-optional)

---

## 1. Contract Fixes (6 items)

### 1.1 `threshold` Naming Unified

**Spec**: v2 spec 4.2 #1

**Fix**: REST request/response field unified to `threshold`; DB column remains `threshold_val` (persistence layer only).

**Verification**:

```bash
# Create rule with "threshold" field (NOT "thresholdVal")
curl -X POST http://localhost:8080/api/v1/rules \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"name":"temp-high","metric":"temp","op":"GT","threshold":30}'

# Expected: 200 OK with rule object containing "threshold"
# Response should NOT contain "thresholdVal"
```

---

### 1.2 RESOLVED Push Carries Persisted Event

**Spec**: v2 spec 4.2 #2

**Fix**: WS `alert` frame (including RESOLVED) always pushes the persisted event object with real `id` and `resolvedAt`.

**Verification**:

1. Create rule `temp GT 30` (device-specific: `TH-0001`)
2. Wait for spike injection (or manually send telemetry `temp: 35`)
3. Observe WS `alert` frame for FIRING event (should have real `id`)
4. Wait for resolution (temp drops below 30)
5. Observe WS `alert` frame for RESOLVED event (should have same `id` + `resolvedAt` field)

```json
// RESOLVED frame example
{ "type": "alert", "event": { "id": 101, "ruleName": "temp-high", "deviceId": "TH-0001",
  "status": "RESOLVED", "value": 28.5, "triggeredAt": 1752739200000, "resolvedAt": 1752739260000 } }
```

---

### 1.3 Alert List Redundant Fields

**Spec**: v2 spec 4.2 #3

**Fix**: `GET /alerts` response includes non-null `ruleName` and `deviceKey` for every element (snapshot, not join).

**Verification**:

```bash
curl -H "Authorization: Bearer <JWT>" \
  "http://localhost:8080/api/v1/alerts?status=FIRING"

# Expected: Each element contains:
# - ruleName: string (non-null)
# - deviceKey: string (non-null)
```

---

### 1.4 Service Interface Completeness

**Spec**: v2 spec 4.2 #4

**Fix**: `AlertRuleService`, `AlertEventService`, `DeviceService` all have `getById`/`getByKey`.

**Verification**:

```bash
# Rule by ID
curl -H "Authorization: Bearer <JWT>" http://localhost:8080/api/v1/rules/1

# Device by key
curl -H "Authorization: Bearer <JWT>" http://localhost:8080/api/v1/devices/TH-0001

# Alert event by ID
curl -H "Authorization: Bearer <JWT>" http://localhost:localhost:8080/api/v1/alerts/101
```

> ❌ **Anti-pattern banned**: `page(1, MAX)` to fetch single entity. Use `getById`/`getByKey` instead.

---

### 1.5 Telemetry Query Result Object

**Spec**: v2 spec 4.2 #5

**Fix**: `TimeSeriesStore.query` returns `QueryResult(points, truncated)`; REST response includes `truncated` field.

**Verification**:

```bash
# Query with raw interval
curl "http://localhost:8080/api/v1/devices/TH-0001/telemetry?metric=temp&from=1752739200000&to=1752825600000&interval=raw"

# Expected response:
# { "metric": "temp", "points": [...], "truncated": false }
# Response header: X-Truncated: false (backward compat)
```

---

### 1.6 Offline Detection Contractualized

**Spec**: v2 spec 4.2 #6

**Fix**: `OfflineDetection` interface introduced; `sweepOffline(Instant cutoff)` returns devices flipped from online→offline.

**Verification**:

1. Start server with debug logging
2. Stop simulator for a device (`TH-0001`)
3. Wait 60s (offline threshold)
4. Check server logs for `OfflineDetection` sweep execution
5. Verify WS `presence` frame: `{ "type": "presence", "deviceId": "TH-0001", "online": false }`

---

## 2. Tombstone Semantics

**Spec**: v2 spec 4.4.1

**Components**:

| Table | Purpose |
|---|---|
| `device_tombstone` | Stores deleted device metadata (`device_id`, `device_key`, `deleted_at`) |
| `alert_event.device_key` | Snapshot column for orphan semantics (alert history survives device deletion) |

**Verification**:

### Step 1: Create and Delete Device

```bash
# Create device
curl -X POST http://localhost:8080/api/v1/devices \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"deviceKey":"TMP-0001","name":"Temp Device","siteId":"site-01"}'

# Send telemetry
curl -X POST http://localhost:8080/api/v1/devices/TMP-0001/telemetry \
  ...

# Delete device
curl -X DELETE http://localhost:8080/api/v1/devices/TMP-0001
# Expected: 204 No Content
```

### Step 2: Verify Tombstone Record

```sql
-- Check tombstone table
SELECT * FROM device_tombstone WHERE device_key = 'TMP-0001';
-- Expected: 1 row with deleted_at populated

-- Verify device removed from device table
SELECT * FROM device WHERE device_key = 'TMP-0001';
-- Expected: 0 rows
```

### Step 3: Verify Historical Data Still Queryable

```bash
# Query telemetry for deleted device (should succeed)
curl "http://localhost:8080/api/v1/devices/TMP-0001/telemetry?metric=temp&from=...&to=...&interval=raw"
# Expected: 200 OK with historical points

# Query alerts for deleted device
curl -H "Authorization: Bearer <JWT>" "http://localhost:8080/api/v1/alerts?deviceKey=TMP-0001"
# Expected: 200 OK with ruleName/deviceKey snapshots
```

### Step 4: Run Unit Test

```bash
cd aluco-server
./mvnw test -Dtest=DeviceTombstoneTest
# Expected: 4 tests PASS
```

---

## 3. EMQX Authentication (Phase 1)

**Spec**: v2 spec 5.2, ADR-0007

**Config**:

| Variable | Default | Description |
|---|---|---|
| `ALUCO_MQTT_AUTH` | `on` | `on` = EMQX auth enabled; `off` = insecure dev profile |
| `ALUCO_EMQX_API_USER` | `admin` | EMQX REST API user |
| `ALUCO_EMQX_API_PASSWORD` | `public` | EMQX REST API password |

**Verification**:

### Step 1: Start EMQX with Auth Enabled

```bash
# Docker
docker run -d --name emqx -p 1883:1883 -p 18083:18083 \
  -e EMQX_ALLOW_ANONYMOUS=false \
  emqx/emqx:5.8.6

# Or modify compose emqx service:
# environment:
#   - EMQX_ALLOW_ANONYMOUS=false
```

### Step 2: Start Server with Auth=On

```bash
java -jar aluco-server.jar \
  --aluco.mqtt.auth=on \
  --aluco.emqx.api.user=admin \
  --aluco.emqx.api.password=public
```

### Step 3: Register Device and Verify Credential Sync

```bash
# Create device
curl -X POST http://localhost:8080/api/v1/devices \
  -H "Authorization: Bearer <JWT>" \
  -d '{"deviceKey":"AUTH-001","name":"Auth Test","siteId":"site-01"}'

# Response includes "token" field
# Expected: Server logs "EMQX user created for AUTH-001"
```

### Step 4: Verify Auth Enforcement

```bash
# Attempt anonymous connection (should fail)
mosquitto_sub -h localhost -t "aluco/+/+/telemetry" -u "" -P ""
# Expected: Connection refused (CONNACK error)

# Attempt connection with wrong token (should fail)
mosquitto_sub -h localhost -t "aluco/+/+/telemetry" -u "AUTH-001" -P "wrong-token"
# Expected: Connection refused

# Attempt connection with correct token (should succeed)
mosquitto_sub -h localhost -t "aluco/+/AUTH-001/cmd" -u "AUTH-001" -P "<token-from-create>"
# Expected: Connection established, subscription ACK

# Attempt to publish to another device's topic (should fail - ACL)
mosquitto_pub -h localhost -t "aluco/+/OTHER-001/telemetry" -u "AUTH-001" -P "<token>" -m "{}"
# Expected: PUBACK error (ACL denied)
```

### Step 5: Verify Token Rotation

```bash
# Rotate token
curl -X POST http://localhost:8080/api/v1/devices/AUTH-001/token/rotate \
  -H "Authorization: Bearer <JWT>"

# Expected: { "token": "new-token" }

# Old token should fail
mosquitto_pub -h localhost -t "aluco/+/AUTH-001/telemetry" -u "AUTH-001" -P "<old-token>" -m "{}"
# Expected: Connection refused

# New token should succeed
mosquitto_pub -h localhost -t "aluco/+/AUTH-001/telemetry" -u "AUTH-001" -P "<new-token>" -m "{}"
# Expected: Message sent
```

### Step 6: Verify Credential Revocation on Delete

```bash
# Delete device
curl -X DELETE http://localhost:8080/api/v1/devices/AUTH-001

# Old token should fail
mosquitto_pub -h localhost -t "aluco/+/AUTH-001/telemetry" -u "AUTH-001" -P "<new-token>" -m "{}"
# Expected: Connection refused (user deleted from EMQX)
```

---

## 4. Command ACK (Phase 1)

**Spec**: v2 spec 5.1, ADR-0006

**New Topic**: `aluco/{siteId}/{deviceKey}/cmdack` (QoS 1)

**New Table**: `command` (cmd_id, device_key, type, params, status, created_at, acked_at)

**Verification**:

### Step 1: Send Command and Verify ACK

```bash
# Send SET_INTERVAL command
curl -X POST http://localhost:8080/api/v1/devices/TH-0001/commands/set-interval \
  -H "Authorization: Bearer <JWT>" \
  -H "Content-Type: application/json" \
  -d '{"intervalSec":5}'

# Expected response:
# { "cmdId": "550e8400-e29b-41d4-a716-446655440000", "status": "SENT" }
```

### Step 2: Monitor Command Status via WS

Connect to `ws://localhost:8080/ws/live?token=<JWT>` and observe:

1. **SENT** → Command created, status `SENT`
2. **ACKED** → Simulator receives cmd and sends `cmdack`, status becomes `ACKED`

```json
// WS command frame
{ "type": "command", "event": { "cmdId": "550e8400-...", "deviceId": "TH-0001", "status": "ACKED" } }
```

### Step 3: Verify Command History

```bash
curl -H "Authorization: Bearer <JWT>" \
  "http://localhost:8080/api/v1/devices/TH-0001/commands"
# Expected: Paginated list of commands for TH-0001
```

### Step 4: Test TIMEOUT Path

```bash
# Start simulator with ack-drop-probability=1 (drop all ACKs)
java -jar aluco-sim.jar --devices 1 --ack-drop-probability 1 ...

# Send command
curl -X POST http://localhost:8080/api/v1/devices/TH-0001/commands/set-interval \
  -H "Authorization: Bearer <JWT>" \
  -d '{"intervalSec":5}'

# Wait 30s (command timeout)
# Expected: Command status becomes TIMEOUT
# WS receives: { "type": "command", "event": { ..., "status": "TIMEOUT" } }
# Metric aluco.command.timeout incremented
```

---

## 5. Token Rotation (Phase 1)

**Spec**: v2 spec 5.2.4

**Verification**: See [EMQX Authentication Step 5](#step-5-verify-token-rotation).

---

## 6. Phase 2 Backends (Optional)

### 6.1 Kafka TelemetrySink

**Spec**: v2 spec 6.1, ADR-0009

**Config**: `--aluco.sink.type=kafka --aluco.kafka.bootstrap=localhost:9092`

**Verification**:

1. Start Kafka 3.9 KRaft cluster
2. Start server with `--aluco.sink.type=kafka`
3. Verify topic created: `aluco.telemetry` (6 partitions)
4. Verify DLQ topic: `aluco.telemetry.dlq`
5. Send telemetry, verify Kafka lag decreases
6. Check metric: `aluco.kafka.lag` gauge

### 6.2 Redis StateStore

**Spec**: v2 spec 6.2, ADR-0010

**Config**: `--aluco.store.state=redis --aluco.redis.url=redis://localhost:6379`

**Verification**:

1. Start Redis 7
2. Start server with `--aluco.store.state=redis`
3. Send telemetry, verify Redis keys:
   - `aluco:state:TH-0001` (HASH)
   - `aluco:last_seen` (ZSET)
4. Verify offline detection uses ZSET sweep (not keyspace notifications)

### 6.3 TimescaleDB TimeSeriesStore

**Spec**: v2 spec 6.3, ADR-0011

**Config**: `--aluco.store.timeseries=timescale --aluco.timescale.jdbc-url=jdbc:postgresql://localhost:5432/aluco`

**Verification**:

1. Start PostgreSQL 16 + TimescaleDB 2.17
2. Run migration script to create hypertable
3. Start server with `--aluco.store.timeseries=timescale`
4. Query telemetry, verify `telemetry_1m` and `telemetry_1h` continuous aggregates used
5. Run `TimescaleMigrator` for backfill (optional)
6. Verify compression ratio (target ≥10×, measured value in ADR-0011)

---

## Appendix: Unit Test Commands

```bash
cd aluco-server

# Run all unit tests (exclude SmokeIT)
./mvnw test -Dtest="AlucoServerApplicationTests,EnvelopeCodecTest,RuleEvaluatorStateMachineTest,DownsampleBucketTest,ArchitectureTest,DeviceTombstoneTest" -DfailIfNoTests=false

# Run specific test
./mvnw test -Dtest=DeviceTombstoneTest
./mvnw test -Dtest=EnvelopeCodecTest
./mvnw test -Dtest=ArchitectureTest
```

**Expected Results** (as of v2 completion):

| Test | Status | Notes |
|---|---|---|
| AlucoServerApplicationTests | ⚠️ SKIP | Requires MySQL running (integration test) |
| EnvelopeCodecTest | ✅ 11/11 PASS | Contract validation |
| RuleEvaluatorStateMachineTest | ✅ 9/9 PASS | Alert state machine |
| DownsampleBucketTest | ✅ 3/3 PASS | SQL bucket calculation |
| ArchitectureTest | ✅ 5/5 PASS | ArchUnit boundary rules |
| DeviceTombstoneTest | ✅ 4/4 PASS | Tombstone semantics |

---

**Last Updated**: 2026-08-06  
**Aluco Version**: v2.0.0
