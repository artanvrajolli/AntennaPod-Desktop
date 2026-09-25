# Generates the WiX installer wizard bitmaps (banner + dialog) in the project
# theme.
#
#   packaging/windows/banner.bmp           493x58 top banner of every wizard page
#   packaging/windows/dialog.bmp           493x312 welcome / completion backdrop
#
# The artwork reuses the app icon's gradient mesh (icons/generate-icons.ps1):
# deep #140F26 base, orange/pink/blue/violet radial glows, white play badge.
# WiX wants exactly these sizes; files are saved 24-bit BMP. The dialog bitmap
# fills the left image panel (no text over it), so it uses the full dark mesh.
# The banner sits behind the wizard's black title text, so it stays near-white
# with only a faint pastel wash and a small icon tile on the right.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$windowsDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$bannerPath = Join-Path $windowsDir 'banner.bmp'
$dialogPath = Join-Path $windowsDir 'dialog.bmp'

function Color([string]$hex) {
    return [System.Drawing.ColorTranslator]::FromHtml($hex)
}

function Argb([int]$alpha, [string]$hex) {
    $c = Color $hex
    return [System.Drawing.Color]::FromArgb($alpha, $c.R, $c.G, $c.B)
}

function Solid([string]$hex) {
    return New-Object System.Drawing.SolidBrush((Color $hex))
}

function RadialBrush([System.Drawing.Color]$center, [System.Drawing.Color]$edge, [single]$x, [single]$y, [single]$w, [single]$h) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse($x, $y, $w, $h)
    $brush = New-Object System.Drawing.Drawing2D.PathGradientBrush($path)
    $brush.CenterColor = $center
    $brush.SurroundColors = @($edge)
    return $brush
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

function DrawPlayTriangle($g, $brush, [single]$cx, [single]$cy, [single]$size) {
    $points = @(
        [System.Drawing.PointF]::new(($cx - $size * 0.42), ($cy - $size * 0.62)),
        [System.Drawing.PointF]::new(($cx - $size * 0.42), ($cy + $size * 0.62)),
        [System.Drawing.PointF]::new(($cx + $size * 0.58), $cy)
    )
    $g.FillPolygon($brush, $points)
}

function New-Graphics($bmp) {
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    return $g
}

function Save-Bmp($bmp, [string]$path) {
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Bmp)
    $bmp.Dispose()
}

# --- dialog 493x312: full dark mesh + centered play badge --------------------
$dialog = New-Object System.Drawing.Bitmap(493, 312,
    [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$g = New-Graphics $dialog
$g.FillRectangle((Solid '#140F26'), 0, 0, 493, 312)
# mesh glows, mapped from the 512 icon canvas onto 493x312
$g.FillEllipse((RadialBrush (Argb 235 '#FF7A29') (Argb 0 '#FF7A29') -38 -36 327 207), -38, -36, 327, 207)
$g.FillEllipse((RadialBrush (Argb 225 '#FF2E97') (Argb 0 '#FF2E97') 212 -24 366 232), 212, -24, 366, 232)
$g.FillEllipse((RadialBrush (Argb 215 '#4CC2F1') (Argb 0 '#4CC2F1') -58 146 385 244), -58, 146, 385, 244)
$g.FillEllipse((RadialBrush (Argb 225 '#7B4BE8') (Argb 0 '#7B4BE8') 222 146 358 227), 222, 146, 358, 227)
$g.FillEllipse((Solid '#F7F7FA'), 166, 76, 160, 160)
DrawPlayTriangle $g (Solid '#1B1230') 248 156 55
$g.Dispose()
Save-Bmp $dialog $dialogPath

# --- banner 493x58: near-white, faint wash, small icon tile on the right -----
$banner = New-Object System.Drawing.Bitmap(493, 58,
    [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$g = New-Graphics $banner
$g.FillRectangle((Solid '#F5F3FA'), 0, 0, 493, 58)
$g.FillEllipse((RadialBrush (Argb 70 '#FF7A29') (Argb 0 '#FF7A29') -30 -40 220 140), -30, -40, 220, 140)
$g.FillEllipse((RadialBrush (Argb 70 '#FF2E97') (Argb 0 '#FF2E97') 250 -50 260 150), 250, -50, 260, 150)
$g.FillEllipse((RadialBrush (Argb 55 '#4CC2F1') (Argb 0 '#4CC2F1') -40 10 240 110), -40, 10, 240, 110)
$g.FillEllipse((RadialBrush (Argb 60 '#7B4BE8') (Argb 0 '#7B4BE8') 300 0 230 120), 300, 0, 230, 120)
$tile = RoundedPath 443 8 42 42 9
$g.FillPath((Solid '#140F26'), $tile)
$g.FillEllipse((Solid '#F7F7FA'), 450, 15, 28, 28)
DrawPlayTriangle $g (Solid '#1B1230') 464 29 10
$g.Dispose()
Save-Bmp $banner $bannerPath

Write-Host "wizard bitmaps written to $windowsDir"
