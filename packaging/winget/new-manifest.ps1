<#
Writes the winget manifests for a published release, ready to submit to
https://github.com/microsoft/winget-pkgs (copy the folder it prints into
manifests/ of a winget-pkgs fork and open a pull request, or use
`wingetcreate submit <folder>`).

The package is the portable zip: winget unpacks it and puts an
`antennapod-desktop` command on the PATH. It runs like the unzipped release,
so the library stays in %APPDATA%\AntennaPod and is shared with an installed copy.

  pwsh packaging/winget/new-manifest.ps1                 # version from app/build.gradle
  pwsh packaging/winget/new-manifest.ps1 -Version 0.3.2

The release must already be published: the zip's SHA-256 is taken from the
GitHub release (or computed from a download when GitHub has none).
#>
param(
    [string]$Version,
    [string]$Repository = 'artanvrajolli/AntennaPod-Desktop',
    [string]$PackageIdentifier = 'artanvrajolli.AntennaPodDesktop',
    [string]$OutDir = (Join-Path $PSScriptRoot 'manifests')
)
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..')

if (-not $Version) {
    $gradle = Get-Content (Join-Path $root 'app\build.gradle') -Raw
    if ($gradle -notmatch "version\s*=\s*'([^']+)'") { throw 'No version in app/build.gradle' }
    $Version = $Matches[1]
}
$tag = "v$Version"
$zipName = 'AntennaPod-Desktop-Windows.zip'

$release = Invoke-RestMethod "https://api.github.com/repos/$Repository/releases/tags/$tag" `
    -Headers @{ 'User-Agent' = 'antennapod-desktop-winget' }
$asset = $release.assets | Where-Object { $_.name -eq $zipName } | Select-Object -First 1
if (-not $asset) { throw "Release $tag has no $zipName" }
$url = $asset.browser_download_url

if ($asset.digest -and $asset.digest -like 'sha256:*') {
    $sha = $asset.digest.Substring(7).ToUpperInvariant()
} else {
    $temp = Join-Path ([IO.Path]::GetTempPath()) "antennapod-$Version.zip"
    Invoke-WebRequest $url -OutFile $temp
    $sha = (Get-FileHash $temp -Algorithm SHA256).Hash
    Remove-Item $temp
}
$date = ([datetime]$release.published_at).ToString('yyyy-MM-dd')

# winget-pkgs layout: manifests/<first letter>/<publisher>/<name>/<version>/
$parts = $PackageIdentifier.Split('.')
$dir = Join-Path $OutDir (Join-Path $parts[0].Substring(0, 1).ToLowerInvariant() `
    (Join-Path $parts[0] (Join-Path $parts[1] $Version)))
New-Item -ItemType Directory -Force $dir | Out-Null
$schema = '1.6.0'

@"
# yaml-language-server: `$schema=https://aka.ms/winget-manifest.version.$schema.schema.json
PackageIdentifier: $PackageIdentifier
PackageVersion: $Version
DefaultLocale: en-US
ManifestType: version
ManifestVersion: $schema
"@ | Set-Content -Encoding utf8 (Join-Path $dir "$PackageIdentifier.yaml")

@"
# yaml-language-server: `$schema=https://aka.ms/winget-manifest.installer.$schema.schema.json
PackageIdentifier: $PackageIdentifier
PackageVersion: $Version
InstallerType: zip
NestedInstallerType: portable
NestedInstallerFiles:
- RelativeFilePath: AntennaPod-Desktop\AntennaPod-Desktop.exe
  PortableCommandAlias: antennapod-desktop
ReleaseDate: $date
Installers:
- Architecture: x64
  InstallerUrl: $url
  InstallerSha256: $sha
ManifestType: installer
ManifestVersion: $schema
"@ | Set-Content -Encoding utf8 (Join-Path $dir "$PackageIdentifier.installer.yaml")

@"
# yaml-language-server: `$schema=https://aka.ms/winget-manifest.defaultLocale.$schema.schema.json
PackageIdentifier: $PackageIdentifier
PackageVersion: $Version
PackageLocale: en-US
Publisher: artanvrajolli
PublisherUrl: https://github.com/artanvrajolli
PublisherSupportUrl: https://github.com/$Repository/issues
PackageName: AntennaPod Desktop
PackageUrl: https://github.com/$Repository
License: GPL-3.0
LicenseUrl: https://github.com/$Repository/blob/main/LICENSE
ShortDescription: A Windows port of the open-source AntennaPod podcast manager.
Description: |-
  AntennaPod Desktop brings the AntennaPod podcast engine to Windows: subscribe by URL or search,
  stream or download episodes, keep a queue, and sync subscriptions and positions with
  gPodder.net or Nextcloud. It integrates with the tray, the taskbar and the media keys.
Moniker: antennapod
Tags:
- podcast
- podcasts
- rss
- audio
- gpodder
ReleaseNotesUrl: https://github.com/$Repository/releases/tag/$tag
ManifestType: defaultLocale
ManifestVersion: $schema
"@ | Set-Content -Encoding utf8 (Join-Path $dir "$PackageIdentifier.locale.en-US.yaml")

Write-Host "Wrote the $Version manifests to $dir"
if (Get-Command winget -ErrorAction SilentlyContinue) {
    winget validate --manifest $dir
}
