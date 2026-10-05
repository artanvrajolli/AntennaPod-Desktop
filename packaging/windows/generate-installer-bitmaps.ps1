# Generates the setup wizard's backdrop in the app icon's colours.
#
#   packaging/windows/setup-backdrop.bmp   493x360, behind every main wizard page
#
# The wizard pages are 370x270 installer units; one unit is 4/3 px at 100%
# scaling, so 493x360 lands pixel for pixel. The text on the pages is drawn by
# Windows Installer on top of this (white, transparent), so the left two thirds
# stay dark for it: the icon's gradient mesh (icons/generate-icons.ps1) and a few
# broadcast rings sit on the right, and the bottom band behind the buttons is a
# shade darker with a hairline above it. The app icon itself is drawn top left,
# from icons/app.ico. Saved 24-bit BMP, which is what the Bitmap control takes.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$windowsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$root = Resolve-Path (Join-Path $windowsDir '..\..')
$backdropPath = Join-Path $windowsDir 'setup-backdrop.bmp'
$iconPath = Join-Path $root 'icons\app.ico'

$width = 493
$height = 360
# where the button band starts: 234 installer units down
$bandTop = 312

function Color([string]$hex) {
    return [System.Drawing.ColorTranslator]::FromHtml($hex)
}

function Argb([int]$alpha, [string]$hex) {
    $c = Color $hex
    return [System.Drawing.Color]::FromArgb($alpha, $c.R, $c.G, $c.B)
}

function Glow($g, [string]$hex, [int]$alpha, [single]$x, [single]$y, [single]$w, [single]$h) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse($x, $y, $w, $h)
    $brush = New-Object System.Drawing.Drawing2D.PathGradientBrush($path)
    $brush.CenterColor = Argb $alpha $hex
    $brush.SurroundColors = @((Argb 0 $hex))
    $g.FillEllipse($brush, $x, $y, $w, $h)
    $brush.Dispose()
    $path.Dispose()
}

$bmp = New-Object System.Drawing.Bitmap($width, $height,
    [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

# base: the icon's deep violet-black, a touch lighter at the top
$base = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    [System.Drawing.Point]::new(0, 0), [System.Drawing.Point]::new(0, $bandTop),
    (Color '#171030'), (Color '#100B20'))
$g.FillRectangle($base, 0, 0, $width, $bandTop)

# the icon's mesh, pushed to the right edge and partly off it
Glow $g '#FF7A29' 150 300 -90 260 220
Glow $g '#FF2E97' 150 360 20 250 220
Glow $g '#7B4BE8' 170 320 150 260 210
Glow $g '#4CC2F1' 120 250 210 220 150
# keeps the glows from reaching under the text column
$fade = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    [System.Drawing.Point]::new(250, 0), [System.Drawing.Point]::new(380, 0),
    (Argb 255 '#140E28'), (Argb 0 '#140E28'))
$g.FillRectangle($fade, 0, 0, 380, $bandTop)
$g.FillRectangle((New-Object System.Drawing.SolidBrush((Argb 255 '#140E28'))), 0, 0, 251, $bandTop)
$top = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    [System.Drawing.Point]::new(0, 0), [System.Drawing.Point]::new(0, $bandTop),
    (Argb 0 '#171030'), (Argb 90 '#100B20'))
$g.FillRectangle($top, 0, 0, $width, $bandTop)

# broadcast rings around a point on the right, the podcast mark without a logo
$cx = 418
$cy = 150
foreach ($ring in @(@(34, 70), @(62, 52), @(92, 38), @(124, 26), @(158, 16))) {
    $r = $ring[0]
    $pen = New-Object System.Drawing.Pen((Argb $ring[1] '#FFFFFF'), 1.4)
    $g.DrawEllipse($pen, ($cx - $r), ($cy - $r), ($r * 2), ($r * 2))
    $pen.Dispose()
}
$g.FillEllipse((New-Object System.Drawing.SolidBrush((Argb 235 '#F7F7FA'))), ($cx - 14), ($cy - 14), 28, 28)
$play = @(
    [System.Drawing.PointF]::new(($cx - 4), ($cy - 7)),
    [System.Drawing.PointF]::new(($cx - 4), ($cy + 7)),
    [System.Drawing.PointF]::new(($cx + 7), $cy)
)
$g.FillPolygon((New-Object System.Drawing.SolidBrush((Color '#1B1230'))), $play)

# the app icon, top left, where the page titles start below it
$icon = New-Object System.Drawing.Icon($iconPath, 256, 256)
$iconBitmap = $icon.ToBitmap()
$g.DrawImage($iconBitmap, 32, 28, 44, 44)
$iconBitmap.Dispose()
$icon.Dispose()

# button band
$g.FillRectangle((New-Object System.Drawing.SolidBrush((Color '#0C0918'))), 0, $bandTop, $width, ($height - $bandTop))
$g.DrawLine((New-Object System.Drawing.Pen((Argb 40 '#FFFFFF'), 1)), 0, $bandTop, $width, $bandTop)

$g.Dispose()
$bmp.Save($backdropPath, [System.Drawing.Imaging.ImageFormat]::Bmp)
$bmp.Dispose()
Write-Host "setup backdrop written to $backdropPath"
