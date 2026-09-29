param(
    [string]$Serial = "",
    [string]$DeviceNo = "device_001",
    [int[]]$VirtualUserIds = @(0, 1, 2),
    [string]$IngestUrl = "",
    [string]$DiscoveryUrl = "",
    [int]$WaitSecondsPerShop = 42,
    [int]$RecentLimit = 10,
    [string]$HostPackage = "com.carlos.multiapp",
    [string]$DeviceSecret = "",
    [switch]$SkipHealthCheck,
    [switch]$NoForceStop
)

$ErrorActionPreference = "Stop"

. "$PSScriptRoot/idlefish_env.ps1"
Import-IdlefishEnv
if (-not $IngestUrl) { $IngestUrl = Get-IdlefishServerUrl '/api/v1/ingest/events' }
if (-not $DiscoveryUrl) { $DiscoveryUrl = Get-IdlefishServerUrl '/api/v1/idlefish/discovery/events' }
if (-not $DeviceSecret) { $DeviceSecret = $env:IDLEFISH_DEVICE_SECRET }

function Get-FirstDevice {
    $lines = & adb devices
    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device$") {
            return $Matches[1]
        }
    }
    throw "No adb device in 'device' state."
}

function Get-BaseUrl([string]$Url) {
    $uri = [System.Uri]$Url
    $port = if ($uri.IsDefaultPort) { "" } else { ":$($uri.Port)" }
    return "$($uri.Scheme)://$($uri.Host)$port"
}

if (-not $Serial) {
    $Serial = Get-FirstDevice
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$singleScript = Join-Path $scriptDir "collect_idlefish_webview_js.ps1"
$baseUrl = Get-BaseUrl $IngestUrl
$healthUrl = "$baseUrl/health"
$latestUrl = "$baseUrl/api/v1/idlefish/latest"

if (-not $SkipHealthCheck) {
    Write-Host "Checking collector server: $healthUrl"
    try {
        Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 5 | Out-Null
    } catch {
        throw "Collector server is not reachable. Use -SkipHealthCheck if this deployment has no /health endpoint."
    }
}

Write-Host "Collecting Idlefish service scores from VirtualApp shops..."
Write-Host "Device: $Serial"
Write-Host "Device no: $DeviceNo"
Write-Host "Virtual users: $($VirtualUserIds -join ', ')"
Write-Host "Ingest URL: $IngestUrl"

foreach ($virtualUserId in $VirtualUserIds) {
    Write-Host ""
    Write-Host "=== Collect service score for VirtualApp user $virtualUserId ==="
    if (-not $NoForceStop) {
        Write-Host "Force stopping $HostPackage to trigger a fresh WebView resume..."
        & adb -s $Serial shell am force-stop $HostPackage
        Start-Sleep -Seconds 2
    }

    $args = @{
        Serial = $Serial
        VirtualUserId = $virtualUserId
        IngestUrl = $IngestUrl
        DiscoveryUrl = $DiscoveryUrl
        DeviceNo = $DeviceNo
        WaitSeconds = $WaitSecondsPerShop
        RecentLimit = $RecentLimit
        SkipHealthCheck = $SkipHealthCheck
        SkipRecentCheck = $true
    }
    if ($DeviceSecret) {
        $args.DeviceSecret = $DeviceSecret
    }
    & $singleScript @args
}

Write-Host ""
Write-Host "Latest bound shop metrics:"
try {
    Invoke-RestMethod -Uri $latestUrl -Method Get -TimeoutSec 10 | ConvertTo-Json -Depth 20
} catch {
    Write-Warning "Unable to read latest metrics: $($_.Exception.Message)"
}
