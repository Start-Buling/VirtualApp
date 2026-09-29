param(
    [string]$Serial = "",
    [int]$VirtualUserId = 0,
    [string]$DeviceNo = "device_001",
    [string]$IngestUrl = "http://127.0.0.1:18080/api/v1/ingest/events",
    [string]$DiscoveryUrl = "",
    [string]$WorkbenchUrl = "https://h5.m.goofish.com/wow/moyu/moyu-project/fish-pro-workbench/pages/Workbench?spm=a2170.7905589.0.0&isOldFriendly=false&_from__=main",
    [int]$WaitSeconds = 36,
    [string]$DeviceSecret = "",
    [switch]$SkipHealthCheck,
    [switch]$SkipPendingCheck
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

$baseUrl = Get-BaseUrl $(if ($DiscoveryUrl) { $DiscoveryUrl } else { $IngestUrl })
$healthUrl = "$baseUrl/health"
$pendingUrl = "$baseUrl/api/v1/idlefish/discovery/pending"
$recentUrl = "$baseUrl/api/v1/events/recent?limit=5"

if (-not $SkipHealthCheck) {
    Write-Host "Checking collector server: $healthUrl"
    try {
        Invoke-RestMethod -Uri $healthUrl -Method Get -TimeoutSec 5 | Out-Null
    } catch {
        throw "Collector server is not reachable. Use -SkipHealthCheck if this deployment has no /health endpoint."
    }
}

foreach ($url in @($IngestUrl, $DiscoveryUrl)) {
    if (-not $url) {
        continue
    }
    $uri = [System.Uri]$url
    if ($uri.Host -eq "127.0.0.1" -or $uri.Host -eq "localhost") {
        $port = $uri.Port
        if ($port -le 0) {
            throw "Local URL must include an explicit port: $url"
        }
        Write-Host "Configuring adb reverse tcp:$port -> tcp:$port"
        & adb -s $Serial reverse "tcp:$port" "tcp:$port"
        if ($LASTEXITCODE -ne 0) {
            throw "adb reverse failed with exit code $LASTEXITCODE"
        }
    }
}

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$openScript = Join-Path $scriptDir "open_idlefish_service_points_in_va.ps1"

Write-Host "Opening Idlefish workbench in VirtualApp user $VirtualUserId"
$openArgs = @{
    Serial = $Serial
    UserId = $VirtualUserId
    Url = $WorkbenchUrl
    IngestUrl = $IngestUrl
    DiscoveryUrl = $DiscoveryUrl
    DeviceNo = $DeviceNo
    UseFleamarketWebView = $true
}
if ($DeviceSecret) {
    $openArgs.DeviceSecret = $DeviceSecret
}
& $openScript @openArgs

Write-Host "Waiting $WaitSeconds seconds for identity discovery upload..."
Start-Sleep -Seconds $WaitSeconds

if (-not $SkipPendingCheck) {
    if ($DiscoveryUrl) {
        Write-Host "Pending discovery results:"
        try {
            Invoke-RestMethod -Uri $pendingUrl -Method Get -TimeoutSec 8 | ConvertTo-Json -Depth 20
        } catch {
            Write-Warning "Unable to read pending discovery results: $($_.Exception.Message)"
        }
    } else {
        Write-Host "Recent uploaded events:"
        try {
            Invoke-RestMethod -Uri $recentUrl -Method Get -TimeoutSec 8 | ConvertTo-Json -Depth 20
        } catch {
            Write-Warning "Unable to read recent events: $($_.Exception.Message)"
        }
    }
}
