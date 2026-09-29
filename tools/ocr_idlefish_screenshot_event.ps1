param(
    [Parameter(Mandatory = $true)]
    [string]$ResultJson,
    [string]$OutDir = "page-probe"
)

$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime

$null = [Windows.Storage.StorageFile, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Storage.FileAccessMode, Windows.Storage, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Graphics.Imaging.SoftwareBitmap, Windows.Graphics.Imaging, ContentType = WindowsRuntime]
$null = [Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime]
$null = [Windows.Globalization.Language, Windows.Foundation, ContentType = WindowsRuntime]

$asTaskGeneric = ([System.WindowsRuntimeSystemExtensions].GetMethods() |
    Where-Object { $_.Name -eq "AsTask" -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 } |
    Select-Object -First 1)

function Await-WinRt {
    param($Operation, [Type]$ResultType)
    $task = $asTaskGeneric.MakeGenericMethod($ResultType).Invoke($null, @($Operation))
    $task.Wait()
    return $task.Result
}

function New-Text {
    param([int[]]$CodePoints)
    return -join ($CodePoints | ForEach-Object { [char]$_ })
}

function Invoke-WindowsOcr {
    param([string]$ImagePath, [string]$LanguageTag)

    $file = Await-WinRt ([Windows.Storage.StorageFile]::GetFileFromPathAsync($ImagePath)) ([Windows.Storage.StorageFile])
    $stream = Await-WinRt ($file.OpenAsync([Windows.Storage.FileAccessMode]::Read)) ([Windows.Storage.Streams.IRandomAccessStream])
    $decoder = Await-WinRt ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($stream)) ([Windows.Graphics.Imaging.BitmapDecoder])
    $bitmap = Await-WinRt ($decoder.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
    $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromLanguage([Windows.Globalization.Language]::new($LanguageTag))
    if ($null -eq $engine) {
        $engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
    }
    $result = Await-WinRt ($engine.RecognizeAsync($bitmap)) ([Windows.Media.Ocr.OcrResult])
    return $result.Text
}

function New-ScaledCrop {
    param(
        [System.Drawing.Bitmap]$Image,
        [double]$X,
        [double]$Y,
        [double]$Width,
        [double]$Height,
        [string]$OutPath
    )

    $scaleX = $Image.Width / 720.0
    $scaleY = $Image.Height / 1612.0
    $rect = [System.Drawing.Rectangle]::new(
        [int]($X * $scaleX),
        [int]($Y * $scaleY),
        [int]($Width * $scaleX),
        [int]($Height * $scaleY)
    )
    $crop = $Image.Clone($rect, $Image.PixelFormat)
    try {
        $crop.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $crop.Dispose()
    }
}

function Convert-OcrScore {
    param([string]$Text)
    $normalized = $Text -replace "\s+", ""
    $normalized = $normalized -replace ([string][char]0xFF0E), "."
    $normalized = $normalized -replace ([string][char]0x53B6), "4"
    if ($normalized -match "(\d)\.(\d{1,2})") {
        return [double]"$($Matches[1]).$($Matches[2])"
    }
    if ($normalized -match "^(\d{2})$") {
        return [double]"4.$($Matches[1])"
    }
    return $null
}

function Convert-OcrDate {
    param([string]$Text)
    $compact = $Text -replace "\s+", ""
    $compact = $compact -replace ([string][char]0xFF0E), "."
    if ($compact -match "(\d{4})\.(\d{2})\.(\d{1,2})") {
        return "{0}-{1}-{2:D2}" -f $Matches[1], $Matches[2], [int]$Matches[3]
    }
    return $null
}

$repoRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$resolvedOutDir = Join-Path $repoRoot $OutDir
New-Item -ItemType Directory -Force -Path $resolvedOutDir | Out-Null

