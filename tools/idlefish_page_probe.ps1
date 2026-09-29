param(
    [string]$Serial = "",
    [string]$OutDir = "page-probe",
    [string]$InputResultJson = ""
)

$ErrorActionPreference = "Stop"

function Run-Adb {
    param([string[]]$AdbArgs)
    if ($Serial) {
        & adb -s $Serial @AdbArgs
    } else {
        & adb @AdbArgs
    }
}

function Get-FirstDevice {
    $lines = & adb devices
    foreach ($line in $lines) {
        if ($line -match "^(\S+)\s+device$") {
            return $Matches[1]
        }
    }
    throw "No adb device in 'device' state."
}

function Try-HttpJson {
    param([int]$Port, [string]$Path)
    try {
        return Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:$Port$Path" -TimeoutSec 2 |
            Select-Object -ExpandProperty Content
    } catch {
        return $null
    }
}

function Get-FirstText {
    param([object[]]$Texts, [string]$Pattern)
    foreach ($text in $Texts) {
        if ($text -match $Pattern) {
            return $text
        }
    }
    return $null
}

function Get-NumberAfterLabel {
    param([object[]]$Texts, [string]$Label)
    for ($i = 0; $i -lt $Texts.Count; $i++) {
        if ($Texts[$i] -eq $Label) {
            for ($j = $i + 1; $j -lt $Texts.Count; $j++) {
                if ($Texts[$j] -match "^\d+(\.\d+)?$") {
                    return [double]$Texts[$j]
                }
            }
        }
    }
    return $null
}

function Get-PercentAfterLabel {
    param([object[]]$Texts, [string]$Label)
    for ($i = 0; $i -lt $Texts.Count; $i++) {
        if ($Texts[$i] -eq $Label) {
            for ($j = $i + 1; $j -lt $Texts.Count; $j++) {
                if ($Texts[$j] -match "^(\d+)%$") {
                    return [int]$Matches[1]
                }
                if (($j + 1) -lt $Texts.Count -and $Texts[$j] -match "^\d+$" -and $Texts[$j + 1] -eq "%") {
                    return [int]$Texts[$j]
                }
            }
        }
    }
    return $null
}

function New-Text {
    param([int[]]$CodePoints)
    return -join ($CodePoints | ForEach-Object { [char]$_ })
}

function Convert-DateText {
    param([string]$Text)
    $updatedLabel = New-Text @(0x66F4, 0x65B0)
    if ($Text -match "(\d{4})\.(\d{2})\.(\d{2})$updatedLabel") {
        return "$($Matches[1])-$($Matches[2])-$($Matches[3])"
    }
    return $null
}

function Get-UiTextsFromXml {
    param([string]$XmlText)
    $texts = @()
    try {
        [xml]$uiXml = $XmlText
        $nodes = $uiXml.SelectNodes("//*[@text or @content-desc]")
        foreach ($node in $nodes) {
            $text = $node.GetAttribute("text")
            $desc = $node.GetAttribute("content-desc")
            if ($text) { $texts += $text }
            if ($desc) { $texts += $desc }
        }
        return @($texts | Where-Object { $_ -and $_.Trim() })
    } catch {
        return @()
    }
}

