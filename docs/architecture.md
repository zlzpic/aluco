# Aluco 项目结构与架构说明

> **文档目的**：为唯一维护者提供项目的「心智模型」——当前代码架构、各包/各类存在的意义、以及按产品规格未来要拓展的模块。写代码前先看这篇，避免迷失在包海里。
> **配套文档**：《Aluco v2 产品与技术规格说明》（落地依据，本文档是其代码地图）；《Aluco v1 产品与技术规格说明》（契约起源）；`docs/adr/`（决策记录）+ `docs/benchmarks/`（压测数据）。

---

## 0. 项目概况

**Aluco（灰林鸮）**：开源 IoT 设备机群监控平台。一句话数据流：

```
设备(模拟器) → MQTT(EMQX) → aluco-server(Spring Boot) → MySQL + WebSocket → Web 仪表盘
```

- **v1 定位**：模块化单体 + 三接缝抽象，可 `docker compose up` 三分钟内演示。
- **v2 定位**：*数据门控式演进*——先压测建立性能基线，撞墙后再沿三接缝引入 Kafka / Redis / TimescaleDB；每项引入都必须有前后对比数据支撑，写入 ADR。
- **技术栈**：Java 21 + Spring Boot 3.4 + Maven；MySQL 8.4（JPA 元数据 + JdbcTemplate 批量写）；EMQX 5.8（MQTT）；React 18 + Vite + Ant Design + ECharts（web）；Paho + picocli（模拟器）。

**模块划分（Monorepo）**：

```
aluco/
├── aluco-server/     # Spring Boot 后端（主战场）
├── aluco-sim/        # 设备模拟器 / 压测工具（Java CLI）
├── aluco-web/        # React + TS 前端
├── deploy/           # docker-compose / nginx / prometheus / grafana 配置
├── docs/             # adr/（决策）+ benchmarks/（压测报告）
└── .github/workflows/ci.yml
```

---

## 1. 后端包结构（aluco-server）

根包 `com.aluco.server`。

### 1.0 包边界一句话总览

| 包 | 一句话职责 | 依赖方向 |
|---|---|---|
| `common` | 全局共享模型：信封、数据点、编解码、异常 | 谁也不依赖 |
| `ingestion` | MQTT 接入层：连接 EMQX、订阅/发布、命令下发 | common |
| `processing` | 实时管线：校验 → **三接缝** → 落库/推送/告警 | common, device, alerting, push |
| `device` | 设备档案、状态存储（接缝三）、离线判定 | common |
| `alerting` | 告警规则、状态机、事件服务 | common |
| `command` | 下行命令生命周期（SENT→ACKED/FAILED/TIMEOUT） | common, ingestion |
| `push` | WebSocket 会话与实时推送 | common |
| `auth` | JWT 登录鉴权 + EMQX 凭证同步 | common |
| `api` | REST controller、鉴权过滤器、DTO | 其它全部 |

**ArchUnit 强制规则**（`ArchitectureTest`）：
- `api` 可依赖所有业务包；任何包不得依赖 `api`；
- `ingestion`、`push` 不得互相依赖；
- **存储访问只能经由三个接缝接口**（`TimeSeriesStore`/`StateStore`/`TelemetrySink`），业务代码不得直接出现 SQL/JdbcTemplate（实现类除外）；Controller 禁止强制转接缝实现类；
- 横切能力（鉴权、时钟、编解码）仅允许归属 `common`。

### 1.1 `common` — 共享模型与契约编解码

| 类 | 存在的意义 |
|---|---|
| `TelemetryMessage` | 处理后的标准遥测模型（deviceKey, siteId, ts, seq, metrics） |
| `TelemetryPoint` | 扁平数据点（deviceKey, metric, ts, val），供批量落库与查询 |
| `DeviceState` | 设备最新状态（metrics, online, lastSeenAt） |
| `DeviceCommand` / `CmdackMessage` | 下行命令 / 命令回执模型 |
| `EnvelopeCodec` | **信封解析器，全项目最核心契约**。校验 `v`、deviceId 与 topic 一致、ts 非未来、metrics 合法性、≤4KB；产出 `ParseResult(TelemetryMessage, dropReason)`。P3 Go 网关按同一逻辑独立实现 |
| `JwtService` / `AuthService` | JWT 签发/校验，登录服务 |
| `BizException` / `Page` | 统一异常 / 分页模型 |

### 1.2 `ingestion` — MQTT 接入层

