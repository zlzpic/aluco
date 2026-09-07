#!/bin/bash

# ==========================================
# Aluco v2 完整阶梯压测脚本（Windows Git Bash）
# ==========================================
# Level 1: 1,000 设备
# Level 2: 5,000 设备
# Level 3: 10,000 设备
# ==========================================

set -e

# 配置
SERVER_URL="http://localhost:8080"
JWT_TOKEN="eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJhZG1pbiIsImlhdCI6MTc4NjAwMTcxNywiZXhwIjoxNzg2MDg4MTE3fQ.1iFrPgdq56Z7eztc7ltBSCVlhBTxSH-s7Lp1fSfd1aAg7ZE4ObMIqzATUg9r5ez0"
MYSQL_PASSWORD="123456"
SIM_JAR="D:/Program/Iot/aluco/aluco-sim/target/aluco-sim-1.0.0.jar"
LOG_DIR="D:/Program/Iot/aluco/docs/benchmarks/logs"
REPORT_FILE="D:/Program/Iot/aluco/docs/benchmarks/v2-baseline-windows.md"

# 创建日志目录
mkdir -p "$LOG_DIR"

# 时间戳
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
LOG_FILE="$LOG_DIR/benchmark-$TIMESTAMP.log"

echo "==========================================" | tee -a "$LOG_FILE"
echo "Aluco v2 完整阶梯压测" | tee -a "$LOG_FILE"
echo "开始时间: $(date)" | tee -a "$LOG_FILE"
echo "==========================================" | tee -a "$LOG_FILE"
echo ""

# 检查服务
echo "[1/4] 检查服务状态..." | tee -a "$LOG_FILE"

# 检查 MySQL
if mysql -u root -p"$MYSQL_PASSWORD" -e "SELECT 1;" >/dev/null 2>&1; then
    echo "✅ MySQL: 运行中" | tee -a "$LOG_FILE"
else
    echo "❌ MySQL: 连接失败" | tee -a "$LOG_FILE"
    exit 1
fi

# 检查 HiveMQ
if netstat -ano | grep ":1883" >/dev/null 2>&1; then
    echo "✅ HiveMQ: 运行中 (端口 1883)" | tee -a "$LOG_FILE"
else
    echo "❌ HiveMQ: 端口 1883 未监听" | tee -a "$LOG_FILE"
    exit 1
fi

# 检查 Server
if curl -s "$SERVER_URL/actuator/health" | grep -q "UP"; then
    echo "✅ aluco-server: 运行中" | tee -a "$LOG_FILE"
else
    echo "❌ aluco-server: 未就绪" | tee -a "$LOG_FILE"
    exit 1
fi

echo ""

# 检查模拟器 JAR
if [ ! -f "$SIM_JAR" ]; then
    echo "❌ 模拟器 JAR 不存在: $SIM_JAR"
    echo "请先构建: cd aluco-sim && mvn clean package"
    exit 1
fi

echo "✅ 模拟器 JAR: 存在" | tee -a "$LOG_FILE"
echo ""

# ==========================================
# Level 1: 1,000 设备
# ==========================================
echo "==========================================" | tee -a "$LOG_FILE"
echo "Level 1: 1,000 设备" | tee -a "$LOG_FILE"
echo "==========================================" | tee -a "$LOG_FILE"

# 创建设备（如果尚未创建）
echo "[2/4] 创建设备 TH-0001 ~ TH-1000..." | tee -a "$LOG_FILE"
for i in $(seq -w 1 1000); do
  curl -s -X POST "$SERVER_URL/api/v1/devices" \
    -H "Authorization: Bearer $JWT_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"deviceKey\":\"TH-${i}\",\"name\":\"Device ${i}\",\"siteId\":\"site-01\"}" \
    > /dev/null 2>&1
done
echo "✅ 设备创建完成" | tee -a "$LOG_FILE"
echo ""

# 清空 telemetry 表
echo "[3/4] 清空 telemetry 表..." | tee -a "$LOG_FILE"
mysql -u root -p"$MYSQL_PASSWORD" aluco -e "TRUNCATE TABLE telemetry;" 2>/dev/null
echo "✅ telemetry 表已清空" | tee -a "$LOG_FILE"
echo ""

