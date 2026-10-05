# Redesign round: a second set of app icon candidates. Dot-sourced at the end of
# icon-designs.ps1, so these are ordinary catalogue entries that use its helpers
# and Tile; generate-icons.ps1 -Design <key> adopts one. Each carries
# Set = 'redesigns', which generate-candidates.ps1 -Set redesigns renders into
# icons/redesigns/ with its own contact sheet.

# points along a sine wave from $x1 to $x2 around $y, for wavy lines and edges
function WavePoints([single]$x1, [single]$x2, [single]$y, [single]$amp, [single]$period, [single]$phase = 0) {
    $points = New-Object System.Collections.Generic.List[System.Drawing.PointF]
    for ($x = $x1; $x -le $x2; $x += 6) {
        $points.Add((Pt $x ($y + $amp * [math]::Sin((($x - $x1) / $period) * 2 * [math]::PI + $phase))))
    }
    return , $points.ToArray()
}

# rounded bars centred on $cy, side by side around $cx
function Bars($g, $brush, [single]$cx, [single]$cy, [single[]]$heights, [single]$width, [single]$gap) {
    $x = $cx - (($heights.Count * $width + ($heights.Count - 1) * $gap) / 2)
    foreach ($h in $heights) {
        $g.FillPath($brush, (RoundedPath $x ($cy - $h / 2) $width $h ($width / 2)))
        $x += $width + $gap
    }
}

# a translucent white brush, for layered glass and card effects
function Veil([int]$alpha) {
    return New-Object System.Drawing.SolidBrush((Argb $alpha '#FFFFFF'))
}

$IconDesigns['pod-antenna'] = @{
    Title = 'Pod with antenna'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#38D486' '#0C8050')
        $white = RoundPen '#FFFFFF' 18
        $g.DrawLine($white, 256, 230, 256, 150)
        $g.FillEllipse((Solid '#FFFFFF'), 236, 112, 40, 40)
        SignalArcs $g $white 256 132 @(52, 92) 0 70
        SignalArcs $g $white 256 132 @(52, 92) 180 70
        $g.FillPath((Solid '#F2FFF7'), (RoundedPath 96 228 320 150 75))
        foreach ($cx in @(178, 334)) { $g.FillEllipse((Solid '#2DB872'), ($cx - 40), 263, 80, 80) }
        $g.FillEllipse((Solid '#0C6B43'), 212, 259, 88, 88)
        DrawPlayTriangle $g (Solid '#FFFFFF') 260 303 44
    }
}

$IconDesigns['feed-play'] = @{
    Title = 'Feed arcs + play'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#FFB347' '#F2621C')
        $white = RoundPen '#FFFFFF' 46
        $g.DrawArc($white, 10, 236, 280, 280, 270, 90)
        $g.DrawArc($white, -80, 146, 460, 460, 270, 90)
        DrawPlayTriangle $g (Solid '#FFFFFF') 160 366 76
    }
}

$IconDesigns['equalizer'] = @{
    Title = 'Equalizer'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#2A1B5C' '#110A28')
        Bars $g (LinearBrush 0 96 0 416 '#FF6CAB' '#7366FF') 256 256 @(130, 236, 320, 200, 112) 44 20
    }
}

$IconDesigns['vinyl'] = @{
    Title = 'Vinyl record'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (Solid '#1C1B22')
        $g.FillEllipse((Solid '#08080B'), 66, 66, 380, 380)
        foreach ($r in @(172, 150, 128, 106)) {
            $g.DrawEllipse((New-Object System.Drawing.Pen((Color '#2C2B36'), 4)), (256 - $r), (256 - $r), (2 * $r), (2 * $r))
        }
        $g.DrawArc((RoundPen (Argb 70 '#FFFFFF') 14), 96, 96, 320, 320, 200, 50)
        $g.FillEllipse((LinearBrush 180 180 332 332 '#FF5E62' '#FF9966'), 180, 180, 152, 152)
        DrawPlayTriangle $g (Solid '#1C1B22') 262 256 58
    }
}