| 类 | 存在的意义 |
|---|---|
| `MqttIngestor` (TelemetryIngestor + CommandPublisher) | 应用就绪后连 EMQX，订阅 `aluco/+/+/telemetry` 与 `aluco/+/+/cmdack`；断线 5s 自动重连；按 topic 后缀分流——遥测走 `TelemetryProcessor.onRawMessage`，回执走 `CommandAckProcessor` |
| `CommandPublisher` 接口 | 接缝外的一处抽象：`publish(deviceKey, cmd)`、`publishSetInterval(...)` |
| `NoOpCommandPublisher` | `aluco.mqtt.enabled=false`（无 broker 的开发环境）时的兜底实现 |

### 1.3 `processing` — 实时管线（三接缝所在地）

管线：`ingestion → DefaultTelemetryProcessor（校验） → TelemetrySink（接缝一） →（consumer）→ 冷/热/告警三路`

| 类 | 存在的意义 |
|---|---|
| `TelemetryProcessor` / `DefaultTelemetryProcessor` | 按信封规则校验，通过则 emit 到 sink；计数 `aluco.ingest.received{ok,dropped}` |
| **`TelemetrySink`**（接缝一） | `emit(TelemetryMessage)`，数据入口的替换点 |
| `InMemoryTelemetrySink` | **当前默认实现**：ArrayBlockingQueue（容量可配，默认 50,000；满了丢并计 `aluco.sink.dropped`）+ 单消费者线程分三路：冷路径攒批写入、热路径 upsert state + WS 推送、告警路径 `AlertingEngine.onTelemetry`；埋 e2e 延迟 |
| `KafkaTelemetrySink` | ⚠️ **P2 实现，当前有 bug（见 §5）**：`aluco.sink.type=kafka` 时激活，KafkaProducer 发 `aluco.telemetry` |
| `KafkaTelemetryConsumer` | ⚠️ **P2 实现，当前有 bug（见 §5）**：消费组 `aluco-server`，same 三路 fan-out；手动提交 offset（at-least-once） |
| `PresenceTracker` | presence 翻转移交管理 |
| **`TimeSeriesStore`**（接缝二） | `writeBatch(points)` / `query(deviceKey, metric, from, to, interval) → QueryResult(points, truncated)` |
| `MySqlTimeSeriesStore` | 当前默认：JdbcTemplate 批量写；`FLOOR(UNIX_TIMESTAMP(ts)/桶)` 降采样；raw 上限 10,000 点截断 |
| `MySqlTimescaleTimeSeriesStore` | P2 目标实现：直查超表 / 走 `telemetry_1m`、`telemetry_1h` 连续聚合 |
| `MySqlTimescaleMigrator` | P2 迁移作业：分批 MySQL→TimescaleDB（COPY），checkpoint 断点续跑 + 双跑校验 |

### 1.4 `device` — 设备档案 + 状态存储（接缝三）

| 类 | 存在的意义 |
|---|---|
| `DeviceService` 接口 + `DeviceServiceImpl` | 设备 CRUD（getByKey、page、delete、token 轮换入口、getState） |
| `Device` / `DeviceRepository` / `DeviceTombstone`(+Repo) | 设备实体；**tombstone 解决孤儿语义**：删设备时把 (device_id, device_key) 移入 tombstone，历史遥测仍可按 key 解析 |
| **`StateStore`**（接缝三） | `upsert / get / list` |
| `MySqlStateStore` | 当前默认：`device_state` 表，metrics JSON 合并覆盖 |
| `RedisStateStore` | ⚠️ P2 实现（`aluco.store.state=redis`）：HASH `aluco:state:{key}` + ZSET `aluco:last_seen` |
| `OfflineDetection` 接口 | v2 契约：`sweepOffline(cutoff)` 返回「由在线翻转为离线」的设备集合 |
| `MySqlOfflineDetection` | 当前默认：按 `online=1 AND last_seen_at < 阈值` 找出并翻转 |
| `OfflineDetectionTask` | `@Scheduled` 定时调 sweepOffline，翻转集合触发 presence(false) 广播（**以持久化旧值为准**，重启不重复广播） |
| `DeviceCommandService` + Impl | SET_INTERVAL 下发的业务入口 |

### 1.5 `alerting` — 告警状态机

