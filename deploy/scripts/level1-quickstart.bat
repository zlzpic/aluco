@echo off
REM ==========================================
REM Aluco v2 Level 1 Quick Start (1,000 devices)
REM ==========================================
REM 用途：快速启动 Level 1 压测（验证流程）
REM 前置：MySQL + HiveMQ + aluco-server 已启动

setlocal enabledelayedexpansion

echo ==========================================
echo Aluco v2 Level 1 Benchmark
echo 1,000 devices x 1Hz x 10 minutes
echo ==========================================
echo.

REM 检查服务
echo [1/4] Checking services...

REM 检查 Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found
    exit /b 1
)
echo [OK] Java detected

REM 检查 MySQL
tasklist | findstr /i "mysqld.exe" >nul
if errorlevel 1 (
    echo [ERROR] MySQL not running
    exit /b 1
)
echo [OK] MySQL running

REM 检查 HiveMQ
netstat -ano | findstr ":1883" >nul
if errorlevel 1 (
    echo [ERROR] HiveMQ port 1883 not listening
    echo Please start HiveMQ: run.bat in D:\Application\hivemq-ce-2026.5\hivemq-ce-2026.5\bin
    exit /b 1
)
echo [OK] HiveMQ listening on 1883

REM 检查 server
curl -s http://localhost:8080/actuator/health >nul 2>&1
if errorlevel 1 (
    echo [ERROR] aluco-server not responding
    echo Please start aluco-server first
    exit /b 1
)
echo [OK] aluco-server running

echo.
echo [2/4] Preparing devices (TH-0001 ~ TH-0001)...

REM 创建设备
powershell -ExecutionPolicy Bypass -File "%~dp0create-devices.ps1" -Start 1 -End 1000 -Jwt "%JWT_TOKEN%"

echo.
echo [3/4] Clearing telemetry table...
mysql -u root -proot aluco -e "TRUNCATE TABLE telemetry;" 2>nul
echo [OK] telemetry table cleared

echo.
echo [4/4] Starting simulator...
echo Devices: 1,000
echo Interval: 1s
echo Ramp: 10s
echo Metrics: temp, humidity
echo.
echo ==========================================
echo Press Ctrl+C to stop the simulator
echo ==========================================
echo.

REM 启动模拟器
java -jar "%~dp0..\..\aluco-sim\target\aluco-sim-1.0.0.jar" ^
  --broker tcp://localhost:1883 ^
  --devices 1000 ^
  --device-prefix TH- ^
  --interval 1s ^
  --ramp 10s ^
  --metrics temp,humidity ^
  --spike-probability 0.002 ^
  --seed-devices false

echo.
echo ==========================================
echo Benchmark stopped
echo ==========================================
echo.
echo Please record metrics in:
echo docs\benchmarks\v2-baseline-windows.md
echo.

pause
