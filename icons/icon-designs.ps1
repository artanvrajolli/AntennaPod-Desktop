# Shared drawing helpers and the catalogue of app icon designs, dot-sourced by
# generate-icons.ps1 (exports the chosen design) and generate-candidates.ps1
# (renders every design side by side for picking one).
#
# Every design draws on a 512x512 virtual canvas; the caller scales the
# Graphics to the target size first, so one drawing serves 16 px to 512 px.
Add-Type -AssemblyName System.Drawing

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

function LinearBrush([single]$x1, [single]$y1, [single]$x2, [single]$y2, [string]$from, [string]$to) {
    return New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        [System.Drawing.PointF]::new($x1, $y1), [System.Drawing.PointF]::new($x2, $y2), (Color $from), (Color $to))
}

function Solid([string]$hex) {
    return New-Object System.Drawing.SolidBrush((Color $hex))
}

# a round-capped, round-joined pen; $fill is a hex colour or a Brush
function RoundPen($fill, [single]$width) {
    if ($fill -is [string]) { $pen = New-Object System.Drawing.Pen((Color $fill), $width) }
    else { $pen = New-Object System.Drawing.Pen($fill, $width) }
    $pen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
    $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
    return $pen
}

function Pt([single]$x, [single]$y) {
    return [System.Drawing.PointF]::new($x, $y)
}

function DrawPlayTriangle($g, $brush, [single]$cx, [single]$cy, [single]$size) {
    $points = @(
        [System.Drawing.PointF]::new(($cx - $size * 0.42), ($cy - $size * 0.62)),
        [System.Drawing.PointF]::new(($cx - $size * 0.42), ($cy + $size * 0.62)),
        [System.Drawing.PointF]::new(($cx + $size * 0.58), $cy)
    )
    $g.FillPolygon($brush, $points)
}

# the rounded tile every design sits on; fills it and clips to it
function Tile($g, $brush) {
    $tile = RoundedPath 22 22 468 468 106
    $g.FillPath($brush, $tile)
    $g.SetClip($tile)
}

# the "gradient mesh" background of the shipped icon
function MeshTile($g) {
    Tile $g (Solid '#140F26')
    $g.FillEllipse((RadialBrush (Argb 235 '#FF7A29') (Argb 0 '#FF7A29') -40 -60 340 340), -40, -60, 340, 340)
    $g.FillEllipse((RadialBrush (Argb 225 '#FF2E97') (Argb 0 '#FF2E97') 220 -40 380 380), 220, -40, 380, 380)
    $g.FillEllipse((RadialBrush (Argb 215 '#4CC2F1') (Argb 0 '#4CC2F1') -60 240 400 400), -60, 240, 400, 400)
    $g.FillEllipse((RadialBrush (Argb 225 '#7B4BE8') (Argb 0 '#7B4BE8') 230 240 372 372), 230, 240, 372, 372)
}

# arcs radiating from ($cx,$cy): one per radius, centred on $angle (degrees,
# clockwise from 3 o'clock), each $sweep degrees wide
function SignalArcs($g, $pen, [single]$cx, [single]$cy, [single[]]$radii, [single]$angle, [single]$sweep) {
    foreach ($r in $radii) {
        $g.DrawArc($pen, ($cx - $r), ($cy - $r), (2 * $r), (2 * $r), ($angle - $sweep / 2), $sweep)
    }
}

# Renders a design at $size px into a new 32-bit bitmap (caller disposes it).
function Render-Icon([scriptblock]$draw, [int]$size) {
    $bmp = New-Object System.Drawing.Bitmap($size, $size)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.ScaleTransform(($size / 512.0), ($size / 512.0))
    & $draw $g
    $g.Dispose()
    return $bmp
}

function Save-Icon([scriptblock]$draw, [int]$size, [string]$path) {
    $bmp = Render-Icon $draw $size
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
}

# Design catalogue, in presentation order; keys are what generate-icons.ps1
# -Design takes. $ShippedDesign is the app icon: generate-icons.ps1 exports it by
# default and the contact sheets show it as 00. It is 'mesh-wave', from the
# redesign round; 'gradient-mesh' below is the icon it replaced.
$ShippedDesign = 'mesh-wave'
$IconDesigns = [ordered]@{}

$IconDesigns['gradient-mesh'] = @{
    Title = 'Gradient mesh (previous)'
    Draw  = {
        param($g)
        MeshTile $g
        $g.FillEllipse((Solid '#F7F7FA'), 146, 146, 220, 220)
        DrawPlayTriangle $g (Solid '#1B1230') 258 256 96
    }
}

$IconDesigns['signal-tower'] = @{
    Title = 'Signal tower'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#3D8BFD' '#1B45A8')
        $white = RoundPen '#FFFFFF' 30
        $g.DrawLines($white, [System.Drawing.PointF[]]@((Pt 186 420), (Pt 256 214), (Pt 326 420)))
        $g.DrawLine($white, 214, 338, 298, 338)
        $g.FillEllipse((Solid '#FFFFFF'), 222, 162, 68, 68)
        SignalArcs $g $white 256 196 @(92, 150) 0 76
        SignalArcs $g $white 256 196 @(92, 150) 180 76
    }
}

$IconDesigns['broadcast'] = @{
    Title = 'Broadcast'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#FF9A3D' '#F0337A')
        $g.FillEllipse((Solid '#FFFFFF'), 218, 218, 76, 76)
        $white = RoundPen '#FFFFFF' 34
        SignalArcs $g $white 256 256 @(100, 168) 0 84
        SignalArcs $g $white 256 256 @(100, 168) 180 84
    }
}

