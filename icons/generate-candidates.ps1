# Renders every design in icon-designs.ps1 for side-by-side comparison, without
# touching the shipped icon:
#
#   icons/candidates/NN-<design>.png   512 px render of each design
#   icons/candidates/contact-sheet.png  all designs, large and at the real
#                                       taskbar/tray sizes on dark and light
#
#   powershell -ExecutionPolicy Bypass -File icons/generate-candidates.ps1
#
# Adopt one with: icons/generate-icons.ps1 -Design <design>
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $root 'icon-designs.ps1')

$outDir = Join-Path $root 'candidates'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Get-ChildItem $outDir -Filter '*.png' | Remove-Item

$keys = @($IconDesigns.Keys)
for ($i = 0; $i -lt $keys.Count; $i++) {
    Save-Icon $IconDesigns[$keys[$i]].Draw 512 (Join-Path $outDir ('{0:D2}-{1}.png' -f $i, $keys[$i]))
}

# contact sheet: one cell per design - a 192 px render, its number and name,
# then 48/32/24/16 px renders on a dark and a light strip (taskbar themes)
$columns = 6
$cellW = 240; $cellH = 360
$rows = [math]::Ceiling($keys.Count / $columns)
$sheet = New-Object System.Drawing.Bitmap(($columns * $cellW), ($rows * $cellH + 56))
$g = [System.Drawing.Graphics]::FromImage($sheet)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.TextRenderingHint = [System.Drawing.Text.TextRenderingHint]::AntiAliasGridFit
$g.Clear((Color '#E9EAEE'))

$titleFont = New-Object System.Drawing.Font('Segoe UI Semibold', 16)
$labelFont = New-Object System.Drawing.Font('Segoe UI Semibold', 11)
$ink = Solid '#1C1D22'
$g.DrawString('AntennaPod Desktop - icon candidates (00 is the shipped icon)', $titleFont, $ink, 20, 14)

$smallSizes = @(48, 32, 24, 16)
for ($i = 0; $i -lt $keys.Count; $i++) {
    $design = $IconDesigns[$keys[$i]]
    $x = ($i % $columns) * $cellW
    $y = [math]::Floor($i / $columns) * $cellH + 56

    $big = Render-Icon $design.Draw 192
    $g.DrawImageUnscaled($big, ($x + 24), ($y + 8))
    $big.Dispose()
    $g.DrawString(('{0:D2}  {1}' -f $i, $design.Title), $labelFont, $ink, ($x + 22), ($y + 206))
    $g.DrawString($keys[$i], $labelFont, (Solid '#6B6E78'), ($x + 22), ($y + 226))

    foreach ($strip in @(@{ Top = 256; Back = '#202020' }, @{ Top = 308; Back = '#F3F3F3' })) {
        $g.FillRectangle((Solid $strip.Back), ($x + 16), ($y + $strip.Top), ($cellW - 32), 48)
        $cx = $x + 24
        foreach ($size in $smallSizes) {
            $small = Render-Icon $design.Draw $size
            $g.DrawImageUnscaled($small, $cx, ($y + $strip.Top + [int]((48 - $size) / 2)))
            $small.Dispose()
            $cx += $size + 14
        }
    }
}
$g.Dispose()
$sheetPath = Join-Path $outDir 'contact-sheet.png'
$sheet.Save($sheetPath, [System.Drawing.Imaging.ImageFormat]::Png)
$sheet.Dispose()

Write-Host "$($keys.Count) designs rendered to $outDir"
Write-Host "contact sheet: $sheetPath"
