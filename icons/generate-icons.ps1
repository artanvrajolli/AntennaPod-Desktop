# Generates the AntennaPod Desktop app icon at every size the app, the tray and
# the Windows installer need.
#
#   icons/app.ico                            multi-resolution icon for jpackage
#   icons/app-icon-preview.png               256 px preview shown in icons/README.md
#   app/src/main/resources/icons/app-icon*.png   runtime window + tray icons
#
# The designs live in icon-designs.ps1; -Design picks one by key (default: the
# shipped "gradient-mesh"). Each is drawn on a 512x512 virtual canvas and scaled
# down, so editing a design there updates every export.
#
#   powershell -ExecutionPolicy Bypass -File icons/generate-icons.ps1
#   powershell -ExecutionPolicy Bypass -File icons/generate-icons.ps1 -Design waveform
param(
    [string]$Design = 'gradient-mesh'
)
$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path $root 'icon-designs.ps1')

if (-not $IconDesigns.Contains($Design)) {
    throw "Unknown design '$Design'. Available: $($IconDesigns.Keys -join ', ')"
}
$drawAppIcon = $IconDesigns[$Design].Draw

$resourceDir = Join-Path $root '..\app\src\main\resources\icons'
New-Item -ItemType Directory -Force -Path $resourceDir | Out-Null

$sizes = @(16, 24, 32, 48, 64, 128, 256)
$rendered = @()
foreach ($size in $sizes) {
    $file = Join-Path $resourceDir "app-icon-$size.png"
    Save-Icon $drawAppIcon $size $file
    $rendered += $file
}
Copy-Item (Join-Path $resourceDir 'app-icon-256.png') (Join-Path $resourceDir 'app-icon.png') -Force
Copy-Item (Join-Path $resourceDir 'app-icon-256.png') (Join-Path $root 'app-icon-preview.png') -Force

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

Write-Host "app icon '$Design' written to $resourceDir"
Write-Host "ico written to $icoPath"