$IconDesigns['cassette'] = @{
    Title = 'Cassette'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#FFD166' '#F4845F')
        $g.FillPath((Solid '#2B2D42'), (RoundedPath 82 142 348 228 34))
        $g.FillPath((Solid '#EDF2F4'), (RoundedPath 114 172 284 66 16))
        $g.FillPath((Solid '#8D99AE'), (RoundedPath 160 262 192 72 36))
        foreach ($cx in @(206, 306)) {
            $g.FillEllipse((Solid '#EDF2F4'), ($cx - 28), 270, 56, 56)
            $g.FillEllipse((Solid '#2B2D42'), ($cx - 11), 287, 22, 22)
        }
    }
}

$IconDesigns['mic-waves'] = @{
    Title = 'Mic + waves'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#2E6BFF' '#7A3CFF')
        $g.FillPath((Solid '#FFFFFF'), (RoundedPath 212 108 88 172 44))
        $white = RoundPen '#FFFFFF' 22
        $g.DrawArc($white, 178, 168, 156, 156, 0, 180)
        $g.DrawLine($white, 256, 324, 256, 384)
        $g.DrawLine($white, 204, 392, 308, 392)
        $near = RoundPen (Argb 200 '#FFFFFF') 18
        $far = RoundPen (Argb 110 '#FFFFFF') 18
        SignalArcs $g $near 256 196 @(132) 0 56
        SignalArcs $g $near 256 196 @(132) 180 56
        SignalArcs $g $far 256 196 @(178) 0 52
        SignalArcs $g $far 256 196 @(178) 180 52
    }
}

$IconDesigns['queue-stack'] = @{
    Title = 'Episode queue'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#14C2B8' '#0A6F96')
        $g.FillPath((Veil 100), (RoundedPath 154 108 204 140 26))
        $g.FillPath((Veil 170), (RoundedPath 124 150 264 160 28))
        $g.FillPath((Solid '#FFFFFF'), (RoundedPath 94 200 324 200 32))
        DrawPlayTriangle $g (Solid '#0A6F96') 262 286 76
        $g.FillPath((Solid '#CDEFF0'), (RoundedPath 140 358 232 12 6))
        $g.FillPath((Solid '#14C2B8'), (RoundedPath 140 358 132 12 6))
    }
}

$IconDesigns['satellite-dish'] = @{
    Title = 'Satellite dish'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#2A5298' '#152A55')
        $white = Solid '#FFFFFF'
        $g.FillPolygon($white, [System.Drawing.PointF[]]@((Pt 220 330), (Pt 262 330), (Pt 300 410), (Pt 182 410)))
        $state = $g.Save()
        $g.TranslateTransform(236, 300)
        $g.RotateTransform(-38)
        $g.FillPie($white, -132, -132, 264, 264, 0, 180)
        $g.Restore($state)
        $g.DrawLine((RoundPen '#FFFFFF' 16), 236, 300, 316, 214)
        $g.FillEllipse($white, 302, 196, 34, 34)
        SignalArcs $g (RoundPen '#FFFFFF' 18) 320 212 @(58, 98) 315 70
    }
}

$IconDesigns['rabbit-ears'] = @{
    Title = 'Retro TV antenna'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#F9D976' '#F39F86')
        $ink = '#3D2C8D'
        $pen = RoundPen $ink 16
        $g.DrawLine($pen, 256, 214, 180, 110)
        $g.DrawLine($pen, 256, 214, 332, 110)
        $g.FillEllipse((Solid $ink), 164, 94, 32, 32)
        $g.FillEllipse((Solid $ink), 316, 94, 32, 32)
        $g.FillPath((Solid $ink), (RoundedPath 96 206 320 214 44))
        $g.FillPath((Solid '#9AD0EC'), (RoundedPath 126 236 210 154 28))
        $g.FillEllipse((Solid '#F9D976'), 354, 256, 34, 34)
        $g.FillEllipse((Solid '#F9D976'), 354, 316, 34, 34)
        DrawPlayTriangle $g (Solid $ink) 236 313 56
    }
}

$IconDesigns['a-play'] = @{
    Title = 'A with a play cut'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#8E2DE2' '#E33C9E')
        # wide legs leave room for a play triangle where the crossbar would be
        $g.DrawLines((RoundPen '#FFFFFF' 52), [System.Drawing.PointF[]]@((Pt 120 414), (Pt 256 108), (Pt 392 414)))
        DrawPlayTriangle $g (Solid '#FFFFFF') 258 328 76
    }
}

