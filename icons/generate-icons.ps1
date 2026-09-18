# Generates the AntennaPod Desktop icon concepts.
# Everything is drawn on a 512x512 virtual canvas and scaled to the requested size,
# so one drawing function produces every export size.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$concepts = Join-Path $root 'concepts'
New-Item -ItemType Directory -Force -Path $concepts | Out-Null

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

function LinearBrush([string]$from, [string]$to, [single]$x1, [single]$y1, [single]$x2, [single]$y2) {
    return New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        ([System.Drawing.PointF]::new($x1, $y1)),
        ([System.Drawing.PointF]::new($x2, $y2)),
        (Color $from), (Color $to))
}

function RadialBrush([System.Drawing.Color]$center, [System.Drawing.Color]$edge, [single]$x, [single]$y, [single]$w, [single]$h) {
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse($x, $y, $w, $h)
    $brush = New-Object System.Drawing.Drawing2D.PathGradientBrush($path)
    $brush.CenterColor = $center
    $brush.SurroundColors = @($edge)
    return $brush
}

function Pen2([string]$hex, [single]$width) {
    $pen = New-Object System.Drawing.Pen((Color $hex), $width)
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    return $pen
}

function Solid([string]$hex) {
    return New-Object System.Drawing.SolidBrush((Color $hex))
}

# paints the rounded tile and clips everything that follows to it
function Fill-Tile($g, $brush, [single]$x, [single]$y, [single]$w, [single]$h, [single]$r) {
    $path = RoundedPath $x $y $w $h $r
    $g.FillPath($brush, $path)
    $g.SetClip($path)
}

# --- shared motifs -------------------------------------------------------------

function DrawAntennaGlyph($g, $pen, [single]$cx, [single]$top, [single]$bottom, [single]$height) {
    $ball = $pen.Width * 1.55
    $g.DrawLine($pen, $cx, $top, $cx, $bottom)
    $g.FillEllipse((New-Object System.Drawing.SolidBrush($pen.Color)), ($cx - $ball / 2), ($top - $ball * 0.78), $ball, $ball)
    $g.DrawLine($pen, $cx, ($bottom - $height * 0.45), ($cx - $height * 0.42), $bottom)
    $g.DrawLine($pen, $cx, ($bottom - $height * 0.45), ($cx + $height * 0.42), $bottom)
    foreach ($radius in @(($height * 0.46), ($height * 0.68))) {
        for ($angle = 206; $angle -le 334; $angle += 9) {
            $a1 = $angle * [Math]::PI / 180
            $a2 = ($angle + 6) * [Math]::PI / 180
            $g.DrawLine($pen,
                ($cx + $radius * [Math]::Cos($a1)), ($top + $radius * [Math]::Sin($a1)),
                ($cx + $radius * [Math]::Cos($a2)), ($top + $radius * [Math]::Sin($a2)))
        }
    }
}

function DrawPlayTriangle($g, $brush, [single]$cx, [single]$cy, [single]$size) {
    $points = @(
        [System.Drawing.PointF]::new(($cx - $size * 0.42), ($cy - $size * 0.62)),
        [System.Drawing.PointF]::new(($cx - $size * 0.42), ($cy + $size * 0.62)),
        [System.Drawing.PointF]::new(($cx + $size * 0.58), $cy)
    )
    $g.FillPolygon($brush, $points)
}

function DrawWaveBars($g, $brush, [single]$cx, [single]$cy, [single]$barWidth, [single]$gap, [int[]]$heights) {
    $count = $heights.Length
    $totalWidth = $count * $barWidth + ($count - 1) * $gap
    $x = $cx - $totalWidth / 2
    foreach ($h in $heights) {
        $g.FillPath($brush, (RoundedPath $x ($cy - $h / 2) $barWidth $h ($barWidth / 2)))
        $x += $barWidth + $gap
    }
}

# --- concepts ------------------------------------------------------------------

$icons = [ordered]@{}

$icons['01-antenna-classic'] = {
    param($g)
    Fill-Tile $g (LinearBrush '#FF9A3C' '#E23B2F' 40 20 470 500) 22 22 468 468 106
    DrawAntennaGlyph $g (Pen2 '#FFFFFF' 24) 256 116 400 290
}

$icons['02-pod-capsule'] = {
    param($g)
    Fill-Tile $g (LinearBrush '#4A3AE0' '#7B4BE8' 30 20 480 500) 22 22 468 468 106
    $g.FillPath((Solid '#FFFFFF'), (RoundedPath 196 118 120 216 60))
    DrawPlayTriangle $g (Solid '#5A3FE3') 254 226 84
    $g.DrawArc((Pen2 '#FFFFFF' 24), 162, 226, 188, 188, 20, 140)
}

$icons['03-waveform-tile'] = {
    param($g)
    Fill-Tile $g (LinearBrush '#252A31' '#12151A' 0 0 512 512) 14 14 484 484 112
    DrawWaveBars $g (LinearBrush '#6FD8FF' '#0096C9' 150 160 150 380) 256 256 30 22 @(96, 168, 248, 168, 96)
}

