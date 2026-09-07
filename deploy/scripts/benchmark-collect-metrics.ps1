# Aluco v2 Benchmark Data Collection Script
# Usage: .\benchmark-collect-metrics.ps1 -Level 2 -DurationSeconds 1800

param(
    [Parameter(Mandatory=$true)]
    [string]$Level,

    [Parameter(Mandatory=$false)]
    [int]$DurationSeconds = 1800,

    [Parameter(Mandatory=$false)]
    [int]$SampleInterval = 60
)

$JWT = "eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJhZG1pbiIsImlhdCI6MTc4NjAwMTcxNywiZXhwIjoxNzg2MDg4MTE3fQ.1iFrPgdq56Z7eztc7ltBSCVlhBTxSH-s7Lp1fSfd1aAg7ZE4ObMIqzATUg9r5ez0"
$BaseUrl = "http://localhost:8080/api/v1"
$OutputDir = "docs/benchmarks/data"
$Timestamp = Get-Date -Format "yyyyMMdd_HHmmss"
$OutputFile = "$OutputDir/level${Level}_metrics_$Timestamp.csv"

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

"timestamp,sink_dropped,e2e_p50_ms,e2e_p99_ms,telemetry_rows,online_devices" | Out-File -FilePath $OutputFile -Encoding UTF8

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  Aluco v2 Baseline - Level $Level Data Collection" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Start: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host "Interval: ${SampleInterval}s"
Write-Host "Duration: $([math]::Round($DurationSeconds/60)) minutes"
Write-Host "Output: $OutputFile"
Write-Host ""

$StartTime = Get-Date
$Samples = [math]::Floor($DurationSeconds / $SampleInterval)
$SampleCount = 0

while ($SampleCount -lt $Samples) {
    $Elapsed = (Get-Date) - $StartTime
    $Remaining = $DurationSeconds - $Elapsed.TotalSeconds

    if ($Remaining -le 0) { break }

    $SampleCount++
    $TimestampStr = Get-Date -Format "yyyy-MM-dd HH:mm:ss"

    Write-Host "[$SampleCount/$Samples] Collecting... ($TimestampStr)" -NoNewline

    try {
        $MetricsResponse = Invoke-RestMethod -Uri "http://localhost:8080/actuator/prometheus" -UseBasicParsing

        $SinkDroppedLine = $MetricsResponse -split "`n" | Select-String "aluco_sink_dropped_total" | Select-Object -First 1
        $SinkDropped = if ($SinkDroppedLine) { ($SinkDroppedLine -replace '.* ([0-9.]+).*','$1').Trim() } else { "N/A" }

        $E2eP50Line = $MetricsResponse -split "`n" | Select-String "aluco_e2e_latency.*quantile=0\.5" | Select-Object -First 1
        $E2eP50 = if ($E2eP50Line) { ($E2eP50Line -replace '.* ([0-9.]+).*','$1').Trim() } else { "N/A" }

        $E2eP99Line = $MetricsResponse -split "`n" | Select-String "aluco_e2e_latency.*quantile=0\.99" | Select-Object -First 1
        $E2eP99 = if ($E2eP99Line) { ($E2eP99Line -replace '.* ([0-9.]+).*','$1').Trim() } else { "N/A" }

        $RowCount = 0
        try {
            $RowCount = (mysql -u root -p123456 aluco -e "SELECT COUNT(*) FROM telemetry;" -s -N 2>$null).Trim()
        } catch {}

        $OnlineCount = 0
        try {
            for ($i = 1; $i -le 10; $i++) {
                $DeviceKey = "TH-{0:D4}" -f $i
                $State = Invoke-RestMethod -Uri "$BaseUrl/devices/$DeviceKey/state" -Headers @{"Authorization"="Bearer $JWT"} -UseBasicParsing -ErrorAction SilentlyContinue
                if ($State.online) { $OnlineCount++ }
            }
        } catch {}

        $CsvLine = "$TimestampStr,$SinkDropped,$E2eP50,$E2eP99,$RowCount,$OnlineCount"
        $CsvLine | Out-File -FilePath $OutputFile -Append -Encoding UTF8

        Write-Host " OK | rows=$RowCount online=$OnlineCount" -ForegroundColor Green

    } catch {
        Write-Host " FAILED: $($_.Exception.Message)" -ForegroundColor Yellow
    }

    Start-Sleep -Seconds $SampleInterval
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "  Collection Complete!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host "End: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host "Output: $OutputFile"
Write-Host ""
