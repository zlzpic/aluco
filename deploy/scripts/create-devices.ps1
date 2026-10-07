<#
.SYNOPSIS
    Aluco 设备批量创建脚本（压测用）
.DESCRIPTION
    批量创建设备并注册到 aluco-server REST API
.PARAMETER Start
    起始设备编号（从1开始）
.PARAMETER End
    结束设备编号
.PARAMETER Jwt
    JWT Token（必填，用于鉴权）
.PARAMETER SiteId
    站点ID（默认：site-01）
.PARAMETER ServerUrl
    Server 地址（默认：http://localhost:8080）
.PARAMETER Prefix
    设备Key前缀（默认：TH-）
.PARAMETER BatchSize
    批量创建批次大小（默认：50，避免请求过快）
.EXAMPLE
    .\create-devices.ps1 -Start 1 -End 1000 -Jwt "eyJhbGc..."
    .\create-devices.ps1 -Start 1001 -End 5000 -Jwt "eyJhbGc..." -BatchSize 100
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [int]$Start,

    [Parameter(Mandatory=$true)]
    [int]$End,

    [Parameter(Mandatory=$true)]
    [string]$Jwt,

    [string]$SiteId = "site-01",

    [string]$ServerUrl = "http://localhost:8080",

    [string]$Prefix = "TH-",

    [int]$BatchSize = 50
)

# 验证参数
if ($Start -gt $End) {
    Write-Error "Start ($Start) cannot be greater than End ($End)"
    exit 1
}

$total = $End - $Start + 1
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Aluco Device Creator (Batch Mode)" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Devices: $total ($Prefix$($Start.ToString('D4')) ~ $Prefix$($End.ToString('D4')))" -ForegroundColor Green
Write-Host "Server: $ServerUrl" -ForegroundColor Green
Write-Host "Batch size: $BatchSize" -ForegroundColor Green
Write-Host ""

# 检查 JWT 有效性
try {
    $headers = @{
        "Authorization" = "Bearer $Jwt"
    }
    $response = Invoke-RestMethod -Uri "$ServerUrl/api/v1/devices" -Headers $headers -Method Get -ErrorAction Stop
    Write-Host "[OK] JWT valid, connected to server" -ForegroundColor Green
} catch {
    Write-Error "Failed to connect to server or invalid JWT: $($_.Exception.Message)"
    exit 1
}

# 统计变量
$successCount = 0
$failCount = 0
$skipCount = 0
$startTime = Get-Date

# 批量创建
Write-Host ""
Write-Host "Starting batch creation..." -ForegroundColor Cyan
Write-Host ""

for ($i = $Start; $i -le $End; $i++) {
    $deviceKey = "{0}{1:D4}" -f $Prefix, $i
    $deviceName = "Device $deviceKey"

    # 构建请求体
    $body = @{
        deviceKey = $deviceKey
        name = $deviceName
        siteId = $SiteId
    } | ConvertTo-Json

    try {
        $response = Invoke-RestMethod `
            -Uri "$ServerUrl/api/v1/devices" `
            -Headers $headers `
            -Method Post `
            -ContentType "application/json" `
            -Body $body `
            -ErrorAction Stop

        $successCount++
        Write-Progress -Activity "Creating devices" -Status "$deviceKey (Success: $successCount, Fail: $failCount)" -PercentComplete (($i - $Start + 1) / $total * 100)

        # 每 BatchSize 个暂停一次，避免请求过快
        if ($i % $BatchSize -eq 0) {
            Start-Sleep -Milliseconds 500
        }

    } catch {
        $errorMsg = $_.Exception.Message

        # 检查是否是设备已存在（409 Conflict）
        if ($errorMsg -match "409" -or $errorMsg -match "already exists") {
            $skipCount++
        } else {
            $failCount++
            Write-Warning "Failed to create $deviceKey : $errorMsg"
        }
    }

    # 每 100 个设备打印一次进度
    if ($i % 100 -eq 0) {
        $elapsed = (Get-Date) - $startTime
        $rate = [math]::Round(($i - $Start + 1) / $elapsed.TotalSeconds, 2)
        $remaining = $total - ($i - $Start + 1)
        $eta = if ($rate -gt 0) { [math]::Round($remaining / $rate, 0) } else { "N/A" }

        Write-Host ""
        Write-Host "Progress: $i / $End | Rate: $rate devices/s | ETA: $eta s" -ForegroundColor Yellow
    }
}

# 完成统计
$elapsed = (Get-Date) - $startTime
Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Batch creation complete!" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Total:      $total" -ForegroundColor Green
Write-Host "Success:    $successCount" -ForegroundColor Green
Write-Host "Skipped:    $skipCount (already exists)" -ForegroundColor Yellow
Write-Host "Failed:     $failCount" -ForegroundColor $(if($failCount -gt 0){"Red"}else{"Green"})
Write-Host "Elapsed:    $($elapsed.ToString('mm\:ss'))" -ForegroundColor Green
Write-Host "Rate:       $([math]::Round($total / $elapsed.TotalSeconds, 2)) devices/s" -ForegroundColor Green
Write-Host ""

# 导出结果到 CSV
$logDir = Join-Path $PSScriptRoot "..\docs\benchmarks\logs"
if (-not (Test-Path $logDir)) {
    New-Item -ItemType Directory -Path $logDir -Force | Out-Null
}

$logFile = Join-Path $logDir "device-creation-$(Get-Date -Format 'yyyyMMdd_HHmmss').csv"
[PSCustomObject]@{
    timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    startDevice = $Start
    endDevice = $End
    total = $total
    success = $successCount
    skipped = $skipCount
    failed = $failCount
    elapsedSeconds = [math]::Round($elapsed.TotalSeconds, 2)
    ratePerSecond = [math]::Round($total / $elapsed.TotalSeconds, 2)
} | Export-Csv -Path $logFile -NoTypeInformation

Write-Host "Log saved to: $logFile" -ForegroundColor Gray

if ($failCount -gt 0) {
    Write-Host ""
    Write-Warning "Some devices failed to create. Check log for details."
    exit 1
}

exit 0
