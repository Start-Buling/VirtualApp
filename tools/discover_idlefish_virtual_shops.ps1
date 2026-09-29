param(
    [string]$Serial = "",
    [string]$DeviceNo = "device_001",
    [int[]]$VirtualUserIds = @(0, 1, 2),
    [string]$IngestUrl = "",
    [string]$DiscoveryUrl = "",
    [int]$WaitSecondsPerShop = 42,
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
$singleScript = Join-Path $scriptDir "discover_idlefish_shop_identity.ps1"
$baseUrl = Get-BaseUrl $(if ($DiscoveryUrl) { $DiscoveryUrl } else { $IngestUrl })
$pendingUrl = "$baseUrl/api/v1/idlefish/discovery/pending"

Write-Host "Discovering Idlefish VirtualApp shops..."
Write-Host "Device: $Serial"
Write-Host "Device no: $DeviceNo"
Write-Host "Virtual users: $($VirtualUserIds -join ', ')"
Write-Host "Ingest URL: $IngestUrl"
Write-Host "Discovery URL: $DiscoveryUrl"

foreach ($virtualUserId in $VirtualUserIds) {
    Write-Host ""
    Write-Host "=== Discover VirtualApp user $virtualUserId ==="
    if (-not $NoForceStop) {
        Write-Host "Force stopping $HostPackage to trigger a fresh WebView resume..."
        & adb -s $Serial shell am force-stop $HostPackage
        Start-Sleep -Seconds 2
    }

    $args = @{
        Serial = $Serial
        VirtualUserId = $virtualUserId
        DeviceNo = $DeviceNo
        IngestUrl = $IngestUrl
        DiscoveryUrl = $DiscoveryUrl
        WaitSeconds = $WaitSecondsPerShop
        SkipPendingCheck = $true
    }
    if ($SkipHealthCheck) {
        $args.SkipHealthCheck = $true
    }
    if ($DeviceSecret) {
        $args.DeviceSecret = $DeviceSecret
    }
    & $singleScript @args
}

Write-Host ""
Write-Host "Pending discovery results:"
try {
    Invoke-RestMethod -Uri $pendingUrl -Method Get -TimeoutSec 10 | ConvertTo-Json -Depth 20
} catch {
    Write-Warning "Unable to read pending discovery results: $($_.Exception.Message)"
}
