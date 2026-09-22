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

# --win-dir-chooser brings in the wizard whose exit page carries the "start the
# app" checkbox from packaging/windows/main.wxs; without it jpackage builds a
# progress-only installer that closes itself.
$setup = "AntennaPod-Desktop-Setup-$Version.exe"
Remove-Item -Recurse -Force installer -ErrorAction SilentlyContinue
Remove-Item -Force $setup -ErrorAction SilentlyContinue
& $jpackage --type exe --dest installer @common `
    --win-per-user-install --win-menu --win-shortcut --win-dir-chooser `
    --resource-dir packaging/windows `
    --win-upgrade-uuid 6f2f2b7c-6a3e-4f4a-9f4a-6b7c2f2b7c11
if ($LASTEXITCODE -ne 0) { throw "jpackage installer failed with exit code $LASTEXITCODE" }
Move-Item "installer/AntennaPod-Desktop-$Version.exe" $setup
Get-Item $setup | Select-Object Name, Length