$icons['04-monogram-a'] = {
    param($g)
    Fill-Tile $g (Solid '#101318') 14 14 484 484 112
    $pen = Pen2 '#FFFFFF' 52
    $g.DrawLine($pen, 158, 404, 256, 152)
    $g.DrawLine($pen, 354, 404, 256, 152)
    $g.DrawLine($pen, 198, 316, 314, 316)
    $g.DrawArc((Pen2 '#4CC2F1' 22), 186, 40, 140, 140, 200, 140)
}

$icons['05-vinyl-play'] = {
    param($g)
    Fill-Tile $g (LinearBrush '#23303F' '#0E141B' 0 0 512 512) 14 14 484 484 112
    $g.FillEllipse((Solid '#0B0E12'), 62, 62, 388, 388)
    foreach ($r in @(150, 128, 106)) {
        $groove = New-Object System.Drawing.Pen((Argb 70 '#FFFFFF'), 3)
        $g.DrawEllipse($groove, (256 - $r), (256 - $r), ($r * 2), ($r * 2))
    }
    $g.FillEllipse((Solid '#FF7A29'), 176, 176, 160, 160)
    DrawPlayTriangle $g (Solid '#FFFFFF') 260 256 74
}

$icons['06-radio-tower'] = {
    param($g)
    Fill-Tile $g (LinearBrush '#0A1122' '#1D3157' 0 0 512 512) 14 14 484 484 112
    foreach ($star in @(@(96, 120, 5), @(150, 78, 4), @(392, 108, 5), @(432, 168, 4), @(340, 62, 3))) {
        $g.FillEllipse((Solid '#FFFFFF'), $star[0], $star[1], ($star[2] * 2), ($star[2] * 2))
    }
    $tower = Pen2 '#EDF3FF' 20
    $g.DrawLine($tower, 236, 430, 268, 180)
    $g.DrawLine($tower, 300, 430, 268, 180)
    $g.DrawLine($tower, 245, 372, 291, 372)
    $g.DrawLine($tower, 251, 318, 285, 318)
    $g.DrawLine($tower, 256, 268, 280, 268)
    $g.FillEllipse((Solid '#FF4D5E'), 254, 152, 28, 28)
    foreach ($r in @(86, 124, 162)) {
        $g.DrawArc((Pen2 '#4CC2F1' 15), (268 - $r), (164 - $r), ($r * 2), ($r * 2), 296, 44)
    }
}

$icons['07-cassette'] = {
    param($g)
    Fill-Tile $g (LinearBrush '#26C6DA' '#7E57C2' 0 0 512 512) 22 22 468 468 106
    $g.FillPath((Solid '#F6EFE2'), (RoundedPath 66 132 380 248 26))
    $g.FillPath((Solid '#2B2F38'), (RoundedPath 126 178 260 104 18))
    $g.FillEllipse((Solid '#F6EFE2'), 168, 208, 44, 44)
    $g.FillEllipse((Solid '#F6EFE2'), 300, 208, 44, 44)
    $g.DrawLine((Pen2 '#2B2F38' 8), 190, 230, 322, 230)
    $g.FillPath((Solid '#FF7A29'), (RoundedPath 66 300 380 46 12))
}

$icons['08-synthwave'] = {
    param($g)
    Fill-Tile $g (LinearBrush '#FF2E97' '#1B0B3B' 0 0 0 512) 22 22 468 468 106
    $sun = RadialBrush (Color '#FFE29A') (Color '#FF6B4A') 116 96 280 280
    $g.FillEllipse($sun, 116, 96, 280, 280)
    for ($y = 236; $y -lt 372; $y += 34) {
        $g.DrawLine((New-Object System.Drawing.Pen((Color '#FF2E97'), 10)), 116, $y, 396, $y)
    }
    $g.DrawLine((Pen2 '#4CC2F1' 8), 40, 352, 472, 352)
    DrawAntennaGlyph $g (Pen2 '#EDFBFF' 18) 256 306 430 170
}

$icons['09-glass-orb'] = {
    param($g)
    Fill-Tile $g (Solid '#0C1016') 14 14 484 484 112
    $g.FillEllipse((RadialBrush (Color '#243749') (Color '#0C1016') 96 96 320 320), 96, 96, 320, 320)
    $g.FillEllipse((RadialBrush (Argb 255 '#8FE2FF') (Argb 60 '#0E5C8A') 116 116 280 280), 116, 116, 280, 280)
    $g.FillEllipse((RadialBrush (Argb 190 '#FFFFFF') (Argb 0 '#FFFFFF') 168 152 96 76), 168, 152, 96, 76)
    DrawWaveBars $g (Solid '#F2FBFF') 256 262 16 13 @(46, 88, 124, 88, 46)
    $g.DrawArc((Pen2 '#8FD8FF' 10), 146, 146, 220, 220, 32, 116)
}

$icons['10-neon-outline'] = {
    param($g)
    Fill-Tile $g (Solid '#04060A') 14 14 484 484 112
    foreach ($glow in @(@(38, 40), @(22, 90), @(12, 170))) {
        $pen = New-Object System.Drawing.Pen((Argb $glow[1] '#4CC2F1'), $glow[0])
        $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        DrawAntennaGlyph $g $pen 256 120 400 290
    }
    DrawAntennaGlyph $g (Pen2 '#EAFBFF' 9) 256 120 400 290
}

