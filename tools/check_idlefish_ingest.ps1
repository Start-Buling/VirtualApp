param(
    [string]$DeviceSerial = "6H5PY5GEZ9QGJJO7",
    [int]$Port = 18080
)

$ErrorActionPreference = "Continue"

Write-Host "Checking PC server..."
$healthUrl = "http://127.0.0.1:$Port/health"
try {
    $health = Invoke-WebRequest -UseBasicParsing $healthUrl -TimeoutSec 3
    Write-Host "OK server health:" $health.Content
} catch {
    Write-Host "FAIL server is not reachable at $healthUrl"
    Write-Host "Start it with: powershell -ExecutionPolicy Bypass -File .\tools\start_idlefish_ingest_server.ps1"
}

Write-Host ""
Write-Host "Checking adb reverse..."
$reverseList = adb -s $DeviceSerial reverse --list 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "FAIL adb reverse check failed:"
    Write-Host $reverseList
    exit 1
}

$expected = "tcp:$Port tcp:$Port"
if ($reverseList -match [regex]::Escape($expected)) {
    Write-Host "OK adb reverse contains $expected"
} else {
    Write-Host "FAIL adb reverse is missing $expected"
    Write-Host "Run: adb -s $DeviceSerial reverse tcp:$Port tcp:$Port"
    Write-Host "Current reverse list:"
    Write-Host $reverseList
}

Write-Host ""
Write-Host "Recent accepted events:"
try {
    $recentUrl = "http://127.0.0.1:$Port/api/v1/events/recent?limit=3"
    (Invoke-WebRequest -UseBasicParsing $recentUrl -TimeoutSec 3).Content
} catch {
    Write-Host "Cannot read recent events because server is not reachable or not updated."
}