function Build-ServiceScoreEvent {
    param(
        [string]$DeviceSerial,
        [string]$ActivityName,
        [object[]]$Urls,
        [object[]]$Texts,
        [string]$CapturedAt
    )

    $labelCategory = New-Text @(0x8003, 0x6838, 0x7C7B, 0x76EE)
    $labelUpdated = New-Text @(0x66F4, 0x65B0)
    $labelServiceScore = New-Text @(0x5C0F, 0x94FA, 0x670D, 0x52A1, 0x5206)
    $labelHigherThan = New-Text @(0x9AD8, 0x4E8E)
    $labelLagging = New-Text @(0x843D, 0x540E)
    $labelExceeding = New-Text @(0x8D85, 0x8FC7)
    $labelPeer = New-Text @(0x540C, 0x884C)
    $labelItemQuality = New-Text @(0x5B9D, 0x8D1D, 0x8D28, 0x91CF)
    $labelResponseSpeed = New-Text @(0x54CD, 0x5E94, 0x901F, 0x5EA6)
    $labelLogistics = New-Text @(0x7269, 0x6D41, 0x4F53, 0x9A8C)
    $labelAfterSales = New-Text @(0x552E, 0x540E, 0x4F53, 0x9A8C)
    $labelQualityRefund = New-Text @(0x54C1, 0x8D28, 0x539F, 0x56E0, 0x9000, 0x6B3E, 0x7387)
    $labelPunishedItem = New-Text @(0x5904, 0x7F5A, 0x5B9D, 0x8D1D, 0x5360, 0x6BD4)
    $labelRiskItem = New-Text @(0x98CE, 0x9669, 0x5B9D, 0x8D1D, 0x5360, 0x6BD4)
    $labelDescriptionCoverage = (New-Text @(0x300C, 0x771F, 0x5B9E, 0x63CF, 0x8FF0, 0x300D)) + (New-Text @(0x76F8, 0x5173, 0x670D, 0x52A1, 0x8986, 0x76D6, 0x7387))
    $labelFiveMinuteResponse = New-Text @(0x0035, 0x5206, 0x949F, 0x56DE, 0x590D, 0x7387)
    $labelDeliveryRefund = New-Text @(0x53D1, 0x8D27, 0x539F, 0x56E0, 0x9000, 0x6B3E, 0x7387)
    $labelFortyEightHourDelivery = New-Text @(0x0034, 0x0038, 0x5C0F, 0x65F6, 0x53D1, 0x8D27, 0x7387)
    $labelFastDeliveryCoverage = (New-Text @(0x300C, 0x6781, 0x901F, 0x53D1, 0x8D27, 0x300D)) + (New-Text @(0x670D, 0x52A1, 0x8986, 0x76D6, 0x7387))
    $labelExtraScore = New-Text @(0x9644, 0x52A0, 0x5206)

    $pageUrl = $Urls | Where-Object { $_ -match "fish-shop-data/pages/service-points" } | Select-Object -Last 1
    if (-not $pageUrl) {
        $pageUrl = $Urls | Select-Object -Last 1
    }

    $packageName = $null
    if ($ActivityName -match "^([^/]+)/") {
        $packageName = $Matches[1]
    }

    $category = $null
    $categoryPattern = "^" + [regex]::Escape($labelCategory) + ":\s*(.+)$"
    $categoryText = Get-FirstText -Texts $Texts -Pattern $categoryPattern
    if ($categoryText -and $categoryText -match $categoryPattern) {
        $category = $Matches[1]
    }

    $updatedDate = Convert-DateText (Get-FirstText -Texts $Texts -Pattern ("^\d{4}\.\d{2}\.\d{2}" + [regex]::Escape($labelUpdated) + "$"))

    $serviceScore = $null
    for ($i = 0; $i -lt $Texts.Count; $i++) {
        if ($Texts[$i] -eq $labelServiceScore) {
            for ($j = $i + 1; $j -lt $Texts.Count; $j++) {
                if ($Texts[$j] -match "^\d+\.\d+$") {
                    $serviceScore = [double]$Texts[$j]
                    break
                }
            }
            break
        }
    }

    $peerStatusPattern = "^(" + [regex]::Escape($labelHigherThan) + "|" + [regex]::Escape($labelLagging) + "|" + [regex]::Escape($labelExceeding) + ")\d+%" + [regex]::Escape($labelPeer) + "$"
    $peerStatus = Get-FirstText -Texts $Texts -Pattern $peerStatusPattern

    return [ordered]@{
        event_type = "idlefish_shop_service_score"
        schema_version = 1
        event_id = [guid]::NewGuid().ToString()
        captured_at = $CapturedAt
        source = "uiautomator"
        account = [ordered]@{
            device_serial = $DeviceSerial
            package_name = $packageName
            virtual_user_id = $null
            shop_id = $null
            account_alias = $null
        }
        page = [ordered]@{
            activity = $ActivityName
            url = $pageUrl
        }
        metrics = [ordered]@{
            category = $category
            updated_date = $updatedDate
            service_score = $serviceScore
            service_score_peer_status = $peerStatus
            item_quality_score = Get-NumberAfterLabel -Texts $Texts -Label $labelItemQuality
            response_speed_score = Get-NumberAfterLabel -Texts $Texts -Label $labelResponseSpeed
            logistics_score = Get-NumberAfterLabel -Texts $Texts -Label $labelLogistics
            after_sales_score = Get-NumberAfterLabel -Texts $Texts -Label $labelAfterSales
            quality_refund_rate_percent = Get-PercentAfterLabel -Texts $Texts -Label $labelQualityRefund
            punished_item_ratio_percent = Get-PercentAfterLabel -Texts $Texts -Label $labelPunishedItem
            risk_item_ratio_percent = Get-PercentAfterLabel -Texts $Texts -Label $labelRiskItem
            description_service_coverage_percent = Get-PercentAfterLabel -Texts $Texts -Label $labelDescriptionCoverage
            five_minute_response_rate_percent = Get-PercentAfterLabel -Texts $Texts -Label $labelFiveMinuteResponse
            average_response_time = Get-FirstText -Texts $Texts -Pattern "^\d+$([regex]::Escape((New-Text @(0x5206, 0x949F))))$"
            delivery_refund_rate_percent = Get-PercentAfterLabel -Texts $Texts -Label $labelDeliveryRefund
            forty_eight_hour_delivery_rate_percent = Get-PercentAfterLabel -Texts $Texts -Label $labelFortyEightHourDelivery
            fast_delivery_coverage_percent = Get-PercentAfterLabel -Texts $Texts -Label $labelFastDeliveryCoverage
            complaint_confirmed_count = Get-FirstText -Texts $Texts -Pattern "^\d+$([regex]::Escape((New-Text @(0x4E2A))))$"
            extra_score = Get-NumberAfterLabel -Texts $Texts -Label $labelExtraScore
        }
        raw = [ordered]@{
            ui_texts = $Texts
            api = $null
            payload_hash = $null
        }
    }
}