$IconDesigns['p-ring'] = @{
    Title = 'P with a ring'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (Solid '#0F172A')
        $pen = RoundPen (LinearBrush 150 110 360 410 '#38BDF8' '#818CF8') 56
        $g.DrawLine($pen, 176, 128, 176, 402)
        $g.DrawEllipse($pen, 176, 128, 178, 178)
        DrawPlayTriangle $g (Solid '#F472B6') 270 217 50
    }
}

$IconDesigns['orb'] = @{
    Title = 'Glossy orb'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (Solid '#0B0B14')
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $path.AddEllipse(88, 88, 336, 336)
        $orb = New-Object System.Drawing.Drawing2D.PathGradientBrush($path)
        $orb.CenterPoint = (Pt 200 190)
        $orb.CenterColor = Color '#B7A6FF'
        $orb.SurroundColors = @((Color '#3B1E9E'))
        $g.FillPath($orb, $path)
        $g.FillEllipse((Veil 80), 150, 128, 120, 66)
        DrawPlayTriangle $g (Solid '#FFFFFF') 268 262 112
    }
}

$IconDesigns['duotone'] = @{
    Title = 'Duotone split'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (Solid '#FF4D6D')
        $g.FillPolygon((Solid '#3A0CA3'), [System.Drawing.PointF[]]@((Pt 520 -10), (Pt 520 520), (Pt -10 520)))
        $g.FillEllipse((Solid '#FFFFFF'), 140, 140, 232, 232)
        DrawPlayTriangle $g (Solid '#1B1033') 264 256 98
    }
}

$IconDesigns['pixel-play'] = @{
    Title = 'Pixel play'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (Solid '#111827')
        # nine rows, widest in the middle: a play triangle in 36 px blocks
        $rows = @(1, 2, 3, 4, 5, 4, 3, 2, 1)
        $colors = @('#22D3EE', '#38BDF8', '#60A5FA', '#7C8CF8', '#818CF8', '#A78BFA', '#B98AFA', '#C084FC', '#E879F9')
        for ($r = 0; $r -lt $rows.Count; $r++) {
            for ($c = 0; $c -lt $rows[$r]; $c++) {
                $g.FillRectangle((Solid $colors[$r]), (176 + $c * 36), (94 + $r * 36), 32, 32)
            }
        }
    }
}

$IconDesigns['ripple'] = @{
    Title = 'Ripples'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#43E97B' '#1FB5A8')
        foreach ($ring in @(@(196, 80), @(150, 150), @(104, 225))) {
            $r = $ring[0]
            $g.DrawEllipse((New-Object System.Drawing.Pen((Argb $ring[1] '#FFFFFF'), 22)), (256 - $r), (256 - $r), (2 * $r), (2 * $r))
        }
        $g.FillEllipse((Solid '#FFFFFF'), 190, 190, 132, 132)
        DrawPlayTriangle $g (Solid '#0F766E') 262 256 56
    }
}

$IconDesigns['sunrise'] = @{
    Title = 'Sunrise'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#FF5F6D' '#FFC371')
        $g.FillPie((Solid '#FFF4D6'), 146, 196, 220, 220, 180, 180)
        $rays = RoundPen '#FFF4D6' 18
        foreach ($a in @(200, 235, 270, 305, 340)) {
            $rad = $a * [math]::PI / 180
            $g.DrawLine($rays, [single](256 + 142 * [math]::Cos($rad)), [single](306 + 142 * [math]::Sin($rad)),
                [single](256 + 178 * [math]::Cos($rad)), [single](306 + 178 * [math]::Sin($rad)))
        }
        $wave = RoundPen '#FFFFFF' 22
        $g.DrawLines($wave, (WavePoints 96 416 340 12 106))
        $g.DrawLines($wave, (WavePoints 126 386 386 10 86 1.5))
        $g.DrawLines($wave, (WavePoints 166 346 428 8 60))
    }
}

$IconDesigns['glass-play'] = @{
    Title = 'Frosted glass'; Set = 'redesigns'
    Draw  = {
        param($g)
        MeshTile $g
        $card = RoundedPath 116 116 280 280 72
        $g.FillPath((Veil 80), $card)
        $g.DrawPath((New-Object System.Drawing.Pen((Argb 150 '#FFFFFF'), 5)), $card)
        DrawPlayTriangle $g (Solid '#FFFFFF') 266 256 104
    }
}