$result = Get-Content -Raw -Encoding UTF8 -Path $ResultJson | ConvertFrom-Json
if (-not $result.screenshot -or -not (Test-Path $result.screenshot)) {
    throw "Result JSON does not point to an existing screenshot."
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$cropDir = Join-Path $resolvedOutDir "$timestamp-ocr-crops"
New-Item -ItemType Directory -Force -Path $cropDir | Out-Null

$image = [System.Drawing.Bitmap]::FromFile($result.screenshot)
try {
    $crops = @{
        service = @(250, 330, 250, 130)
        item = @(45, 605, 100, 55)
        response = @(220, 605, 100, 55)
        logistics = @(395, 605, 105, 55)
        afterSales = @(540, 580, 150, 90)
    }

    foreach ($name in $crops.Keys) {
        $values = $crops[$name]
        New-ScaledCrop `
            -Image $image `
            -X $values[0] `
            -Y $values[1] `
            -Width $values[2] `
            -Height $values[3] `
            -OutPath (Join-Path $cropDir "$name.png")
    }
} finally {
    $image.Dispose()
}

$ocrZh = Invoke-WindowsOcr -ImagePath $result.screenshot -LanguageTag "zh-Hans"
$ocrEn = Invoke-WindowsOcr -ImagePath $result.screenshot -LanguageTag "en-US"

$scoreTexts = @{}
foreach ($name in $crops.Keys) {
    $scoreTexts[$name] = Invoke-WindowsOcr -ImagePath (Join-Path $cropDir "$name.png") -LanguageTag "en-US"
}

$compactZh = $ocrZh -replace "\s+", ""
$category = $null
$labelCategory = New-Text @(0x8003, 0x6838, 0x7C7B, 0x76EE)
$labelRightAngle = New-Text @(0x3009)
$categoryIndex = $compactZh.IndexOf($labelCategory)
if ($categoryIndex -ge 0) {
    $categoryStart = $categoryIndex + $labelCategory.Length
    if ($categoryStart -lt $compactZh.Length -and ($compactZh[$categoryStart] -eq ":" -or [int][char]$compactZh[$categoryStart] -eq 0xFF1A)) {
        $categoryStart++
    }
    $categoryEnd = $compactZh.IndexOf($labelRightAngle, $categoryStart)
    if ($categoryEnd -gt $categoryStart) {
        $category = $compactZh.Substring($categoryStart, $categoryEnd - $categoryStart)
    }
}

$peerStatus = $null
$labelHigherThan = New-Text @(0x9AD8, 0x4E8E)
$labelExceeding = New-Text @(0x8D85, 0x8FC7)
$labelLagging = New-Text @(0x843D, 0x540E)
$labelPeer = New-Text @(0x540C, 0x884C)
$percentPattern = "[" + [regex]::Escape("%") + [regex]::Escape([string][char]0xFF05) + "]"
$statusPattern = "(" +
    [regex]::Escape($labelHigherThan) + "|" +
    [regex]::Escape($labelExceeding) + "|" +
    [regex]::Escape($labelLagging) +
    ")(\d+)" + $percentPattern + [regex]::Escape($labelPeer)
if ($compactZh -match $statusPattern) {
    $peerStatus = "$($Matches[1])$($Matches[2])%$labelPeer"
}

$deviceSerial = $result.serial
$packageName = $null
if ($result.currentActivity -match "^([^/]+)/") {
    $packageName = $Matches[1]
}

$event = [ordered]@{
    event_type = "idlefish_shop_service_score"
    schema_version = 1
    event_id = [guid]::NewGuid().ToString()
    captured_at = Get-Date -Format "yyyy-MM-ddTHH:mm:sszzz"
    source = "screenshot_ocr"
    account = [ordered]@{
        device_serial = $deviceSerial
        package_name = $packageName
        virtual_user_id = $null
        shop_id = $null
        account_alias = $null
    }
    page = [ordered]@{
        activity = $result.currentActivity
        url = $null
    }
    metrics = [ordered]@{
        category = $category
        updated_date = Convert-OcrDate $ocrZh
        service_score = Convert-OcrScore $scoreTexts.service
        service_score_peer_status = $peerStatus
        item_quality_score = Convert-OcrScore $scoreTexts.item
        response_speed_score = Convert-OcrScore $scoreTexts.response
        logistics_score = Convert-OcrScore $scoreTexts.logistics
        after_sales_score = Convert-OcrScore $scoreTexts.afterSales
    }
    raw = [ordered]@{
        ui_texts = @($result.uiTexts)
        ocr_zh = $ocrZh
        ocr_en = $ocrEn
        ocr_score_texts = $scoreTexts
        screenshot = $result.screenshot
        api = $null
        payload_hash = $null
    }
}

$eventPath = Join-Path $resolvedOutDir "$timestamp-ocr-event.json"
$event | ConvertTo-Json -Depth 12 | Set-Content -Path $eventPath -Encoding UTF8

[ordered]@{
    eventPath = $eventPath
    screenshot = $result.screenshot
    metrics = $event.metrics
    ocrScoreTexts = $scoreTexts
} | ConvertTo-Json -Depth 8
