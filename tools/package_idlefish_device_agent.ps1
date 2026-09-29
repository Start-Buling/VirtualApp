param(
    [string]$Runtime = "win-x64",
    [string]$Configuration = "Release",
    [string]$OutputDir = ".\dist\idlefish-device-agent",
    [string]$PlatformToolsPath = "",
    [switch]$OverwriteConfig
)

$ErrorActionPreference = "Stop"

$project = Join-Path $PSScriptRoot "IdlefishDeviceAgent\IdlefishDeviceAgent.csproj"
$trayProject = Join-Path $PSScriptRoot "IdlefishDeviceAgent.Tray\IdlefishDeviceAgent.Tray.csproj"
$trayIconScript = Join-Path $PSScriptRoot "IdlefishDeviceAgent.Tray\Assets\create_icon.ps1"
$trayIcon = Join-Path $PSScriptRoot "IdlefishDeviceAgent.Tray\Assets\idlefish-agent.ico"
$resolvedOutput = Resolve-Path -Path (New-Item -ItemType Directory -Force -Path $OutputDir)

if (-not (Test-Path $trayIcon)) {
    powershell -ExecutionPolicy Bypass -File $trayIconScript
}

dotnet publish $project `
    -c $Configuration `
    -r $Runtime `
    --self-contained true `
    -p:PublishSingleFile=true `
    -p:PublishTrimmed=false `
    -o $resolvedOutput

dotnet publish $trayProject `
    -c $Configuration `
    -r $Runtime `
    --self-contained true `
    -p:PublishSingleFile=true `
    -p:PublishTrimmed=false `
    -o $resolvedOutput

$exampleConfig = Join-Path $PSScriptRoot "IdlefishDeviceAgent\idlefish-agent.example.json"
$targetConfig = Join-Path $resolvedOutput "idlefish-agent.json"

Copy-Item -Path $exampleConfig `
    -Destination (Join-Path $resolvedOutput "idlefish-agent.example.json") `
    -Force

if ($OverwriteConfig -or -not (Test-Path $targetConfig)) {
    Copy-Item -Path $exampleConfig -Destination $targetConfig -Force
} else {
    Write-Host "Preserved existing config:"
    Write-Host $targetConfig
}

if (-not $PlatformToolsPath) {
    $localProperties = Join-Path (Split-Path -Parent $PSScriptRoot) "local.properties"
    if (Test-Path $localProperties) {
        $sdkLine = Get-Content -Path $localProperties | Where-Object { $_ -match "^sdk\.dir=(.+)$" } | Select-Object -First 1
        if ($sdkLine -and $sdkLine -match "^sdk\.dir=(.+)$") {
            $sdkDir = $Matches[1] -replace "/", "\"
            $PlatformToolsPath = Join-Path $sdkDir "platform-tools"
        }
    }
}

if (-not $PlatformToolsPath -and $env:ANDROID_HOME) {
    $PlatformToolsPath = Join-Path $env:ANDROID_HOME "platform-tools"
}

if (-not $PlatformToolsPath -and $env:ANDROID_SDK_ROOT) {
    $PlatformToolsPath = Join-Path $env:ANDROID_SDK_ROOT "platform-tools"
}

if (-not $PlatformToolsPath) {
    $defaultSdk = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools"
    if (Test-Path $defaultSdk) {
        $PlatformToolsPath = $defaultSdk
    }
}

if ($PlatformToolsPath -and (Test-Path (Join-Path $PlatformToolsPath "adb.exe"))) {
    $targetPlatformTools = Join-Path $resolvedOutput "platform-tools"
    New-Item -ItemType Directory -Force -Path $targetPlatformTools | Out-Null
    Copy-Item -Path (Join-Path $PlatformToolsPath "*") -Destination $targetPlatformTools -Recurse -Force
    Write-Host "Bundled platform-tools:"
    Write-Host $targetPlatformTools
} else {
    Write-Warning "platform-tools was not bundled. Set -PlatformToolsPath or configure adbPath manually."
}

Write-Host "Packaged Idlefish Device Agent:"
Write-Host (Join-Path $resolvedOutput "IdlefishDeviceAgent.exe")
Write-Host (Join-Path $resolvedOutput "IdlefishDeviceAgent.Tray.exe")
Write-Host "Edit idlefish-agent.json next to the exe before running it on another PC."