# 采集基线指标
echo "=== Level 1 基线指标 ===" | tee -a "$LOG_FILE"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_sink_dropped_total" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_e2e_latency_seconds_count" | tee -a "$LOG_FILE"
echo ""

# 启动模拟器
echo "[4/4] 启动模拟器（1,000 设备 × 1Hz）..." | tee -a "$LOG_FILE"
echo "模拟器日志将输出到控制台" | tee -a "$LOG_FILE"
echo "按 Ctrl+C 停止模拟器（建议运行 10-15 分钟）" | tee -a "$LOG_FILE"
echo ""

java -jar "$SIM_JAR" \
  --broker tcp://localhost:1883 \
  --devices 1000 \
  --device-prefix TH- \
  --interval 1s \
  --ramp 10s \
  --metrics temp,humidity \
  --spike-probability 0.002 \
  --seed-devices false

echo ""
echo "Level 1 模拟器已停止" | tee -a "$LOG_FILE"
echo ""

# ==========================================
# 压测后数据采集
# ==========================================
echo "==========================================" | tee -a "$LOG_FILE"
echo "压测后数据采集" | tee -a "$LOG_FILE"
echo "==========================================" | tee -a "$LOG_FILE"

echo "=== 最终指标 ===" | tee -a "$LOG_FILE"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_sink_dropped_total" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_e2e_latency_seconds_count" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_persist_batch_size" | tee -a "$LOG_FILE"
echo ""

echo "=== Telemetry 表统计 ===" | tee -a "$LOG_FILE"
mysql -u root -p"$MYSQL_PASSWORD" aluco -e "SELECT COUNT(*) as total_rows, COUNT(*) / TIMESTAMPDIFF(SECOND, MIN(ts), MAX(ts)) as rows_per_sec FROM telemetry;" 2>/dev/null | tee -a "$LOG_FILE"
mysql -u root -p"$MYSQL_PASSWORD" aluco -e "SELECT ROUND(((data_length + index_length) / 1024 / 1024), 2) AS Size_MB FROM information_schema.TABLES WHERE table_name = 'telemetry';" 2>/dev/null | tee -a "$LOG_FILE"
echo ""

echo "==========================================" | tee -a "$LOG_FILE"
echo "Level 1 完成" | tee -a "$LOG_FILE"
echo "==========================================" | tee -a "$LOG_FILE"
echo ""

# 询问是否继续 Level 2
echo "是否继续 Level 2（5,000 设备）？(y/n)"
read -r CONTINUE

if [ "$CONTINUE" != "y" ]; then
    echo "压测结束" | tee -a "$LOG_FILE"
    exit 0
fi

# ==========================================
# Level 2: 5,000 设备
# ==========================================
echo "==========================================" | tee -a "$LOG_FILE"
echo "Level 2: 5,000 设备" | tee -a "$LOG_FILE"
echo "==========================================" | tee -a "$LOG_FILE"

# 创建设备 TH-1001 ~ TH-5000
echo "[1/4] 创建设备 TH-1001 ~ TH-5000..." | tee -a "$LOG_FILE"
for i in $(seq -w 1001 5000); do
  curl -s -X POST "$SERVER_URL/api/v1/devices" \
    -H "Authorization: Bearer $JWT_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"deviceKey\":\"TH-${i}\",\"name\":\"Device ${i}\",\"siteId\":\"site-01\"}" \
    > /dev/null 2>&1
done
echo "✅ 设备创建完成" | tee -a "$LOG_FILE"
echo ""

# 清空 telemetry 表
echo "[2/4] 清空 telemetry 表..." | tee -a "$LOG_FILE"
mysql -u root -p"$MYSQL_PASSWORD" aluco -e "TRUNCATE TABLE telemetry;" 2>/dev/null
echo "✅ telemetry 表已清空" | tee -a "$LOG_FILE"
echo ""

# 采集基线指标
echo "=== Level 2 基线指标 ===" | tee -a "$LOG_FILE"
echo "时间: $(date '+%Y-%m-%d %H:%M:%S')" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_sink_dropped_total" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_e2e_latency_seconds_count" | tee -a "$LOG_FILE"
echo ""

