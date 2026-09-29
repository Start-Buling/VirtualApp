$ErrorActionPreference = "Stop"

Add-Type -AssemblyName System.Drawing

$outputPath = Join-Path $PSScriptRoot "idlefish-agent.ico"
$sizes = @(16, 24, 32, 48, 64, 128, 256)
$images = New-Object System.Collections.Generic.List[byte[]]

function New-RoundedRectanglePath {
    param(
        [float]$X,
        [float]$Y,
        [float]$Width,
        [float]$Height,
        [float]$Radius
    )

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $diameter = $Radius * 2
    $path.AddArc($X, $Y, $diameter, $diameter, 180, 90)
    $path.AddArc($X + $Width - $diameter, $Y, $diameter, $diameter, 270, 90)
    $path.AddArc($X + $Width - $diameter, $Y + $Height - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($X, $Y + $Height - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
}

foreach ($size in $sizes) {
    $bitmap = New-Object System.Drawing.Bitmap $size, $size, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.Clear([System.Drawing.Color]::Transparent)

    $scale = $size / 64.0
    $bgPath = New-RoundedRectanglePath -X (4 * $scale) -Y (4 * $scale) -Width (56 * $scale) -Height (56 * $scale) -Radius (14 * $scale)
    $bgBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        (New-Object System.Drawing.RectangleF(0, 0, $size, $size)),
        [System.Drawing.Color]::FromArgb(255, 0, 132, 132),
        [System.Drawing.Color]::FromArgb(255, 18, 72, 138),
        45
    )
    $graphics.FillPath($bgBrush, $bgPath)

    $phonePath = New-RoundedRectanglePath -X (17 * $scale) -Y (9 * $scale) -Width (30 * $scale) -Height (46 * $scale) -Radius (7 * $scale)
    $phoneBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 235, 252, 252))
    $graphics.FillPath($phoneBrush, $phonePath)

    $screenPath = New-RoundedRectanglePath -X (20 * $scale) -Y (14 * $scale) -Width (24 * $scale) -Height (33 * $scale) -Radius (4 * $scale)
    $screenBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 19, 113, 129))
    $graphics.FillPath($screenBrush, $screenPath)

    $fishBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 255, 197, 74))
    $fishPen = New-Object System.Drawing.Pen ([System.Drawing.Color]::FromArgb(255, 255, 229, 145)), (2.2 * $scale)
    $graphics.FillEllipse($fishBrush, (23 * $scale), (25 * $scale), (15 * $scale), (9 * $scale))
    $tail = @(
        (New-Object System.Drawing.PointF((38 * $scale), (29.5 * $scale))),
        (New-Object System.Drawing.PointF((45 * $scale), (24 * $scale))),
        (New-Object System.Drawing.PointF((45 * $scale), (35 * $scale)))
    )
    $graphics.FillPolygon($fishBrush, $tail)
    $graphics.DrawArc($fishPen, (25 * $scale), (26 * $scale), (8 * $scale), (6 * $scale), 210, 120)

    $buttonBrush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, 18, 72, 138))
    $graphics.FillEllipse($buttonBrush, (30 * $scale), (49 * $scale), (4 * $scale), (4 * $scale))

    $memory = New-Object System.IO.MemoryStream
    $bitmap.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
    $images.Add($memory.ToArray())

    $memory.Dispose()
    $buttonBrush.Dispose()
    $fishPen.Dispose()
    $fishBrush.Dispose()
    $screenBrush.Dispose()
    $phoneBrush.Dispose()
    $bgBrush.Dispose()
    $bgPath.Dispose()
    $phonePath.Dispose()
    $screenPath.Dispose()
    $graphics.Dispose()
    $bitmap.Dispose()
}

$stream = [System.IO.File]::Create($outputPath)
$writer = New-Object System.IO.BinaryWriter $stream

$writer.Write([UInt16]0)
$writer.Write([UInt16]1)
$writer.Write([UInt16]$images.Count)

$offset = 6 + (16 * $images.Count)
foreach ($i in 0..($images.Count - 1)) {
    $size = $sizes[$i]
    $bytes = $images[$i]
    $iconSizeByte = if ($size -eq 256) { 0 } else { $size }
    $writer.Write([byte]$iconSizeByte)
    $writer.Write([byte]$iconSizeByte)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([UInt16]1)
    $writer.Write([UInt16]32)
    $writer.Write([UInt32]$bytes.Length)
    $writer.Write([UInt32]$offset)
    $offset += $bytes.Length
}

foreach ($bytes in $images) {
    $writer.Write($bytes)
}

$writer.Dispose()
$stream.Dispose()
Write-Host "Created $outputPath"