$IconDesigns['mesh-broadcast'] = @{
    Title = 'Mesh + broadcast'
    Draw  = {
        param($g)
        MeshTile $g
        $g.FillEllipse((Solid '#F7F7FA'), 126, 126, 260, 260)
        $ink = '#1B1230'
        $g.FillEllipse((Solid $ink), 230, 230, 52, 52)
        $pen = RoundPen $ink 22
        SignalArcs $g $pen 256 256 @(62, 102) 0 84
        SignalArcs $g $pen 256 256 @(62, 102) 180 84
    }
}

$IconDesigns['headphones'] = @{
    Title = 'Headphones'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#1FC7B6' '#0B6B80')
        $g.DrawArc((RoundPen '#FFFFFF' 34), 128, 116, 256, 256, 180, 180)
        $g.FillPath((Solid '#FFFFFF'), (RoundedPath 100 236 84 160 36))
        $g.FillPath((Solid '#FFFFFF'), (RoundedPath 328 236 84 160 36))
        DrawPlayTriangle $g (Solid '#FFFFFF') 262 318 82
    }
}

$IconDesigns['microphone'] = @{
    Title = 'Studio mic'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#7C3AED' '#2A0F5C')
        $g.FillEllipse((RadialBrush (Argb 150 '#FFB86B') (Argb 0 '#FFB86B') 60 20 400 340), 60, 20, 400, 340)
        $g.FillPath((Solid '#FFFFFF'), (RoundedPath 200 96 112 200 56))
        $grill = RoundPen '#6D3FD6' 12
        foreach ($y in 158, 196, 234) { $g.DrawLine($grill, 226, $y, 286, $y) }
        $white = RoundPen '#FFFFFF' 28
        $g.DrawArc($white, 160, 150, 192, 196, 0, 180)
        $g.DrawLine($white, 256, 348, 256, 402)
        $g.DrawLine($white, 196, 408, 316, 408)
    }
}

$IconDesigns['pea-pod'] = @{
    Title = 'Pea pod'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#1F4D36' '#0F2A1E')
        $state = $g.Save()
        $g.TranslateTransform(256, 262)
        $g.RotateTransform(-32)
        $pod = New-Object System.Drawing.Drawing2D.GraphicsPath
        $pod.AddBezier((Pt -214 0), (Pt -110 -122), (Pt 110 -122), (Pt 214 0))
        $pod.AddBezier((Pt 214 0), (Pt 110 122), (Pt -110 122), (Pt -214 0))
        $pod.CloseFigure()
        $g.FillPath((LinearBrush 0 -100 0 100 '#9BE15D' '#3FA34D'), $pod)
        $g.DrawLine((RoundPen '#3FA34D' 20), 206, 0, 244, -30)
        $pea = Solid '#E4FBC4'
        $g.FillEllipse($pea, -150, -40, 80, 80)
        $g.FillEllipse($pea, 70, -40, 80, 80)
        $g.FillEllipse((Solid '#FFFFFF'), -52, -52, 104, 104)
        $g.Restore($state)
        # the middle pea carries the play badge, upright
        DrawPlayTriangle $g (Solid '#2F7D3A') 260 262 46
    }
}

$IconDesigns['waveform'] = @{
    Title = 'Waveform'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#1E2433' '#0B0F19')
        $bars = LinearBrush 0 70 0 442 '#38E1F5' '#A45CF6'
        $heights = @(150, 272, 356, 236, 128)
        $width = 48; $gap = 22
        $x = 256 - (($heights.Count * $width + ($heights.Count - 1) * $gap) / 2)
        foreach ($h in $heights) {
            $g.FillPath($bars, (RoundedPath $x (256 - $h / 2) $width $h ($width / 2)))
            $x += $width + $gap
        }
    }
}

$IconDesigns['monogram-a'] = @{
    Title = 'Antenna A'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#FBB024' '#E4570D')
        $white = RoundPen '#FFFFFF' 46
        $g.DrawLines($white, [System.Drawing.PointF[]]@((Pt 150 414), (Pt 256 196), (Pt 362 414)))
        $g.DrawLine($white, 196, 338, 316, 338)
        SignalArcs $g (RoundPen '#FFFFFF' 26) 256 196 @(62, 112) 270 90
    }
}

$IconDesigns['speech-bubble'] = @{
    Title = 'Talk bubble'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#14AEEF' '#5B5CE6')
        $bubble = RoundedPath 96 118 320 232 76
        $bubble.AddPolygon([System.Drawing.PointF[]]@((Pt 150 320), (Pt 136 418), (Pt 236 336)))
        $bubble.FillMode = [System.Drawing.Drawing2D.FillMode]::Winding
        $g.FillPath((Solid '#FFFFFF'), $bubble)
        $ink = Solid '#4B4FD8'
        $heights = @(56, 112, 150, 112, 56)
        $width = 26; $gap = 18
        $x = 256 - (($heights.Count * $width + ($heights.Count - 1) * $gap) / 2)
        foreach ($h in $heights) {
            $g.FillPath($ink, (RoundedPath $x (234 - $h / 2) $width $h ($width / 2)))
            $x += $width + $gap
        }
    }
}

$IconDesigns['progress-ring'] = @{
    Title = 'Progress ring'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#1B2436' '#0B1120')
        $g.DrawEllipse((New-Object System.Drawing.Pen((Color '#334055'), 40)), 100, 100, 312, 312)
        $g.DrawArc((RoundPen (LinearBrush 100 100 412 412 '#FFA43A' '#FF3D8B') 40), 100, 100, 312, 312, -90, 270)
        DrawPlayTriangle $g (Solid '#FFFFFF') 266 256 124
    }
}

# the redesign round, in its own file; its entries carry Set = 'redesigns'
. (Join-Path $PSScriptRoot 'icon-designs-redesigns.ps1')