# 启动模拟器
echo "[3/4] 启动模拟器（5,000 设备 × 1Hz）..." | tee -a "$LOG_FILE"
java -jar "$SIM_JAR" \
  --broker tcp://localhost:1883 \
  --devices 5000 \
  --device-prefix TH- \
  --interval 1s \
  --ramp 60s \
  --metrics temp,humidity \
  --spike-probability 0.002 \
  --seed-devices false

echo ""
echo "Level 2 模拟器已停止" | tee -a "$LOG_FILE"
echo ""

# 压测后数据采集
echo "=== Level 2 最终指标 ===" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_sink_dropped_total" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_e2e_latency_seconds_count" | tee -a "$LOG_FILE"
mysql -u root -p"$MYSQL_PASSWORD" aluco -e "SELECT COUNT(*) as total_rows FROM telemetry;" 2>/dev/null | tee -a "$LOG_FILE"
echo ""

echo "==========================================" | tee -a "$LOG_FILE"
echo "Level 2 完成" | tee -a "$LOG_FILE"
echo "==========================================" | tee -a "$LOG_FILE"
echo ""

# 询问是否继续 Level 3
echo "是否继续 Level 3（10,000 设备）？(y/n)"
read -r CONTINUE

if [ "$CONTINUE" != "y" ]; then
    echo "压测结束" | tee -a "$LOG_FILE"
    exit 0
fi

# ==========================================
# Level 3: 10,000 设备
# ==========================================
echo "==========================================" | tee -a "$LOG_FILE"
echo "Level 3: 10,000 设备" | tee -a "$LOG_FILE"
echo "==========================================" | tee -a "$LOG_FILE"

# 创建设备 TH-5001 ~ TH-10000
echo "[1/4] 创建设备 TH-5001 ~ TH-10000..." | tee -a "$LOG_FILE"
for i in $(seq -w 5001 10000); do
  curl -s -X POST "$SERVER_URL/api/v1/devices" \
    -H "Authorization: Bearer $JWT_TOKEN" \
    -H "Content-Type: application/json" \
    -d "{\"deviceKey\":\"TH-${i}\",\"name\":\"Device ${i}\",\"siteId\":\"site-01\"}" \
    > /dev/null 2>&1
done
echo "✅ 设备创建完成" | tee -a "$LOG_FILE"
echo ""

# 清空 telemetry 表
echo "[2/4] 清空 telemetry 表..." | tee -a "$LOG_FILE"
mysql -u root -p"$MYSQL_PASSWORD" aluco -e "TRUNCATE TABLE telemetry;" 2>/dev/null
echo "✅ telemetry 表已清空" | tee -a "$LOG_FILE"
echo ""

# 采集基线指标
echo "=== Level 3 基线指标 ===" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_sink_dropped_total" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_e2e_latency_seconds_count" | tee -a "$LOG_FILE"
echo ""

# 启动模拟器
echo "[3/4] 启动模拟器（10,000 设备 × 1Hz）..." | tee -a "$LOG_FILE"
java -jar "$SIM_JAR" \
  --broker tcp://localhost:1883 \
  --devices 10000 \
  --device-prefix TH- \
  --interval 1s \
  --ramp 120s \
  --metrics temp,humidity \
  --spike-probability 0.002 \
  --seed-devices false

echo ""
echo "Level 3 模拟器已停止" | tee -a "$LOG_FILE"
echo ""

# 最终数据采集
echo "=== Level 3 最终指标 ===" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_sink_dropped_total" | tee -a "$LOG_FILE"
curl -s "$SERVER_URL/actuator/prometheus" | grep "aluco_e2e_latency_seconds_count" | tee -a "$LOG_FILE"
mysql -u root -p"$MYSQL_PASSWORD" aluco -e "SELECT COUNT(*) as total_rows FROM telemetry;" 2>/dev/null | tee -a "$LOG_FILE"
echo ""

echo "==========================================" | tee -a "$LOG_FILE"
echo "完整阶梯压测完成！" | tee -a "$LOG_FILE"
echo "结束时间: $(date)" | tee -a "$LOG_FILE"
echo "==========================================" | tee -a "$LOG_FILE"
echo ""
echo "请将数据填入: $REPORT_FILE"
