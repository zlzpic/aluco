# Aluco v2 Level 1 Data Collection
param([int]$SampleCount = 10)

$JWT = "eyJhbGciOiJIUzM4NCJ9.eyJzdWIiOiJhZG1pbiIsImlhdCI6MTc4NjAwMTcxNywiZXhwIjoxNzg2MDg4MTE3fQ.1iFrPgdq56Z7eztc7ltBSCVlhBTxSH-s7Lp1fSfd1aAg7ZE4ObMIqzATUg9r5ez0"
$BaseUrl = "http://localhost:8080/api/v1"

Write-Host "=== Aluco v2 Level 1 Data Collection ===" -ForegroundColor Cyan

# 1. Prometheus metrics
Write-Host "`n[1/5] Prometheus metrics..." -ForegroundColor Yellow
try {
    $Metrics = Invoke-RestMethod -Uri "http://localhost:8080/actuator/prometheus" -UseBasicParsing
    $Metrics -split "`n" | Select-String "aluco_sink_dropped_total|aluco_e2e_latency" | ForEach-Object { Write-Host "  $_" }
} catch { Write-Host "  Failed: $($_.Exception.Message)" -ForegroundColor Red }

# 2. Table size
Write-Host "`n[2/5] Telemetry table size..." -ForegroundColor Yellow
mysql -u root -p123456 aluco -e "SELECT table_rows, ROUND(data_length/1024/1024,2) AS mb FROM information_schema.tables WHERE table_name='telemetry';" -s -N

# 3. Device status
Write-Host "`n[3/5] Device status (sample $SampleCount)..." -ForegroundColor Yellow
$online = 0; $total = 0
1..$SampleCount | ForEach-Object {
    $key = "TH-{0:D4}" -f $_
    try {
        $s = Invoke-RestMethod "$BaseUrl/devices/$key/state" -Headers @{Authorization="Bearer $JWT"} -UseBasicParsing -ErrorAction Stop
        $total++
        if($s.online){ $online++; Write-Host "  $key : online" -ForegroundColor Green }
        else { Write-Host "  $key : offline" -ForegroundColor Gray }
    } catch { Write-Host "  $key : not found" -ForegroundColor Yellow }
}
Write-Host "  Summary: $online / $total online" -ForegroundColor Cyan

# 4. Rules count
Write-Host "`n[4/5] Alert rules..." -ForegroundColor Yellow
try { 
    $r = Invoke-RestMethod "$BaseUrl/rules?page=0&size=10" -Headers @{Authorization="Bearer $JWT"} -UseBasicParsing
    Write-Host "  Total: $($r.total)" -ForegroundColor Green 
} catch { Write-Host "  Failed: $($_.Exception.Message)" -ForegroundColor Red }

# 5. Alerts
Write-Host "`n[5/5] FIRING alerts..." -ForegroundColor Yellow
try { 
    $a = Invoke-RestMethod "$BaseUrl/alerts?status=FIRING&page=0&size=10" -Headers @{Authorization="Bearer $JWT"} -UseBasicParsing
    Write-Host "  Total: $($a.total)" -ForegroundColor Green 
} catch { Write-Host "  Failed: $($_.Exception.Message)" -ForegroundColor Red }

Write-Host "`n=== Collection Complete ===" -ForegroundColor Cyan