| 类 | 存在的意义 |
|---|---|
| `AlertRuleService` + Impl | 规则 CRUD（create / setEnabled / delete / page / listEnabled / getById）；启用规则缓存 |
| `AlertEventService` + Impl | 事件查询/确认（fire / resolve / ack / query / getById） |
| `RuleEvaluator` 接口 + `DefaultRuleEvaluator` | 内存状态机 `Map<(ruleId,deviceId), NORMAL|FIRING>`：NORMAL+违反→FIRED；FIRING+恢复→RESOLVED；FIRING+仍违反→NONE（去重） |
| `AlertingEngine` | sink 三路中的告警路径：找「启用且 metric 匹配且作用范围命中」的规则逐条 evaluate；启动时从 DB 恢复 FIRING 状态避免重复触发；`aluco.rule.eval` 计时 |
| `AlertRule` / `AlertEvent`（实体+Repo） | `threshold` 统一命名；`device_key`、`rule_name` 快照列（V2 迁移加，告警展示不依赖 device 表） |

### 1.6 `command` — 命令回执链路（P1 已实现）

| 类 | 存在的意义 |
|---|---|
| `Command` / `CommandRepository` | command 表：cmd_id UNIQUE、status ∈ SENT/ACKED/FAILED/TIMEOUT、acked_at |
| `CommandService` | 建命令(SENT)→发布；收到 ack→置 ACKED/FAILED；查询单条/按设备分页 |
| `CommandAckProcessor` | 处理 `aluco/+/+/cmdack`（由 `MqttIngestor` 订阅并分流）：ACKED→`ack()`、FAILED→`fail()`，仅当命令仍是 SENT 才流转；随后 `LivePush.pushCommandEvent` 广播 |
| `CommandTimeoutTask` | `@Scheduled(fixedDelay=10s)`：SENT 超过 30s 置 TIMEOUT |
| `LivePush.pushCommandEvent` | 状态流转经 WS `command` 帧广播 |

### 1.7 `push` — WebSocket 实时推送

| 类 | 存在的意义 |
|---|---|
| `LivePush` 接口 | pushTelemetry / pushAlert / pushPresence / pushCommandEvent / subscribe / unsubscribe |
| `WebSocketLivePush` | 会话注册表 + 订阅模型（telemetry 仅发给订阅会话，alert/presence/command 全局广播）+ 30s ping + `aluco.ws.sessions` gauge |
| `LiveWebSocketHandler` / `WebSocketConfig` | 握手校验 JWT（`/ws/live?token=...`），帧路由 |

### 1.8 `auth` — 登录鉴权与 EMQX 凭证

| 类 | 存在的意义 |
|---|---|
| `AuthServiceImpl` | 登录（BCrypt 校验）→ 发 JWT |
| `EmqxAuthService` | ⚠️ 凭证同步（P1·部分）——经 EMQX REST API 同步设备凭证 / 平台超级用户 `aluco-server` |
| `AppUser` / `AppUserRepository` | `app_user` 表（原 `user` 改名，V2 迁移） |
| API 侧 `JwtAuthFilter` | `/api/**` 校验 Bearer JWT；WS 握手校验 query token |

### 1.9 `api` — REST 层

Controller：`AuthController`（login）、`DeviceController`、`RuleController`、`AlertController` + `GlobalExceptionHandler`。
**约定**：前缀 `/api/v1`；分页 `{list,total,page,size}`；错误 `{code,message}` + 合适 HTTP 状态码；时间字段统一 epoch 毫秒。

主要端点（与 v2 规格 14.1 对应）：
- 设备：`POST /devices`（token 仅创建时返回一次）、`GET /devices`、`GET/DELETE /devices/{deviceKey}`、`GET .../state`、`GET .../telemetry`（metric/from/to/interval，响应 `{metric, points, truncated}`）、`POST .../commands/set-interval`、`POST .../token/rotate`
- 规则：`GET/POST /rules`、`PATCH /rules/{id}/enabled`、`DELETE /rules/{id}`
- 告警：`GET /alerts?status&deviceKey`、`POST /alerts/{id}/ack`
- 命令：`GET /commands/{cmdId}`、`GET /devices/{deviceKey}/commands`（按规格 5.1.5）

---

## 2. 数据库（Flyway：V1 + V2 + V3）

`aluco-server/src/main/resources/db/migration/`，MySQL 8.4（生产/演示）+ H2 `MODE=MySQL`（测试）；脚本必须双方言兼容（无 `AFTER`、无多动作 `ALTER`、无 `UPDATE...JOIN`、索引名按 schema 命名、种子用 `INSERT...SELECT...WHERE NOT EXISTS`）。

