function Import-IdlefishEnv {
    param([string]$Path = $env:IDLEFISH_ENV_FILE)
    if (-not $Path) {
        $Path = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'
    }
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($line in Get-Content -LiteralPath $Path) {
        $entry = $line.Trim()
        if (-not $entry -or $entry.StartsWith('#')) { continue }
        if ($entry -notmatch '^([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            throw 'Invalid .env entry. Expected KEY=value.'
        }
        $key = $Matches[1]
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        if ($null -eq [Environment]::GetEnvironmentVariable($key, 'Process')) {
            [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        }
    }
}

function Get-IdlefishServerUrl {
    param([string]$ApiPath)
    $baseUrl = $env:IDLEFISH_SERVER_BASE_URL
    $uri = $null
    if (-not [Uri]::TryCreate($baseUrl, [UriKind]::Absolute, [ref]$uri) -or $uri.Scheme -notin @('http', 'https') -or -not $uri.Host) {
        throw 'Set IDLEFISH_SERVER_BASE_URL in .env to an absolute HTTP(S) collector URL.'
    }
    return $baseUrl.TrimEnd('/') + $ApiPath
}
