<#
Packages an installDist build into the portable zip and the installer.

Used by both release workflows so the two can never ship different installers,
and runnable locally after `gradlew :app:installDist` (needs a JDK 17+ with
jpackage; the installer also needs WiX Toolset 3 on PATH).

  pwsh packaging/windows/package.ps1                 # version from app/build.gradle
  pwsh packaging/windows/package.ps1 -Version 1.2.3
  pwsh packaging/windows/package.ps1 -SkipInstaller  # portable zip only, no WiX

Outputs, in the repository root:
  AntennaPod-Desktop-Windows.zip
  AntennaPod-Desktop-Setup-<version>.exe
#>
param(
    [string]$Version,
    [switch]$SkipInstaller
)

$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')
Set-Location $root

if (-not $Version) {
    $match = Select-String -Path app/build.gradle -Pattern "version = '([^']+)'"
    if (-not $match) { throw "Could not read app version from app/build.gradle" }
    $Version = $match.Matches[0].Groups[1].Value
}

$jar = "app/build/install/app/lib/app-$Version.jar"
if (-not (Test-Path $jar)) {
    throw "$jar not found - run 'gradlew :app:installDist' first (or pass the matching -Version)"
}

$jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\jpackage.exe' } else { 'jpackage' }

# Options shared by the portable image and the installer.
$common = @(
    '--name', 'AntennaPod-Desktop',
    '--app-version', $Version,
    '--vendor', 'AntennaPod',
    '--input', 'app/build/install/app/lib',
    '--main-jar', "app-$Version.jar",
    '--main-class', 'de.danoeh.antennapod.desktop.Launcher',
    '--icon', 'icons/app.ico',
    '--java-options', '--enable-native-access=javafx.media'
)

Write-Host "Packaging AntennaPod Desktop $Version"

Remove-Item -Recurse -Force release/AntennaPod-Desktop -ErrorAction SilentlyContinue
Remove-Item -Force AntennaPod-Desktop-Windows.zip -ErrorAction SilentlyContinue
& $jpackage --type app-image --dest release @common
if ($LASTEXITCODE -ne 0) { throw "jpackage app-image failed with exit code $LASTEXITCODE" }
Compress-Archive -Path release/AntennaPod-Desktop -DestinationPath AntennaPod-Desktop-Windows.zip
Get-Item AntennaPod-Desktop-Windows.zip | Select-Object Name, Length

if ($SkipInstaller) { return }

# The setup wizard is our own, all of it in packaging/windows/main.wxs. That is
# why there is no --win-dir-chooser here: it would bring in jpackage's WixUI
# wizard alongside ours (the install folder is under Options... on our welcome
# page instead).
# The wizard's backdrop (setup-backdrop.bmp, ApodBackdrop in main.wxs) resolves
# via the APOD_BITMAP_DIR env var, pointed here at the resource dir: WiX expands
# $(env.VAR) at candle time to an absolute path, which survives jpackage's
# per-build temp dirs (resource-dir extras are NOT copied to its config dir,
# so a relative bitmap path cannot work). Absolute, so CI and local agree.
# --win-menu-group names the Start menu folder, which is "Unknown" without it.
$env:APOD_BITMAP_DIR = (Resolve-Path (Join-Path $PSScriptRoot '.')).Path
$setup = "AntennaPod-Desktop-Setup-$Version.exe"
Remove-Item -Recurse -Force installer -ErrorAction SilentlyContinue
Remove-Item -Force $setup -ErrorAction SilentlyContinue
& $jpackage --type exe --dest installer @common `
    --win-per-user-install --win-menu --win-menu-group 'AntennaPod' --win-shortcut `
    --resource-dir packaging/windows `
    --win-upgrade-uuid 6f2f2b7c-6a3e-4f4a-9f4a-6b7c2f2b7c11
if ($LASTEXITCODE -ne 0) { throw "jpackage installer failed with exit code $LASTEXITCODE" }
Move-Item "installer/AntennaPod-Desktop-$Version.exe" $setup
Get-Item $setup | Select-Object Name, Length
