param(
    [string]$Serial = "",
    [string]$IngestUrl = "http://127.0.0.1:18080/api/v1/ingest/events",
    [string]$HealthUrl = "http://127.0.0.1:18080/health",
    [string]$RecentUrl = "http://127.0.0.1:18080/api/v1/events/recent?limit=3",
    [string]$OutDir = "page-probe",
    [string]$DeviceSecret = "",
    [switch]$AllowPartial,
    [switch]$OpenInVirtualApp,
    [int]$VirtualUserId = 0,
    [int]$OpenWaitSeconds = 12
)

$ErrorActionPreference = "Stop"

function Get-FirstDevice {
    $lines = & adb devices
    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device$") {
            return $Matches[1]
        }
    }
    throw "No adb device in 'device' state."
}

function Get-PortFromUrl {
    param([string]$Url)
    $uri = [Uri]$Url
    if ($uri.Port -gt 0) {
        return $uri.Port
    }
    if ($uri.Scheme -eq "https") {
        return 443
    }
    return 80
}

function Get-JsonSummary {
    param([object]$Result)

    $texts = @()
    if ($Result.uiTexts) {
        $texts = @($Result.uiTexts | Select-Object -First 12)
    }

    return [ordered]@{
        currentActivity = $Result.currentActivity
        uiDumpStatus = $Result.uiDumpStatus
        uiTextCount = $Result.uiTextCount
        urls = $Result.urls
        firstTexts = $texts
        eventPath = $Result.eventPath
    }
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$probeScript = Join-Path $repoRoot "tools\idlefish_page_probe.ps1"
$uploadScript = Join-Path $repoRoot "tools\upload_idlefish_event.ps1"
$ocrScript = Join-Path $repoRoot "tools\ocr_idlefish_screenshot_event.ps1"
$openScript = Join-Path $repoRoot "tools\open_idlefish_service_points_in_va.ps1"

if (-not (Test-Path $probeScript)) {
    throw "Probe script not found: $probeScript"
}
if (-not (Test-Path $uploadScript)) {
    throw "Upload script not found: $uploadScript"
}
if (-not (Test-Path $ocrScript)) {
    throw "OCR script not found: $ocrScript"
}
if ($OpenInVirtualApp -and -not (Test-Path $openScript)) {
    throw "VirtualApp open script not found: $openScript"
}

if (-not $Serial) {
    $Serial = Get-FirstDevice
}

$port = Get-PortFromUrl -Url $IngestUrl
Write-Host "Device: $Serial"
Write-Host "Ingest: $IngestUrl"

Write-Host "Checking ingest server..."
try {
    $health = Invoke-WebRequest -UseBasicParsing $HealthUrl -TimeoutSec 3
    Write-Host "OK server:" $health.Content
} catch {
    throw "Ingest server is not reachable at $HealthUrl. Start it first: powershell -ExecutionPolicy Bypass -File .\tools\start_idlefish_ingest_server.ps1"
}

Write-Host "Ensuring adb reverse tcp:$port tcp:$port..."
& adb -s $Serial reverse "tcp:$port" "tcp:$port" | Out-Null

if ($OpenInVirtualApp) {
    Write-Host "Opening service-score page inside VirtualApp user $VirtualUserId..."
    & powershell -ExecutionPolicy Bypass -File $openScript -Serial $Serial -UserId $VirtualUserId -UseFleamarketWebView
    Write-Host "Waiting $OpenWaitSeconds seconds for H5 load..."
    Start-Sleep -Seconds $OpenWaitSeconds
}

Write-Host "Capturing current foreground page..."
$probeOutput = & powershell -ExecutionPolicy Bypass -File $probeScript -Serial $Serial -OutDir $OutDir
$result = ($probeOutput -join "`n") | ConvertFrom-Json

Write-Host "Capture summary:"
Get-JsonSummary -Result $result | ConvertTo-Json -Depth 8

if (-not $result.eventPath) {
    throw "Probe did not produce an eventPath."
}

$event = Get-Content -Raw -Encoding UTF8 -Path $result.eventPath | ConvertFrom-Json
$serviceScore = $event.metrics.service_score
if (-not $AllowPartial -and $null -eq $serviceScore) {
    Write-Host "UI text did not expose service_score; trying screenshot OCR fallback..."
    $resultJson = $result.eventPath -replace "-event\.json$", "-result.json"
    if (-not (Test-Path $resultJson)) {
        throw "Probe result JSON not found for OCR fallback: $resultJson"
    }
    $ocrOutput = & powershell -ExecutionPolicy Bypass -File $ocrScript -ResultJson $resultJson -OutDir $OutDir
    $ocrResult = ($ocrOutput -join "`n") | ConvertFrom-Json
    Write-Host "OCR fallback summary:"
    $ocrResult | ConvertTo-Json -Depth 8
    $result.eventPath = $ocrResult.eventPath
    $event = Get-Content -Raw -Encoding UTF8 -Path $result.eventPath | ConvertFrom-Json
    $serviceScore = $event.metrics.service_score
    if ($null -eq $serviceScore) {
        throw "Current page still did not produce service_score after OCR fallback. Event kept locally at: $($result.eventPath)"
    }
}

Write-Host "Uploading event..."
if ($DeviceSecret) {
    $uploadResult = & powershell -ExecutionPolicy Bypass -File $uploadScript -EventPath $result.eventPath -Url $IngestUrl -DeviceSecret $DeviceSecret
} else {
    $uploadResult = & powershell -ExecutionPolicy Bypass -File $uploadScript -EventPath $result.eventPath -Url $IngestUrl
}
$uploadResult

Write-Host "Recent accepted events:"
try {
    (Invoke-WebRequest -UseBasicParsing $RecentUrl -TimeoutSec 3).Content
} catch {
    Write-Host "Upload returned, but recent endpoint could not be read: $($_.Exception.Message)"
}