| 表 | 关键点 |
|---|---|
| `device` | device_key UNIQUE、token（UUID 去横线）、site_id |
| `device_tombstone`（V2 新增） | 删设备时移入，孤儿语义第一支柱 |
| `device_state` | 每设备一行，metrics JSON、online、last_seen_at |
| `telemetry` | 窄表一指标一行；`idx_dev_metric_ts(device_id, metric, ts)` + **V2 加 `UNIQUE uk_dev_metric_ts`**（Kafka at-least-once 幂等写入的基石） |
| `alert_rule` / `alert_event` | 规则与事件；V2 给 event 加 `device_key`、`rule_name` 快照列（告警展示永不 join device） |
| `command`（V2 新增） | cmd_id UNIQUE、status、acked_at |
| `app_user`（V2 由 `user` 改名） | 根除保留字问题；V3 幂等种子 demo 账号 `admin`（仅演示用，见 README） |

**孤儿语义三支柱（ADR-0008，已决策不可变）**：① tombstone 保设备 key 解析路径（`device ∪ device_tombstone`）；② alert_event 快照列；③ telemetry 不建外键。为何不用 FK / 弃软删除 → 见 ADR-0008。

---

## 3. 全局契约速查

### 3.1 MQTT 主题（v2 规格 14.3）

| 主题 | 方向 | QoS |
|---|---|---|
| `aluco/{siteId}/{deviceKey}/telemetry` | 设备→平台 | 1 |
| `aluco/{siteId}/{deviceKey}/cmd` | 平台→设备 | 1 |
| `aluco/{siteId}/{deviceKey}/cmdack`（P1 新增） | 设备→平台 | 1 |

Kafka（P2）：`aluco.telemetry`（6 分区，key=deviceKey 保同设备有序）、`aluco.telemetry.dlq`。

### 3.2 信封（v=1，全局稳定契约）

```json
{ "v": 1, "deviceId": "TH-0001", "ts": 1752739200000, "seq": 42,
  "metrics": { "temp": 35.2, "humidity": 61.0 } }
```
cmdack（独立 schema，v=1）：`{ "v":1, "cmdId":"uuid", "deviceId":"TH-0001", "ts":..., "status":"ACKED", "message":"..." }`。

### 3.3 WebSocket 帧（`/ws/live`）

- 客户端→服务端：`subscribe` / `unsubscribe`（deviceIds）
- 服务端→客户端：`telemetry`（定向）/ `alert` / `presence` / `command`（全局广播）
- 服务端每 30s ping；客户端断线指数退避重连（1s→30s）

---

## 4. 观测（Metrics，名称必须一致）

| 指标 | 类型 | 来源 |
|---|---|---|
| `aluco.ingest.received{result=ok,dropped}` | Counter | DefaultTelemetryProcessor |
| `aluco.sink.dropped` | Counter | InMemoryTelemetrySink（队列满丢） |
| `aluco.e2e.latency` | Timer | 信封 ts → 处理完成 |
| `aluco.persist.batch.size` | DistributionSummary | MySqlTimeSeriesStore |
| `aluco.rule.eval` | Timer | AlertingEngine |
| `aluco.ws.sessions` | Gauge | WebSocketLivePush |
| `aluco.alerts.firing` | Gauge | AlertEventServiceImpl |
| `aluco.kafka.dlq`（P2） | Counter | KafkaTelemetryConsumer |

---

## 5. 三接缝现状与**下一步代码工作**（最重要）

### 现状：三接缝接口就位，P2 实现类已存在但**有 bug**

接缝一 `TelemetrySink`、接缝二 `TimeSeriesStore`、接缝三 `StateStore` 三个接口 + 各自 MySQL/默认实现 + P2 目标实现（Kafka/Redis/Timescale）**都在代码里**，且由 `aluco.sink.type` / `aluco.store.state` / `aluco.store.timeseries` 三个配置开关切换。

**当前阻塞在代码上前，必须修的两个 bug：**

1. **`KafkaTelemetrySink` 序列化错误**（`processing/KafkaTelemetrySink.java`）：
   手写 `String.format` 序列化，`metrics` Map 的 `toString()` 输出 Java 格式 `{temp=35.2}`，**不是 JSON**；而 consumer 用 Jackson 反序列化。一条消息都过不去。→ 改用注入的 `ObjectMapper`，与 consumer 同一套，走信封 JSON 完整序列化。