if (-not $Serial -and -not $InputResultJson) {
    $Serial = Get-FirstDevice
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$capturedAt = Get-Date -Format "yyyy-MM-ddTHH:mm:sszzz"
$resolvedOutDir = Join-Path (Resolve-Path ".") $OutDir
New-Item -ItemType Directory -Force -Path $resolvedOutDir | Out-Null

if ($InputResultJson) {
    $existingResult = Get-Content -Raw -Encoding UTF8 -Path $InputResultJson | ConvertFrom-Json
    $replayTexts = $existingResult.uiTexts
    $pairedUiXml = $InputResultJson -replace "-result\.json$", "-ui.xml"
    if (Test-Path $pairedUiXml) {
        $xmlReplayText = Get-Content -Raw -Encoding UTF8 -Path $pairedUiXml
        $xmlReplayTexts = Get-UiTextsFromXml -XmlText $xmlReplayText
        if ($xmlReplayTexts.Count -gt 0) {
            $replayTexts = $xmlReplayTexts
        }
    }
    $event = Build-ServiceScoreEvent `
        -DeviceSerial $existingResult.serial `
        -ActivityName $existingResult.currentActivity `
        -Urls $existingResult.urls `
        -Texts $replayTexts `
        -CapturedAt $capturedAt

    $eventPath = Join-Path $resolvedOutDir "$timestamp-event.json"
    $event | ConvertTo-Json -Depth 8 | Set-Content -Path $eventPath -Encoding UTF8
    $existingResult | Add-Member -NotePropertyName eventPath -NotePropertyValue $eventPath -Force
    $existingResult | Add-Member -NotePropertyName replayTextCount -NotePropertyValue $replayTexts.Count -Force
    $existingResult | ConvertTo-Json -Depth 8
    exit 0
}

$window = Run-Adb @("shell", "dumpsys", "window")
$activity = Run-Adb @("shell", "dumpsys", "activity", "com.taobao.idlefish")
$uiRaw = Run-Adb @("exec-out", "uiautomator", "dump", "/dev/tty")
$uiDumpExitCode = $LASTEXITCODE
$uiDumpStatus = "ok"

$currentFocus = ($window | Select-String -Pattern "mCurrentFocus=" | Select-Object -First 1).Line
$currentActivity = ""
if ($currentFocus -match " ([^/\s]+/[^}\s]+)") {
    $currentActivity = $Matches[1]
}

$urls = @()
foreach ($line in $activity) {
    foreach ($match in [regex]::Matches($line, "https?://[^\s,}\]]+")) {
        $urls += $match.Value
    }
}
$urls = $urls | Select-Object -Unique

$xmlText = ""
$uiTexts = @()
$xmlMatch = [regex]::Match(($uiRaw -join "`n"), "(?s)<\?xml.*</hierarchy>")
if ($xmlMatch.Success) {
    $xmlText = $xmlMatch.Value
    $xmlPath = Join-Path $resolvedOutDir "$timestamp-ui.xml"
    Set-Content -Path $xmlPath -Value $xmlText -Encoding UTF8
    $uiTexts = Get-UiTextsFromXml -XmlText $xmlText
} elseif (($uiRaw -join "`n") -match "Killed") {
    $uiDumpStatus = "killed"
} else {
    $uiDumpStatus = "no_xml"
}

$screenRemote = "/sdcard/idlefish-page-probe-$timestamp.png"
$screenLocal = Join-Path $resolvedOutDir "$timestamp.png"
Run-Adb @("shell", "screencap", "-p", $screenRemote) | Out-Null
Run-Adb @("pull", $screenRemote, $screenLocal) | Out-Null
Run-Adb @("shell", "rm", $screenRemote) | Out-Null

$pidsRaw = Run-Adb @("shell", "pidof", "com.taobao.idlefish")
$pids = @()
if ($pidsRaw) {
    $pids = ($pidsRaw -join " ").Split(" ", [System.StringSplitOptions]::RemoveEmptyEntries)
}

$devtools = @()
$devtoolsSockets = Run-Adb @("shell", "cat", "/proc/net/unix") |
    Select-String -Pattern "webview_devtools_remote"
$basePort = 9222
for ($i = 0; $i -lt $pids.Count; $i++) {
    $procId = $pids[$i]
    if (-not ($devtoolsSockets -match "webview_devtools_remote_$procId")) {
        continue
    }
    $port = $basePort + $i
    Run-Adb @("forward", "tcp:$port", "localabstract:webview_devtools_remote_$procId") | Out-Null
    $json = Try-HttpJson -Port $port -Path "/json"
    if ($json) {
        $devtools += [ordered]@{
            pid = $procId
            port = $port
            json = ($json | ConvertFrom-Json)
        }
    }
}

$result = [ordered]@{
    serial = $Serial
    currentFocus = $currentFocus
    currentActivity = $currentActivity
    uiDumpStatus = $uiDumpStatus
    uiDumpExitCode = $uiDumpExitCode
    urls = $urls
    uiTextCount = $uiTexts.Count
    uiTexts = $uiTexts
    screenshot = $screenLocal
    devtoolsSockets = @($devtoolsSockets | ForEach-Object { $_.Line })
    devtoolsTargets = $devtools
}

$event = Build-ServiceScoreEvent `
    -DeviceSerial $Serial `
    -ActivityName $currentActivity `
    -Urls $urls `
    -Texts $uiTexts `
    -CapturedAt $capturedAt

$jsonPath = Join-Path $resolvedOutDir "$timestamp-result.json"
$eventPath = Join-Path $resolvedOutDir "$timestamp-event.json"
$result.eventPath = $eventPath
$result | ConvertTo-Json -Depth 8 | Set-Content -Path $jsonPath -Encoding UTF8
$event | ConvertTo-Json -Depth 8 | Set-Content -Path $eventPath -Encoding UTF8
$result | ConvertTo-Json -Depth 8