$IconDesigns['neon'] = @{
    Title = 'Neon'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (Solid '#0A0A12')
        foreach ($layer in @(@(64, 22), @(42, 45), @(26, 90), @(14, 255))) {
            $g.DrawEllipse((New-Object System.Drawing.Pen((Argb $layer[1] '#FF2E97'), $layer[0])), 106, 106, 300, 300)
        }
        $tri = [System.Drawing.PointF[]]@((Pt 222 186), (Pt 222 326), (Pt 330 256))
        foreach ($layer in @(@(40, 30), @(24, 70))) {
            $g.DrawPolygon((RoundPen (Argb $layer[1] '#2EF2FF') $layer[0]), $tri)
        }
        $g.FillPolygon((Solid '#2EF2FF'), $tri)
        $g.DrawPolygon((RoundPen '#2EF2FF' 10), $tri)
    }
}

$IconDesigns['paper-waves'] = @{
    Title = 'Paper waves'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (Solid '#FFE8D6')
        foreach ($layer in @(@(270, '#FFB4A2', 0), @(334, '#E5989B', 2), @(396, '#B5838D', 4))) {
            $path = New-Object System.Drawing.Drawing2D.GraphicsPath
            $path.AddLines((WavePoints 10 502 $layer[0] 22 246 $layer[2]))
            $path.AddLine(502, 520, 10, 520)
            $path.CloseFigure()
            $g.FillPath((Solid $layer[1]), $path)
        }
        $g.FillEllipse((Solid '#6D6875'), 186, 104, 140, 140)
        DrawPlayTriangle $g (Solid '#FFE8D6') 262 174 52
    }
}

$IconDesigns['seek-capsule'] = @{
    Title = 'Seek bar'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 0 22 0 490 '#243B55' '#141E30')
        $g.FillPath((Veil 70), (RoundedPath 84 232 344 48 24))
        $g.FillPath((LinearBrush 84 0 304 0 '#00C6FF' '#0072FF'), (RoundedPath 84 232 220 48 24))
        $g.FillEllipse((Solid '#FFFFFF'), 246, 198, 116, 116)
        DrawPlayTriangle $g (Solid '#0072FF') 309 256 46
    }
}

$IconDesigns['hex-badge'] = @{
    Title = 'Hex badge'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (Solid '#0E1F1C')
        $hex = New-Object System.Collections.Generic.List[System.Drawing.PointF]
        for ($i = 0; $i -lt 6; $i++) {
            $a = (60 * $i - 90) * [math]::PI / 180
            $hex.Add((Pt (256 + 196 * [math]::Cos($a)) (256 + 196 * [math]::Sin($a))))
        }
        $path = New-Object System.Drawing.Drawing2D.GraphicsPath
        $path.AddPolygon($hex.ToArray())
        $g.FillPath((LinearBrush 60 60 452 452 '#38EF7D' '#11998E'), $path)
        $g.DrawPath((RoundPen (Argb 90 '#FFFFFF') 10), $path)
        DrawPlayTriangle $g (Solid '#FFFFFF') 268 256 116
    }
}

$IconDesigns['orbit'] = @{
    Title = 'Orbit'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#302B63' '#0F0C29')
        foreach ($star in @(@(112, 120, 10), @(392, 104, 8), @(420, 380, 12), @(96, 360, 7))) {
            $g.FillEllipse((Solid '#FFFFFF'), $star[0], $star[1], $star[2], $star[2])
        }
        $ring = New-Object System.Drawing.Pen((Color '#FFD3A5'), 18)
        # the back half of the ring goes behind the planet, the front half over it
        foreach ($half in @(180, 0)) {
            $state = $g.Save()
            $g.TranslateTransform(256, 256)
            $g.RotateTransform(-22)
            $g.DrawArc($ring, -190, -66, 380, 132, $half, 180)
            $g.Restore($state)
            if ($half -eq 180) {
                $g.FillEllipse((LinearBrush 160 160 352 352 '#FF9A8B' '#FF3D77'), 160, 160, 192, 192)
                DrawPlayTriangle $g (Solid '#FFFFFF') 262 256 78
            }
        }
    }
}