2. **`KafkaTelemetryConsumer.sendToDlq()` 是空壳**：注释明说「producer 未注入，先 log」。DLQ 从未真正发送到 `aluco.telemetry.dlq`。→ 注入一个 `KafkaProducer`（可抽取共享 bean 供 sink/consumer 复用），让 DLQ 真实落 topic，带 `error_reason` 头。

修复后再按规格 6.1 对齐行为，重跑 Level 2 阶梯压测验证 `sink.dropped` 归零 → 复测数据回填 ADR-0009。

---

## 6. 模拟器（aluco-sim）

```
aluco-sim/src/main/java/com/aluco/sim/
├── AlucoSim.java          # picocli 主命令 + 子命令注册（backfill 等）
├── SimDevice.java         # 单台设备：波形 temp=22+3sin(t/10min)+N(0,0.3)、尖峰注入、订阅自身 cmd、
│                          #   --interval 上报、SIGTERM 优雅退出、10s 统计
├── DeviceSeeder.java      # --seed-devices：经 server REST 注册设备，拿 token（--auth 后作为 MQTT 密码）
├── EnvelopeJson.java      # 信封序列化
└── bench/BackfillTool.java # backfill 工具：直写数据库（不经 MQTT），checkpoint 断点续跑，SIGINT 退出码 130
```

CLI 关键参数：`--broker --devices --interval --ramp --metrics --spike-probability --seed-devices --auth`；生命周期/时间参数条款适用于所有 CLI 组件。

---

## 7. 前端（aluco-web）

React 18 + TS + Vite + AntD + ECharts。结构：
- `pages/`：dashboard、devices、deviceDetail、rules、alerts、login
- `stores/`：authStore、deviceStore、alertStore、telemetryStore、mockStore
- `ws/liveClient.ts`：单例 WS 连接管理器（订阅模型、指数退避重连、ping）
- `api/`：axios 封装 + 各资源接口；`mock/`：mockServer + mockWs（无后端演示模式）
- `components/`：TelemetryChart、OnlineBadge、AlertStatusTag、TokenRevealModal（token 仅显一次）、AppLayout

---

## 8. 部署与 CI

- `deploy/docker-compose.yml`：`name: aluco`；mysql、emqx（demo 默认匿名，`EMQX_ALLOW_ANONYMOUS=false` 切凭证模式）、server、web、prometheus、grafana，sim 在 `demo` profile 后
- `nginx.conf`：静态托管 + `/api`、`/ws` 反代（WS Upgrade 头）
- `deploy/scripts/`：benchmark 系列脚本（`.bat`/`.ps1`/`.sh`）。模拟器 jar **不再入库**（曾提交过一个与源码脱节的 v1 构建），需要时 `cd aluco-sim && mvn -q package` 产出 `target/aluco-sim-1.0.0.jar`
- `.github/workflows/ci.yml`：server 构建+测试、web 构建、simulator 构建；`compose-smoke` 在 push/schedule/dispatch 下起全栈，断言摄取速率与命令 SENT→ACKED
- Grafana：provisioning 预置 datasource + `aluco-overview.json` 面板

---

## 9. 未来拓展模块（按产品规格，含「存在的意义」）

### P1 剩余（可与 P2 并行，多为补全）

**REST / WS 契约补全**（规格 5.1.5、8）：
- `GET /commands/{cmdId}`、`GET /devices/{key}/commands?page&size`（最近命令列表）
- POST set-interval 响应改 `{cmdId, status}`；WS `command` 帧驱动前端状态流转 SENT→ACKED/FAILED/TIMEOUT
- 意义：命令从「发出去就不管」闭环为「可追踪、可兜底轮询、前端可见」。

**MQTT 认证落地**（规格 5.2，兑现 ADR-0003，写 ADR-0007）：
- 语义：`username=device_key`、`password=device.token`
- EMQX 配置：built-in database 认证器 + ACL（`{username}` 占位符，设备仅能 pub/sub 自身主题；平台超级用户 `aluco-server` 全通）；`EMQX_ALLOW_ANONYMOUS=false`
- 凭证同步：启动时幂等 bootstrap（确保认证器/授权源/超级用户 → 全量对账 device 表与 EMQX 用户，多退少补）；运行时增删改对齐；EMQX 不可达指数退避重试（上限 5min）+ `aluco.emqx.sync.failures` 指标，不阻塞启动
- 双 profile：`aluco.mqtt.auth=on|off`（off = 任意 broker 纯功能开发）
- 意义：v1 最大的已知安全欠账；token 从「无用途字段」变成正式接入凭证，是 ACL 与轮换机制的地基。

