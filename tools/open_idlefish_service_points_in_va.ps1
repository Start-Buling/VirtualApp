param(
    [string]$Serial = "",
    [int]$UserId = 0,
    [string]$PackageName = "com.taobao.idlefish",
    [string]$HostPackage = "com.carlos.multiapp",
    [string]$Url = "https://h5.m.goofish.com/wow/moyu/moyu-project/fish-shop-data/pages/service-points?spm=a2170.28358589.0.0&isOldFriendly=false&_from__=webhybrid",
    [string]$IngestUrl = "",
    [string]$DiscoveryUrl = "",
    [string]$DeviceNo = "",
    [string]$DeviceSecret = "",
    [switch]$UseFleamarketWebView
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

if (-not $Serial) {
    $Serial = Get-FirstDevice
}

$component = "$HostPackage/com.carlos.home.idlefish.IdlefishDeepLinkReceiver"
$launcherComponent = "$HostPackage/com.carlos.splash.splashActivity"
$intentAction = "android.intent.action.VIEW"
$targetComponent = ""
$openUrl = $Url

if ($UseFleamarketWebView) {
    $intentAction = "android.intent.action.idlefish"
    $targetComponent = "com.taobao.idlefish.webview.WebHybridActivity"
    $encodedUrl = [System.Uri]::EscapeDataString($Url)
    $openUrl = "fleamarket://webview?url=$encodedUrl"
}

Write-Host "Opening Idlefish URL inside VirtualApp..."
Write-Host "Device: $Serial"
Write-Host "UserId: $UserId"
Write-Host "Package: $PackageName"
Write-Host "Intent action: $intentAction"
if ($targetComponent) {
    Write-Host "Target component: $targetComponent"
}
Write-Host "URL: $openUrl"
if ($IngestUrl) {
    Write-Host "Ingest URL: $IngestUrl"
}
if ($DiscoveryUrl) {
    Write-Host "Discovery URL: $DiscoveryUrl"
}
if ($DeviceNo) {
    Write-Host "Device no: $DeviceNo"
}

& adb -s $Serial shell am start -n $launcherComponent | Out-Null
Start-Sleep -Seconds 6

$quotedUrl = "'" + ($openUrl -replace "'", "'\''") + "'"
$command = "am broadcast -n $component -a com.carlos.multiapp.IDLEFISH_OPEN_URL --ei user_id $UserId --es package_name $PackageName --es intent_action $intentAction --es url $quotedUrl"
if ($targetComponent) {
    $command += " --es component_name $targetComponent"
}
if ($IngestUrl) {
    $quotedIngestUrl = "'" + ($IngestUrl -replace "'", "'\''") + "'"
    $command += " --es sync_endpoint $quotedIngestUrl --ez sync_enabled true"
}
if ($DiscoveryUrl) {
    $quotedDiscoveryUrl = "'" + ($DiscoveryUrl -replace "'", "'\''") + "'"
    $command += " --es discovery_endpoint $quotedDiscoveryUrl --ez sync_enabled true"
}
if ($DeviceNo) {
    $quotedDeviceNo = "'" + ($DeviceNo -replace "'", "'\''") + "'"
    $command += " --es device_no $quotedDeviceNo"
}
if ($DeviceSecret) {
    $quotedDeviceSecret = "'" + ($DeviceSecret -replace "'", "'\''") + "'"
    $command += " --es device_secret $quotedDeviceSecret"
}
& adb -s $Serial shell $command

if ($LASTEXITCODE -ne 0) {
    throw "adb broadcast failed with exit code $LASTEXITCODE"
}
