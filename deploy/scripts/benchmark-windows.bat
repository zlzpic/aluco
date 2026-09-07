@echo off
REM ==========================================
REM Aluco v2 Baseline Benchmark Script (Windows)
REM ==========================================
REM 用途：轻量压测脚本，自动检查环境、执行阶梯压测、记录数据
REM 版本：1.0
REM 日期：2026-08-06
REM 依赖：Java 21, Maven, MySQL 8.4, HiveMQ CE

setlocal enabledelayedexpansion

echo ==========================================
echo Aluco v2 Baseline Benchmark (Windows)
echo ==========================================
echo.

REM 颜色定义
set "GREEN=[92m"
set "YELLOW=[93m"
set "RED=[91m"
set "NC=[0m"

REM 配置参数
set "SERVER_URL=http://localhost:8080"
set "PROMETHEUS_URL=http://localhost:9090"
set "MQTT_BROKER=tcp://localhost:1883"
set "SIM_JAR=aluco-sim-1.0.0.jar"
set "REPORT_FILE=%~dp0..\docs\benchmarks\v2-baseline-windows.md"
set "LOG_DIR=%~dp0..\docs\benchmarks\logs"
set "CHECKPOINT_DIR=%~dp0..\docs\benchmarks\checkpoints"

REM 创建日志目录
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"
if not exist "%CHECKPOINT_DIR%" mkdir "%CHECKPOINT_DIR%"

REM 时间戳
for /f "tokens=1-4 delims=/: " %%a in ('echo %time% %date%') do (
    set "TIMESTAMP=%%a-%%b-%%c_%%d"
)

set "LOG_FILE=%LOG_DIR%\benchmark-%TIMESTAMP%.log"

REM ==========================================
REM 函数：记录日志
REM ==========================================
:log
echo %* | tee -a "%LOG_FILE%"
goto :eof

REM ==========================================
REM 函数：检查服务
REM ==========================================
:check_services
call :log "========================================"
call :log "Step 1: Checking services..."
call :log "========================================"

REM 检查 Java
java -version >nul 2>&1
if errorlevel 1 (
    call :log "%RED%[ERROR] Java not found%NC%"
    exit /b 1
)
for /f "tokens=2 delims=." %%a in ('java -version 2^>^&1') do set "JAVA_VER=%%a"
call :log "%GREEN%[OK]%NC% Java !JAVA_VER! detected"

REM 检查 MySQL
tasklist | findstr /i "mysqld.exe" >nul
if errorlevel 1 (
    call :log "%RED%[ERROR] MySQL not running%NC%"
    exit /b 1
)
call :log "%GREEN%[OK]%NC% MySQL is running"

REM 检查 HiveMQ
tasklist | findstr /i "hivemq" >nul
if errorlevel 1 (
    call :log "%YELLOW%[WARN]%NC% HiveMQ not detected, trying to connect..."
    timeout /t 2 >nul
    netstat -ano | findstr ":1883" >nul
    if errorlevel 1 (
        call :log "%RED%[ERROR] HiveMQ port 1883 not listening%NC%"
        call :log "Please start HiveMQ first: run.bat"
        exit /b 1
    )
)
call :log "%GREEN%[OK]%NC% HiveMQ detected"

REM 检查 server
curl -s "%SERVER_URL%/actuator/health" >nul 2>&1
if errorlevel 1 (
    call :log "%YELLOW%[WARN]%NC% aluco-server not responding at %SERVER_URL%"
    call :log "Please start aluco-server first"
    exit /b 1
)
call :log "%GREEN%[OK]%NC% aluco-server is running"

call :log ""
exit /b 0

REM ==========================================
REM 函数：采集指标
REM ==========================================
:collect_metrics
set "LEVEL=%~1"
set "METRICS_FILE=%LOG_DIR%\metrics-level%LEVEL%.csv"

echo timestamp,sink_dropped,e2e_p50,e2e_p99,batch_size_avg,ws_sessions > "!METRICS_FILE!"

call :log "Collecting metrics for Level %LEVEL% (Ctrl+C to stop)..."
call :log "Saving to: !METRICS_FILE!"
call :log ""

:metrics_loop
for /f "tokens=1-3 delims=," %%a in ('powershell -Command "Get-Date -Format 'yyyy-MM-dd HH:mm:ss'"') do (
    set "TS=%%a %%b"
)

REM 采集 sink.dropped
for /f "tokens=2 delims= " %%a in ('curl -s %SERVER_URL%/actuator/prometheus ^| findstr "aluco_sink_dropped_total"') do (
    set "SINK_DROPPED=%%a"
)

REM 采集 e2e p50/p99
for /f "tokens=2 delims= " %%a in ('curl -s "%PROMETHEUS_URL%/api/v1/query?query=histogram_quantile(0.50,sum(rate(aluco_e2e_latency_seconds_bucket[5m]))by(le))" ^| findstr "value"') do (
    set "E2E_P50=%%a"
)
for /f "tokens=2 delims= " %%a in ('curl -s "%PROMETHEUS_URL%/api/v1/query?query=histogram_quantile(0.99,sum(rate(aluco_e2e_latency_seconds_bucket[5m]))by(le))" ^| findstr "value"') do (
    set "E2E_P99=%%a"
)

REM 采集批大小均值
for /f "tokens=2 delims= " %%a in ('curl -s %SERVER_URL%/actuator/prometheus ^| findstr "aluco_persist_batch_size_sum"') do (
    set "BATCH_SUM=%%a"
)
for /f "tokens=2 delims= " %%a in ('curl -s %SERVER_URL%/actuator/prometheus ^| findstr "aluco_persist_batch_size_count"') do (
    set "BATCH_COUNT=%%a"
)