**Token 轮换/吊销 REST**（接口 16）：
- `POST /devices/{key}/token/rotate` → `{token}` 仅返回一次，旧凭证立即失效（EMQX 先删后建）
- 意义：凭证泄露止损手段，配合 `--auth` 模拟器验证新老凭证切换。

### P2：性能演进（门控全部已触发轮次，逐项复测对比）

**Kafka TelemetrySink（ADR-0009）** — 接缝一，**优先任务，§5 有 bug 修复说明**：
- 拓扑：6 分区 key=deviceKey；DLQ 连续失败 3 次转入（带 error_reason 头）；acks=all + 幂等生产者
- 校验仍在边缘完成，非法消息不进 Kafka（沿用丢弃计数）
- 消费者：批量落库成功才 commit offset（at-least-once）；重复由 telemetry 唯一键 + `INSERT ... ON DUPLICATE KEY UPDATE` 幂等吸收
- 观测：`aluco.kafka.lag` gauge + Micrometer Kafka client 指标
- 意义：消化 10k 行/s 写入、`sink.dropped` 归零；缓冲接缝的真实价值兑现。

**Redis StateStore + ZSET 离线判定（ADR-0010）** — 接缝三：
- 数据模型：HASH `aluco:state:{key}`（metrics/lastSeenAt/online）+ ZSET `aluco:last_seen`（score=lastSeenAt）
- upsert：HSET+ZADD 同 Lua 脚本原子；离线巡检 `ZRANGEBYSCORE ... -(now-60s)` 取候选 → 读旧 online → 原子置 0 仅对迁移集合广播（持久化迁移语义）
- **明确不用 keyspace notifications**（事件不可靠、重启丢失），理由写 ADR-0010
- 意义：把 device_state 的 upsert 与离线巡检从 MySQL 热路径上拿掉。

**TimescaleDB（ADR-0011）**：
- 目标：PG16 + 超表 7 天 chunk；连续聚合 `telemetry_1m/_1h`；`raw` 走超表、`1m/5m` 走 `_1m`、`1h/1d` 走 `_1h`
- 迁移作业：分批 MySQL→PG（COPY），checkpoint 断点续跑 + 双跑校验（抽样桶 AVG 一致）；切换接受秒级停写
- 7 天后原生压缩（目标 ≥10×），保留策略默认关
- 意义：1 亿行存量下 raw 查询 p95≤500ms 的唯一现实路径（测量目标而非推测）。

### P3：Go 网关预研（ADR-0013）

- 单个 `cmd/aluco-gw-poc`：MQTT 订阅 → Go 复刻 EnvelopeCodec（v=1）→ 合法消息发 Kafka `aluco.telemetry`
- 硬边界：不下发命令、不生产化、不接生产链路、不引 gRPC/K8s
- 意义：验证「信封契约可异语言独立实现」——契约层质量的分水岭测试。

---

## 10. ADR 索引（docs/adr/）

| 编号 | 主题 | 一句话 |
|---|---|---|
| 0001 | EMQX vs 自研 broker | 选现成中间件，5.8.x 最后 Apache 2.0 线 |
| 0002 | 模块化单体 + ArchUnit 强边界 | 边界即未来服务边界 |
| 0003 | v1 匿名 MQTT + 应用层校验 | 已知限制 + 加固路径（→P1 认证） |
| 0004 | 窄表不分区 | 「第一天最合理」，容量拐点留给压测（含 v2 续篇压测结论） |
| 0005 | 稳定信封契约 | 跨版本渐进重构基石 |
| 0006 | cmdack 独立 schema | 不升级信封 v=2 的理由 |
| 0007 | EMQX 内置认证、token 即密码 | 凭证同步/轮换/双 profile |
| 0008 | 孤儿语义文档化 | tombstone + 快照；为何弃软删除 |
| 0009 | Kafka 于遥测边缘 | 门控数据 + 前后对比（当前主线） |
| 0010 | Redis device shadow + ZSET 离线 | 为何不用 keyspace notifications |
| 0011 | TimescaleDB 迁移 | 门控数据、迁移作业、压缩比 |
| 0012 | Testcontainers 取代 H2 | schema 字面规格恢复 |
| 0013 | Go 网关 spike | 契约跨语言验证结论 |

---

*维护提示：本文档随 PR 演进。新增包/类/表后顺手更新对应小节；接缝实现类的开关与默认值以 application.yml 与 @ConditionalOnProperty 为准（本文不重复贴配置）。*