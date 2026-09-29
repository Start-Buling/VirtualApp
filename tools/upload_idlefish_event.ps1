param(
    [Parameter(Mandatory = $true)]
    [string]$EventPath,
    [string]$Url = "http://127.0.0.1:18080/api/v1/ingest/events",
    [string]$DeviceSecret = ""
)

$ErrorActionPreference = "Stop"

function New-HmacSignature {
    param(
        [string]$Secret,
        [string]$Message
    )

    $keyBytes = [System.Text.Encoding]::UTF8.GetBytes($Secret)
    $messageBytes = [System.Text.Encoding]::UTF8.GetBytes($Message)
    $hmac = [System.Security.Cryptography.HMACSHA256]::new($keyBytes)
    try {
        $hash = $hmac.ComputeHash($messageBytes)
        return -join ($hash | ForEach-Object { $_.ToString("x2") })
    } finally {
        $hmac.Dispose()
    }
}

$event = Get-Content -Raw -Encoding UTF8 -Path $EventPath | ConvertFrom-Json
$deviceId = $event.account.device_serial
if (-not $deviceId) {
    throw "Event does not contain account.device_serial."
}

$payload = [ordered]@{
    device_id = $deviceId
    events = @($event)
}

$body = $payload | ConvertTo-Json -Depth 16 -Compress
$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($body)
$headers = @{}

if ($DeviceSecret) {
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $headers["x-device-id"] = $deviceId
    $headers["x-timestamp"] = $timestamp
    $headers["x-signature"] = New-HmacSignature -Secret $DeviceSecret -Message "$timestamp.$body"
}

Invoke-RestMethod `
    -Method Post `
    -Uri $Url `
    -Headers $headers `
    -ContentType "application/json; charset=utf-8" `
    -Body $bodyBytes