REM 采集 WebSocket 会话数
for /f "tokens=2 delims= " %%a in ('curl -s %SERVER_URL%/actuator/prometheus ^| findstr "aluco_ws_sessions"') do (
    set "WS_SESSIONS=%%a"
)

echo !TS: =!,!SINK_DROPPED!,!E2E_P50!,!E2E_P99!,!BATCH_SUM!,!WS_SESSIONS! >> "!METRICS_FILE!"

echo [!TS: =!] sink.dropped=!SINK_DROPPED! e2e_p50=!E2E_P50! e2e_p99=!E2E_P99! ws_sessions=!WS_SESSIONS!

timeout /t 30 >nul
goto :metrics_loop

REM ==========================================
REM 主流程
REM ==========================================
:main
call :log "Starting Aluco v2 Baseline Benchmark..."
call :log "Log file: %LOG_FILE%"
call :log ""

REM 步骤1：检查环境
call :check_services
if errorlevel 1 (
    call :log "%RED%Environment check failed. Exiting.%NC%"
    exit /b 1
)

REM 步骤2：选择压测档位
echo.
echo ==========================================
echo Select Benchmark Level:
echo ==========================================
echo 1. Level 1: 1,000 devices (10 min)
echo 2. Level 2: 5,000 devices (10 min)
echo 3. Level 3: 10,000 devices (10 min)
echo 4. Full benchmark (Level 1 + 2 + 3)
echo 5. Exit
echo.
set /p "CHOICE=Enter choice (1-5): "

if "%CHOICE%"=="1" goto level1
if "%CHOICE%"=="2" goto level2
if "%CHOICE%"=="3" goto level3
if "%CHOICE%"=="4" goto full_benchmark
if "%CHOICE%"=="5" exit /b 0

call :log "%RED%Invalid choice. Exiting.%NC%"
exit /b 1

REM ==========================================
REM Level 1: 1,000 设备
REM ==========================================
:level1
call :log "========================================"
call :log "Level 1: 1,000 devices (10 min steady state)"
call :log "========================================"

call :log "Preparing devices (TH-0001 ~ TH-1000)..."
powershell -ExecutionPolicy Bypass -File "%~dp0create-devices.ps1" -Start 1 -End 1000 -Jwt "%JWT_TOKEN%" >nul 2>&1
if errorlevel 1 (
    call :log "%YELLOW%[WARN]%NC% Failed to create devices via API, continuing anyway..."
)

call :log "Starting simulator..."
start /b java -jar "%SIM_JAR%" ^
  --broker %MQTT_BROKER% ^
  --devices 1000 ^
  --device-prefix TH- ^
  --interval 1s ^
  --ramp 10s ^
  --metrics temp,humidity ^
  --spike-probability 0.002 ^
  --seed-devices false

call :log "Simulator started (PID: !errorlevel!)"
call :log "Waiting 5 min for warm-up..."
timeout /t 300 >nul

call :log "Starting metrics collection (press Ctrl+C to stop after 10 min)..."
call :collect_metrics 1

call :log "Level 1 complete. Check %REPORT_FILE% for data entry."
pause
exit /b 0

REM ==========================================
REM Level 2: 5,000 设备
REM ==========================================
:level2
call :log "========================================"
call :log "Level 2: 5,000 devices (10 min steady state)"
call :log "========================================"

call :log "Preparing devices (TH-1001 ~ TH-5000)..."
powershell -ExecutionPolicy Bypass -File "%~dp0create-devices.ps1" -Start 1001 -End 5000 -Jwt "%JWT_TOKEN%" >nul 2>&1

call :log "Starting simulator..."
start /b java -jar "%SIM_JAR%" ^
  --broker %MQTT_BROKER% ^
  --devices 5000 ^
  --device-prefix TH- ^
  --interval 1s ^
  --ramp 60s ^
  --metrics temp,humidity ^
  --spike-probability 0.002 ^
  --seed-devices false

call :log "Waiting 5 min for warm-up..."
timeout /t 300 >nul

call :log "Starting metrics collection..."
call :collect_metrics 2

call :log "Level 2 complete."
pause
exit /b 0

REM ==========================================
REM Level 3: 10,000 设备
REM ==========================================
:level3
call :log "========================================"
call :log "Level 3: 10,000 devices (10 min steady state)"
call :log "========================================"

call :log "Preparing devices (TH-5001 ~ TH-10000)..."
powershell -ExecutionPolicy Bypass -File "%~dp0create-devices.ps1" -Start 5001 -End 10000 -Jwt "%JWT_TOKEN%" >nul 2>&1

call :log "Starting simulator..."
start /b java -jar "%SIM_JAR%" ^
  --broker %MQTT_BROKER% ^
  --devices 10000 ^
  --device-prefix TH- ^
  --interval 1s ^
  --ramp 120s ^
  --metrics temp,humidity ^
  --spike-probability 0.002 ^
  --seed-devices false

call :log "Waiting 5 min for warm-up..."
timeout /t 300 >nul

call :log "Starting metrics collection..."
call :collect_metrics 3

call :log "Level 3 complete."
pause
exit /b 0

REM ==========================================
REM 全量压测（Level 1 + 2 + 3）
REM ==========================================
:full_benchmark
call :log "========================================"
call :log "Full Benchmark: Level 1 + 2 + 3"
call :log "========================================"

call :level1
call :level2
call :level3

call :log "========================================"
call :log "Full benchmark complete!"
call :log "========================================"
call :log "Please fill in %REPORT_FILE% with collected data."
pause
exit /b 0