$IconDesigns['mesh-antenna'] = @{
    Title = 'Mesh + antenna'; Set = 'redesigns'
    Draw  = {
        param($g)
        MeshTile $g
        $g.FillEllipse((Solid '#F7F7FA'), 126, 126, 260, 260)
        $ink = '#1B1230'
        $pen = RoundPen $ink 22
        $g.DrawLines($pen, [System.Drawing.PointF[]]@((Pt 206 340), (Pt 256 212), (Pt 306 340)))
        $g.DrawLine($pen, 226, 290, 286, 290)
        $g.FillEllipse((Solid $ink), 232, 176, 48, 48)
        SignalArcs $g (RoundPen $ink 18) 256 200 @(58, 96) 0 70
        SignalArcs $g (RoundPen $ink 18) 256 200 @(58, 96) 180 70
    }
}

# the colour $t of the way from $from to $to (0..1)
function MixColor([string]$from, [string]$to, [double]$t) {
    $a = Color $from
    $b = Color $to
    return [System.Drawing.Color]::FromArgb(
        [int]($a.R + ($b.R - $a.R) * $t), [int]($a.G + ($b.G - $a.G) * $t), [int]($a.B + ($b.B - $a.B) * $t))
}

$IconDesigns['mesh-wave'] = @{
    Title = 'Mesh + waveform (current)'; Set = 'redesigns'
    Draw  = {
        param($g)
        MeshTile $g
        $g.FillEllipse((Solid '#F7F7FA'), 126, 126, 260, 260)
        # each bar takes the mesh corners it sits between: orange to pink along the
        # top, blue to violet along the bottom, so the badge echoes the tile around it.
        # Every bar spans its own gradient, through magenta, so the short ones get the
        # full colours too instead of the grey a straight orange-to-blue mix goes through.
        $heights = @(60, 120, 168, 120, 60)
        $width = 26; $gap = 16
        $left = 256 - (($heights.Count * $width + ($heights.Count - 1) * $gap) / 2)
        $span = $heights.Count * $width + ($heights.Count - 1) * $gap - $width
        for ($i = 0; $i -lt $heights.Count; $i++) {
            $x = $left + $i * ($width + $gap)
            $t = ($x - $left) / $span
            $h = $heights[$i]
            $top = 256 - $h / 2
            $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
                (Pt 0 ($top - 1)), (Pt 0 ($top + $h + 1)), (Color '#000000'), (Color '#000000'))
            $blend = New-Object System.Drawing.Drawing2D.ColorBlend(3)
            $blend.Colors = @((MixColor '#FF7A29' '#FF2E97' $t), (Color '#C93CC0'), (MixColor '#4CC2F1' '#7B4BE8' $t))
            $blend.Positions = @([single]0, [single]0.5, [single]1)
            $brush.InterpolationColors = $blend
            $g.FillPath($brush, (RoundedPath $x $top $width $h ($width / 2)))
        }
    }
}

$IconDesigns['mono-line'] = @{
    Title = 'Monoline pod'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (Solid '#F5F3EE')
        $ink = '#1F2937'
        $pen = RoundPen $ink 22
        $g.DrawPath($pen, (RoundedPath 100 236 312 150 75))
        foreach ($cx in @(180, 332)) { $g.DrawEllipse($pen, ($cx - 30), 281, 60, 60) }
        $g.FillEllipse((Solid '#F97316'), 218, 273, 76, 76)
        DrawPlayTriangle $g (Solid '#FFFFFF') 260 311 36
        $g.DrawLine($pen, 256, 236, 256, 170)
        SignalArcs $g $pen 256 170 @(44, 84) 270 110
    }
}

$IconDesigns['bookmark-play'] = @{
    Title = 'Saved episode'; Set = 'redesigns'
    Draw  = {
        param($g)
        Tile $g (LinearBrush 22 22 490 490 '#FC5C7D' '#6A82FB')
        $mark = New-Object System.Drawing.Drawing2D.GraphicsPath
        $mark.AddLines([System.Drawing.PointF[]]@((Pt 160 92), (Pt 352 92), (Pt 352 420), (Pt 256 352), (Pt 160 420)))
        $mark.CloseFigure()
        $g.FillPath((Solid '#FFFFFF'), $mark)
        DrawPlayTriangle $g (LinearBrush 220 160 320 280 '#FC5C7D' '#6A82FB') 262 216 92
    }
}
