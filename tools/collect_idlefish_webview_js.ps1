param(
    [string]$Serial = "",
    [int]$VirtualUserId = 0,
    [string]$IngestUrl = "http://127.0.0.1:18080/api/v1/ingest/events",
    [string]$DiscoveryUrl = "",
    [string]$DeviceNo = "device_001",
    [int]$WaitSeconds = 36,
    [int]$RecentLimit = 5,
    [string]$DeviceSecret = "",
    [switch]$SkipHealthCheck,
    [switch]$SkipRecentCheck
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

function Get-BaseUrl([string]$Url) {
    $uri = [System.Uri]$Url
    $port = if ($uri.IsDefaultPort) { "" } else { ":$($uri.Port)" }
    return "$($uri.Scheme)://$($uri.Host)$port"
}

if (-not $Serial) {
    $Serial = Get-FirstDevice
}

$baseUrl = Get-BaseUrl $IngestUrl
$healthUrl = "$baseUrl/health"
$recentUrl = "$baseUrl/api/v1/events/recent?limit=$RecentLimit"
$ingestUri = [System.Uri]$IngestUrl

if (-not $SkipHealthCheck) {
    Write-Host "Checking ingest server: $healthUrl"
    try {
        Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 3 | Out-Null
    } catch {
        throw "Ingest server is not reachable. Use -SkipHealthCheck if this deployment has no /health endpoint."
    }
}

if ($ingestUri.Host -eq "127.0.0.1" -or $ingestUri.Host -eq "localhost") {
    $port = $ingestUri.Port
    if ($port -le 0) {
        throw "Local IngestUrl must include an explicit port: $IngestUrl"
    }
    Write-Host "Configuring adb reverse tcp:$port -> tcp:$port"
    & adb -s $Serial reverse "tcp:$port" "tcp:$port"
    if ($LASTEXITCODE -ne 0) {
        throw "adb reverse failed with exit code $LASTEXITCODE"
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$openScript = Join-Path $scriptDir "open_idlefish_service_points_in_va.ps1"

Write-Host "Opening Idlefish service-points page in VirtualApp user $VirtualUserId"
$openArgs = @{
    Serial = $Serial
    UserId = $VirtualUserId
    IngestUrl = $IngestUrl
    DeviceNo = $DeviceNo
    UseFleamarketWebView = $true
}
if ($DiscoveryUrl) {
    $openArgs.DiscoveryUrl = $DiscoveryUrl
}
if ($DeviceSecret) {
    $openArgs.DeviceSecret = $DeviceSecret
}
& $openScript @openArgs

Write-Host "Waiting $WaitSeconds seconds for WebView JS capture and upload..."
Start-Sleep -Seconds $WaitSeconds

if (-not $SkipRecentCheck) {
    Write-Host "Recent uploaded events:"
    try {
        $recent = Invoke-RestMethod -Uri $recentUrl -Method Get -TimeoutSec 5
        $recent | ConvertTo-Json -Depth 20
    } catch {
        Write-Warning "Unable to read recent events: $($_.Exception.Message)"
    }
}
