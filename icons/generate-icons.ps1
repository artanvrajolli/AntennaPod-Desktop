# Generates the AntennaPod Desktop app icon ("gradient mesh") at every size the
# app, the tray and the Windows installer need.
#
#   icons/app.ico                            multi-resolution icon for jpackage
#   app/src/main/resources/icons/app-icon*.png   runtime window + tray icons
#
# The artwork is drawn on a 512x512 virtual canvas and scaled down, so tweaking
# the drawing below updates every export.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$resourceDir = Join-Path $root '..\app\src\main\resources\icons'
New-Item -ItemType Directory -Force -Path $resourceDir | Out-Null

function Color([string]$hex) {
    return [System.Drawing.ColorTranslator]::FromHtml($hex)
}

function Argb([int]$alpha, [string]$hex) {
    $c = Color $hex
    return [System.Drawing.Color]::FromArgb($alpha, $c.R, $c.G, $c.B)
}

function RoundedPath([single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $d = $r * 2
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc($x + $w - $d, $y, $d, $d, 270, 90)
    $path.AddArc($x + $w - $d, $y + $h - $d, $d, $d, 0, 90)
    $path.AddArc($x, $y + $h - $d, $d, $d, 90, 90)
    $path.CloseFigure()
    return $path
}

function RadialBrush([System.Drawing.Color]$center, [System.Drawing.Color]$edge, [single]$x, [single]$y, [single]$w, [single]$h) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse($x, $y, $w, $h)
    $brush = New-Object System.Drawing.Drawing2D.PathGradientBrush($path)
    $brush.CenterColor = $center
    $brush.SurroundColors = @($edge)
    return $brush
}

function Solid([string]$hex) {
    return New-Object System.Drawing.SolidBrush((Color $hex))
}

function DrawPlayTriangle($g, $brush, [single]$cx, [single]$cy, [single]$size) {
    $points = @(
        [System.Drawing.PointF]::new(($cx - $size * 0.42), ($cy - $size * 0.62)),
        [System.Drawing.PointF]::new(($cx - $size * 0.42), ($cy + $size * 0.62)),
        [System.Drawing.PointF]::new(($cx + $size * 0.58), $cy)
    )
    $g.FillPolygon($brush, $points)
}

# the icon itself: a colour mesh behind a white play badge
$drawAppIcon = {
    param($g)
    $tile = RoundedPath 22 22 468 468 106
    $g.FillPath((Solid '#140F26'), $tile)
    $g.SetClip($tile)
    $g.FillEllipse((RadialBrush (Argb 235 '#FF7A29') (Argb 0 '#FF7A29') -40 -60 340 340), -40, -60, 340, 340)
    $g.FillEllipse((RadialBrush (Argb 225 '#FF2E97') (Argb 0 '#FF2E97') 220 -40 380 380), 220, -40, 380, 380)
    $g.FillEllipse((RadialBrush (Argb 215 '#4CC2F1') (Argb 0 '#4CC2F1') -60 240 400 400), -60, 240, 400, 400)
    $g.FillEllipse((RadialBrush (Argb 225 '#7B4BE8') (Argb 0 '#7B4BE8') 230 240 372 372), 230, 240, 372, 372)
    $g.FillEllipse((Solid '#F7F7FA'), 146, 146, 220, 220)
    DrawPlayTriangle $g (Solid '#1B1230') 258 256 96
}

function Save-Icon([scriptblock]$draw, [int]$size, [string]$path) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.ScaleTransform(($size / 512.0), ($size / 512.0))
    & $draw $g
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
}

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$rendered = @()
foreach ($size in $sizes) {
    $file = Join-Path $resourceDir "app-icon-$size.png"
    Save-Icon $drawAppIcon $size $file
    $rendered += $file
}
Copy-Item (Join-Path $resourceDir 'app-icon-256.png') (Join-Path $resourceDir 'app-icon.png') -Force

# multi-resolution .ico (PNG-compressed frames) for jpackage: EXE, shortcuts, installer
$icoPath = Join-Path $root 'app.ico'
$stream = [System.IO.File]::Create($icoPath)
$writer = New-Object System.IO.BinaryWriter($stream)
$writer.Write([UInt16]0)
$writer.Write([UInt16]1)
$writer.Write([UInt16]$rendered.Count)
$offset = 6 + ($rendered.Count * 16)
foreach ($file in $rendered) {
    $size = [int](([System.IO.Path]::GetFileNameWithoutExtension($file)) -replace 'app-icon-', '')
    $dimension = $size
    if ($dimension -ge 256) { $dimension = 0 }
    $bytes = [System.IO.File]::ReadAllBytes($file)
    $writer.Write([byte]$dimension)
    $writer.Write([byte]$dimension)
    $writer.Write([byte]0)
    $writer.Write([byte]0)
    $writer.Write([UInt16]1)
    $writer.Write([UInt16]32)
    $writer.Write([UInt32]$bytes.Length)
    $writer.Write([UInt32]$offset)
    $offset += $bytes.Length
}
foreach ($file in $rendered) {
    $writer.Write([System.IO.File]::ReadAllBytes($file))
}
$writer.Flush()
$writer.Close()
$stream.Close()

Write-Host "app icon written to $resourceDir"
Write-Host "ico written to $icoPath"