$icons['11-gradient-mesh'] = {
    param($g)
    Fill-Tile $g (Solid '#140F26') 22 22 468 468 106
    $g.FillEllipse((RadialBrush (Argb 235 '#FF7A29') (Argb 0 '#FF7A29') -40 -60 340 340), -40, -60, 340, 340)
    $g.FillEllipse((RadialBrush (Argb 225 '#FF2E97') (Argb 0 '#FF2E97') 220 -40 380 380), 220, -40, 380, 380)
    $g.FillEllipse((RadialBrush (Argb 215 '#4CC2F1') (Argb 0 '#4CC2F1') -60 240 400 400), -60, 240, 400, 400)
    $g.FillEllipse((RadialBrush (Argb 225 '#7B4BE8') (Argb 0 '#7B4BE8') 230 240 372 372), 230, 240, 372, 372)
    $g.FillEllipse((Solid '#F7F7FA'), 146, 146, 220, 220)
    DrawPlayTriangle $g (Solid '#1B1230') 258 256 96
}

$icons['12-pixel-antenna'] = {
    param($g)
    Fill-Tile $g (Solid '#0F1B2E') 14 14 484 484 112
    $sprite = @(
        '................',
        '.......ww.......',
        '......w..w......',
        '.....w....w.....',
        '....w......w....',
        '.......ww.......',
        '.......ww.......',
        '......oooo......',
        '.......ww.......',
        '.......ww.......',
        '......w..w......',
        '.....w....w.....',
        '....w......w....',
        '..ccc......ccc..',
        '..ccc......ccc..',
        '................'
    )
    $cell = 32
    for ($row = 0; $row -lt $sprite.Length; $row++) {
        $line = $sprite[$row]
        for ($col = 0; $col -lt $line.Length; $col++) {
            $ch = $line[$col]
            if ($ch -eq '.') { continue }
            $brush = Solid '#FFFFFF'
            if ($ch -eq 'o') { $brush = Solid '#FF7A29' }
            if ($ch -eq 'c') { $brush = Solid '#4CC2F1' }
            $g.FillRectangle($brush, ($col * $cell), ($row * $cell), $cell, $cell)
        }
    }
}

# --- export --------------------------------------------------------------------

function Export-Icon([string]$name, [scriptblock]$draw, [int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.ScaleTransform(($size / 512.0), ($size / 512.0))
    & $draw $g
    $file = Join-Path $concepts "$name.png"
    $bmp.Save($file, [System.Drawing.Imaging.ImageFormat]::Png)
    $g.Dispose()
    $bmp.Dispose()
}

foreach ($name in $icons.Keys) {
    Export-Icon $name $icons[$name] 256
    Export-Icon "$name-32" $icons[$name] 32
    Write-Host "rendered $name"
}

# contact sheet: 256 artwork, name, and a 3x zoom of the 32px version
$tileW = 340
$tileH = 400
$cols = 4
$rows = [Math]::Ceiling($icons.Count / $cols)
$sheet = New-Object System.Drawing.Bitmap(($tileW * $cols), ($tileH * $rows + 80))
$sg = [System.Drawing.Graphics]::FromImage($sheet)
$sg.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$sg.Clear((Color '#101216'))
$titleFont = New-Object System.Drawing.Font('Segoe UI', 26, [System.Drawing.FontStyle]::Bold)
$nameFont = New-Object System.Drawing.Font('Segoe UI', 15, [System.Drawing.FontStyle]::Bold)
$hintFont = New-Object System.Drawing.Font('Segoe UI', 12)
$sg.DrawString('AntennaPod Desktop - icon concepts', $titleFont, (Solid '#F2F5F8'), 24, 16)
$sg.DrawString('left: 256 px master       right: the same icon at 32 px, zoomed 3x (tray size)', $hintFont, (Solid '#8A94A0'), 24, 54)
$i = 0
foreach ($name in $icons.Keys) {
    $col = $i % $cols
    $row = [Math]::Floor($i / $cols)
    $x = $col * $tileW
    $y = 80 + $row * $tileH
    $big = [System.Drawing.Image]::FromFile((Join-Path $concepts "$name.png"))
    $sg.DrawImage($big, ($x + 22), ($y + 16), 220, 220)
    $big.Dispose()
    $small = [System.Drawing.Image]::FromFile((Join-Path $concepts "$name-32.png"))
    $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
    $sg.DrawImage($small, ($x + 258), ($y + 78), 96, 96)
    $small.Dispose()
    $sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $sg.DrawString($name, $nameFont, (Solid '#E7EDF3'), ($x + 22), ($y + 252))
    $i++
}
$sheetFile = Join-Path $root 'contact-sheet.png'
$sheet.Save($sheetFile, [System.Drawing.Imaging.ImageFormat]::Png)
$sg.Dispose()
$sheet.Dispose()
Write-Host "contact sheet: $sheetFile"
