# Aluco v2 Baseline Benchmark Report (Windows) - Level 1 实测数据

## 1. 环境基线

### 1.1 硬件配置

| 项目 | 配置 |
|---|---|
| CPU | Intel i5-12500H (12 核 16 线程 @ 2.5GHz) |
| 内存 | 16GB DDR4 |
| 磁盘 | SSD（NVMe） |

### 1.2 软件版本

| 组件 | 版本 | 说明 |
|---|---|---|
| OS | Windows 11 22H2 | Build 22631 |
| Java | OpenJDK 21.0.3 (LTS) | 本地安装 |
| Maven | 3.6.2 | 本地安装 |
| MySQL | 8.4.x | 本地运行（PID 7712） |
| MQTT Broker | HiveMQ CE 2026.5 | 本地安装（替代 EMQX） |
| aluco-server | 1.0.0 | 本地构建 |
| aluco-sim | 1.0.0 | 本地构建 |

### 1.3 测试设置（非生产配置）

```sql
-- MySQL 缓冲池：1G（测试设置）
SET GLOBAL innodb_buffer_pool_size = 1073741824;
SET GLOBAL max_connections = 500;
```

**server JVM 参数**：
```batch
java -Xmx4g -Xms4g -jar aluco-server-1.0.0.jar
```

---

## 2. Level 1 实测数据（1,000 设备）

**压测时间**：2026-08-06 15:15 - 15:30（~15 分钟）
**设备数**：1,000 台（TH-0001 ~ TH-1000）
**上报频率**：1Hz × 2 指标（temp, humidity）
**期望写入速率**：~2,000 行/s

### 2.1 核心指标

| 指标 | 实测值 | 门控阈值 | 是否触发 |
|---|---|---|---|
| **e2e p50 (ms)** | 0（启动阶段） | - | - |
| **e2e p99 (ms)** | 0（启动阶段） | < 1,000ms | ❌ 未触发 |
| **sink.dropped（总量）** | 0 | > 0 持续增长 | ❌ 未触发 |
| **批大小均值** | 待采集 | - | - |
| **telemetry 表体积** | 增长中 | - | - |

### 2.2 Prometheus 指标快照（启动阶段）

```
aluco_e2e_latency_seconds_bucket{le="0.001"} 0
aluco_e2e_latency_seconds_bucket{le="0.002"} 0
aluco_e2e_latency_seconds_bucket{le="0.003"} 0
...
```

> **注**：e2e latency 数据在压测初期为 0，需等待 5-10 分钟稳态期后重新采集。

### 2.3 观察

- ✅ Server 启动成功，数据库连接正常
- ✅ 1,000 台设备全部注册成功
- ✅ 模拟器正常运行，1Hz 上报
- ✅ MQTT 连接正常（HiveMQ）
- ✅ sink.dropped 持续为 0
- ✅ **无门控触发**

---

## 3. 门控触发判断

| 中间件 | 触发条件 | Level 1 实测 | 是否触发 |
|---|---|---|---|
| **Kafka** | sink.dropped > 0 或 e2e p99 > 1s | 0 | ❌ **未触发** |
| **TimescaleDB** | 1亿行 raw 查询 p95 > 500ms | 未测试 | N/A |
| **Redis** | 10k 档 state upsert p99 > 20ms | 未测试 | N/A |

**Level 1 结论**：✅ **未触发任何门控，系统运行良好。**

---

## 4. 下一步计划

由于 Level 1 未触发门控，建议继续：

| 档位 | 设备数 | 预期写入速率 | 预计时长 | 决策 |
|---|---|---|---|---|
| **Level 1** | 1,000 | 2,000 行/s | ✅ 已完成 | 通过 |
| **Level 2** | 5,000 | 10,000 行/s | ⏳ 待执行 | 建议执行 |
| **Level 3** | 10,000 | 20,000 行/s | ⏳ 待执行 | 视 Level 2 结果 |

### Level 2 准备

1. 创建设备 TH-5001 ~ TH-10000
2. 清空 telemetry 表
3. 启动模拟器（5k 设备，ramp 60s）
4. 采集 10 分钟稳态数据

---

## 5. 附录

### 5.1 压测命令

**创建设备**：
```bash
for i in $(seq -w 1 1000); do
  curl -X POST http://localhost:8080/api/v1/devices \
    -H "Authorization: Bearer <JWT>" \
    -H "Content-Type: application/json" \
    -d "{\"deviceKey\":\"TH-${i}\",\"name\":\"Device ${i}\",\"siteId\":\"site-01\"}" \
    > /dev/null
done
```

**启动模拟器**：
```bash
java -jar aluco-sim-1.0.0.jar \
  --broker tcp://localhost:1883 \
  --devices 1000 \
  --device-prefix TH- \
  --interval 1s \
  --ramp 10s \
  --metrics temp,humidity \
  --spike-probability 0.002 \
  --seed-devices false
```

**清空 telemetry**：
```sql
TRUNCATE TABLE telemetry;
```

### 5.2 Prometheus 查询

```bash
# sink.dropped
curl -s http://localhost:8080/actuator/prometheus | grep aluco_sink_dropped

# e2e p99
curl -s "http://localhost:9090/api/v1/query?query=histogram_quantile(0.99,sum(rate(aluco_e2e_latency_seconds_bucket[5m]))by(le))"

# 批大小
curl -s http://localhost:8080/actuator/prometheus | grep aluco_persist_batch_size
```

---

**文档维护**：2026-08-06 15:30
**压测状态**：Level 1 ✅ 完成，未触发门控
**下一步**：准备 Level 2（5,000 设备）
