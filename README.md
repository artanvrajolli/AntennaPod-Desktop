# AntennaPod Desktop for Windows

A native Windows port of [AntennaPod](https://github.com/AntennaPod/AntennaPod),
the open-source podcast manager. Same core engine (feed parser, search, sync),
rebuilt as a Windows desktop app with its own Java runtime bundled — no
installation of Java or anything else required.

## Download

From the [**Releases page**](https://github.com/artanvrajolli/AntennaPod-Desktop/releases)
(Windows 10/11, 64-bit):

- **Installer (recommended)** — run `AntennaPod-Desktop-Setup-*.exe`, then start
  AntennaPod Desktop from the Start menu. Per-user install in
  `%LOCALAPPDATA%\AntennaPod-Desktop`, no admin rights needed.
- **Portable** — unzip `AntennaPod-Desktop-Windows.zip` anywhere and run
  `AntennaPod-Desktop.exe`.

Your data (subscriptions, downloads, playback positions) lives in
`%APPDATA%\AntennaPod`, so both ways share the same library.

## Features

- Subscribe by URL, OPML import/export (+ HTML export), podcast search
  (Apple, fyyd, Podcast Index) with cover art in results
- Streaming + downloads with progress, per-feed auto-download rules and
  auto-delete after playing
- Playback queue, per-feed sort orders, ▶ Play-all from oldest to newest
- Sleep timer, per-feed playback speed, skip intro/ending, volume boost,
  one-click **skip silence** toggle in the player bar, favorites, chapters,
  transcripts (SRT/VTT/JSON), shownotes
- Playback history, listening statistics, system-tray integration with
  compact icon playback controls (right-click the tray icon)
- Progress bars show a ghost marker at the last position saved to your sync
  provider, and playback duration fixes keep gPodder.net episode actions clean
- Search/filter your subscriptions and episodes, light/dark/system theme
  (follows the Windows app theme by default)
- **Sync** with gPodder.net and Nextcloud (two-way subscriptions, positions,
  played state), including import from another device

## Sync setup

1. Open **Sync**, choose gPodder.net (host `gpodder.net`) or Nextcloud
   (your server host + an app password).
2. Enter username/password → Save → **Test login**.
3. **Sync now**. To pull subscriptions from your phone, use
   **Import from another device…** and pick it from the list.

## Build from source

Requirements: JDK 17+ (CI uses Microsoft Build of OpenJDK 21).

```bat
gradlew :core:test :app:installDist
```

Run: `app\build\install\app\bin\app.bat`

Package the portable app (needs a JDK with `jpackage`):

```bat
jpackage --type app-image --name "AntennaPod-Desktop" --app-version "0.1.5" ^
  --vendor "AntennaPod" --dest release --input app\build\install\app\lib ^
  --main-jar app-0.1.5.jar --main-class de.danoeh.antennapod.desktop.Launcher ^
  --java-options "--enable-native-access=javafx.media"
```

Package the single-file installer (also needs WiX Toolset v3 on PATH):

```bat
jpackage --type exe --name "AntennaPod-Desktop" --app-version "0.1.5" ^
  --vendor "AntennaPod" --dest installer --input app\build\install\app\lib ^
  --main-jar app-0.1.5.jar --main-class de.danoeh.antennapod.desktop.Launcher ^
  --java-options "--enable-native-access=javafx.media" ^
  --win-per-user-install --win-menu --win-shortcut ^
  --win-upgrade-uuid 6f2f2b7c-6a3e-4f4a-9f4a-6b7c2f2b7c11
```

Every push to `main` builds and tests automatically; every `v*` tag publishes
a ready-to-download release.

## Project structure

- `core/` — ported AntennaPod engine (`model`, feed parser, discovery,
  gPodder/Nextcloud sync) with small JVM compatibility shims, plus desktop
  storage (SQLite), updater, downloader and sync engine. Covered by 40+ tests.
- `app/` — JavaFX user interface and JavaFX-based playback manager.

## Credits and license

Built on the excellent work of the AntennaPod contributors. This project is
licensed under the GNU General Public License v3.0 — see `LICENSE`.
