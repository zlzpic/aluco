<#
.SYNOPSIS
    Aluco v2 模拟器快速启动脚本 (PowerShell版)
.DESCRIPTION
    提供预定义的压测级别和自定义模式
.PARAMETER Level
    压测级别: 1, 2, 3, level1, level2, level3, custom
.PARAMETER Devices
    自定义设备数（仅custom模式）
.PARAMETER Interval
    自定义上报间隔（仅custom模式）
.PARAMETER Ramp
    自定义Ramp时间（仅custom模式）
.EXAMPLE
    .\run-sim.ps1 -Level 2
    # 启动 Level 2 (5,000 设备)
.EXAMPLE
    .\run-sim.ps1 -Level custom -Devices 8000 -Interval 1s -Ramp 90s
    # 启动自定义压测
#>

param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("1", "2", "3", "level1", "level2", "level3", "custom")]
    [string]$Level,

    [Parameter(Mandatory=$false)]
    [int]$Devices,

    [Parameter(Mandatory=$false)]
    [string]$Interval = "1s",

    [Parameter(Mandatory=$false)]
    [string]$Ramp
)

# Fix encoding for Chinese characters
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

# 配置
$JarPath = Join-Path $PSScriptRoot "aluco-sim-1.0.0.jar"
$Broker = "tcp://localhost:1883"
$Metrics = "temp,humidity"
$SpikeProb = 0.002
$SeedDevices = $true

# 检查jar文件
if (-not (Test-Path $JarPath)) {
    Write-Error "找不到 $JarPath"
    Write-Error "请确保 aluco-sim-1.0.0.jar 与本脚本在同一目录"
    exit 1
}

# 定义压测级别配置
$configs = @{
    "level1" = @{ Devices = 1000; Ramp = "10s"; Description = "Level 1 (1,000 设备)" }
    "level2" = @{ Devices = 5000; Ramp = "60s"; Description = "Level 2 (5,000 设备)" }
    "level3" = @{ Devices = 10000; Ramp = "120s"; Description = "Level 3 (10,000 设备)" }
}

# 构建启动参数
function Build-Args {
    param($Config)

    $args = @(
        "--broker", $Broker,
        "--devices", $Config.Devices,
        "--interval", $Interval,
        "--ramp", $Config.Ramp,
        "--metrics", $Metrics,
        "--spike-probability", $SpikeProb
    )

    # --seed-devices 默认为 true，无需显式传入
    return $args
}

# 执行
try {
    switch ($Level.ToLower()) {
        {$_ -in @("1", "level1")} {
            $config = $configs["level1"]
            Write-Host "`n[$($config.Description)] 启动压测..." -ForegroundColor Cyan
            Write-Host "  设备数: $($config.Devices)"
            Write-Host "  频率: $Interval"
            Write-Host "  Ramp: $($config.Ramp)"
            Write-Host ""
            & java -jar $JarPath @(Build-Args $config)
        }

        {$_ -in @("2", "level2")} {
            $config = $configs["level2"]
            Write-Host "`n[$($config.Description)] 启动压测..." -ForegroundColor Cyan
            Write-Host "  设备数: $($config.Devices)"
            Write-Host "  频率: $Interval"
            Write-Host "  Ramp: $($config.Ramp)"
            Write-Host ""
            & java -jar $JarPath @(Build-Args $config)
        }

        {$_ -in @("3", "level3")} {
            $config = $configs["level3"]
            Write-Host "`n[$($config.Description)] 启动压测..." -ForegroundColor Cyan
            Write-Host "  设备数: $($config.Devices)"
            Write-Host "  频率: $Interval"
            Write-Host "  Ramp: $($config.Ramp)"
            Write-Host ""
            & java -jar $JarPath @(Build-Args $config)
        }

        "custom" {
            if (-not $Devices) {
                $Devices = Read-Host "请输入设备数"
            }
            if (-not $Ramp) {
                $Ramp = Read-Host "请输入Ramp时间 (如 60s)"
            }

            $config = @{ Devices = $Devices; Ramp = $Ramp }
            Write-Host "`n[自定义模式] 启动压测..." -ForegroundColor Cyan
            Write-Host "  设备数: $Devices"
            Write-Host "  频率: $Interval"
            Write-Host "  Ramp: $Ramp"
            Write-Host ""
            & java -jar $JarPath @(Build-Args $config)
        }
    }
} catch {
    Write-Error "启动失败: $($_.Exception.Message)"
    exit 1
}
