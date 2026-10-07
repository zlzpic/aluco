@echo off
REM Aluco v2 模拟器快速启动脚本
REM 用法: run-sim.bat [level|custom]

setlocal enabledelayedexpansion

set JAR=%~dp0aluco-sim-1.0.0.jar
set BROKER=tcp://localhost:1883

REM 检查jar文件是否存在
if not exist "%JAR%" (
    echo 错误: 找不到 %JAR%
    echo 请确保 aluco-sim-1.0.0.jar 与本脚本在同一目录
    pause
    exit /b 1
)

REM 根据参数选择配置
if "%~1"=="" goto :show_help
if "%~1"=="1" goto :level1
if "%~1"=="2" goto :level2
if "%~1"=="3" goto :level3
if "%~1"=="level1" goto :level1
if "%~1"=="level2" goto :level2
if "%~1"=="level3" goto :level3
goto :custom

:show_help
echo.
echo  Aluco v2 模拟器快速启动脚本
echo.
echo  用法:
echo    %~nx0 [level]
echo.
echo  预定义级别:
echo    1 / level1    1,000 设备 (1k/s 写入速率)
echo    2 / level2    5,000 设备 (10k/s 写入速率)
echo    3 / level3   10,000 设备 (20k/s 写入速率)
echo.
echo  自定义:
echo    %~nx0 custom   自定义参数（会提示输入）
echo.
goto :eof

:level1
echo [Level 1] 启动 1,000 设备压测...
echo 设备数: 1,000
echo 频率: 1Hz
echo Ramp: 10s
echo.
java -jar "%JAR%" ^
    --broker %BROKER% ^
    --devices 1000 ^
    --interval 1s ^
    --ramp 10s ^
    --metrics temp,humidity ^
    --spike-probability 0.002 ^
    --seed-devices false
goto :eof

:level2
echo [Level 2] 启动 5,000 设备压测...
echo 设备数: 5,000
echo 频率: 1Hz
echo Ramp: 60s
echo.
java -jar "%JAR%" ^
    --broker %BROKER% ^
    --devices 5000 ^
    --interval 1s ^
    --ramp 60s ^
    --metrics temp,humidity ^
    --spike-probability 0.002 ^
    --seed-devices false
goto :eof

:level3
echo [Level 3] 启动 10,000 设备压测...
echo 设备数: 10,000
echo 频率: 1Hz
echo Ramp: 120s
echo.
java -jar "%JAR%" ^
    --broker %BROKER% ^
    --devices 10000 ^
    --interval 1s ^
    --ramp 120s ^
    --metrics temp,humidity ^
    --spike-probability 0.002 ^
    --seed-devices false
goto :eof

:custom
echo [自定义模式] 请输入参数:
set /p DEVICES="设备数 (如: 5000): "
set /p INTERVAL="上报间隔 (如: 1s): "
set /p RAMP="Ramp时间 (如: 60s): "

echo.
echo 启动自定义压测...
java -jar "%JAR%" ^
    --broker %BROKER% ^
    --devices !DEVICES! ^
    --interval !INTERVAL! ^
    --ramp !RAMP! ^
    --metrics temp,humidity ^
    --spike-probability 0.002 ^
    --seed-devices false
goto :eof

endlocal
