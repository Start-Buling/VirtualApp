param(
    [int]$Port = 18080
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$serverScript = Join-Path $repoRoot "server\idlefish-ingest-server.js"

if (-not (Test-Path $serverScript)) {
    throw "Server script not found: $serverScript"
}

$env:PORT = [string]$Port
Set-Location $repoRoot

Write-Host "Starting Idlefish ingest server on http://127.0.0.1:$Port"
Write-Host "Keep this window open while testing the phone upload button."
Write-Host "Health check: http://127.0.0.1:$Port/health"
Write-Host "Recent events: http://127.0.0.1:$Port/api/v1/events/recent?limit=10"
Write-Host ""

node $serverScript
