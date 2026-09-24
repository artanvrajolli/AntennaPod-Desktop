# AGENTS.md — AntennaPod Desktop

Guidance for coding agents working in this repository. Current version: **0.3.0**.

## What this is

A native Windows port of [AntennaPod](https://github.com/AntennaPod/AntennaPod).
The Java engine (feed parser, model, sync, storage) was ported out of the
Android app and rebuilt on top of a JavaFX desktop UI. No Android runtime —
the Android/Androidx APIs the engine needs are re-implemented as JVM shims
inside `core`.

## Modules

- `core/` — Java library: ported AntennaPod engine
  (`de.danoeh.antennapod.*` under `core/src/main/java`)
  - `UpdateChecker` / `UpdateDownloader` — finds and fetches a newer release from
    the project's own GitHub releases; the app then runs the installer and exits
  - `model/` (feed, playback, download), `parser/` (feed, transcript),
    `net/` (discovery: Apple/fyyd/PodcastIndex, sync: gPodder/Nextcloud, ssl),
    `storage/` (SQLite-backed desktop storage, import/export OPML, preferences)
  - `android/` + `androidx/` — hand-written compatibility shims the ported
    engine depends on (Log, XML, media, collections). Keep them minimal.
  - 22 test classes (JUnit 4) in `core/src/test`.
- `app/` — JavaFX UI (14 classes under
  `app/src/main/java/de/danoeh/antennapod/desktop`): `DesktopApp` (scenes),
  `PlaybackManager` (JavaFX media playback), `TrayManager` (system tray),
  `WindowChrome` (the app-drawn title bar; the stage is undecorated),
  `WindowsTaskbar` + `ThumbBar` (ITaskbarList3 via JNA: taskbar progress and
  the media buttons under the taskbar thumbnail), `MediaKeys` (the keyboard's
  media keys), `SmtcManager` + `SmtcArtwork` (the episode in the Windows volume
  flyout and media card via WinRT SMTC), `TaskbarIcon` (the playing episode's
  artwork drawn into the
  window icon), `ThemeManager`/`SystemTheme`, `ImageCache`, `Icons`, `Launcher`
  (entry point / main class), `SeekAccent` (artwork colour for the seek bar).
  18 test classes in `app/src/test`.

## Build, test, run

Requires JDK 17+ (CI uses Microsoft Build of OpenJDK 21). Gradle wrapper only.

```bat
gradlew :core:test :app:test :app:installDist   :: build + all tests (what CI runs)
gradlew :app:run                                :: run the app from source
app\build\install\app\bin\app.bat               :: run the installed distribution
```

- Both modules use the Java 17 toolchain; JavaFX 21.0.4 via the
  `org.openjfx.javafxplugin` Gradle plugin (`app` only).
- Run task adds `--enable-native-access=javafx.media`; keep that flag when
  launching directly.
- Tests are JUnit 4 (`useJUnit()` in both build files). `core` tests are pure
  JVM; `app` tests cover theme/tray/UI helpers.
- `DesktopIntegrationTest` exercises the full storage + engine flow end to
  end — run it after touching `core/storage` or the sync engine.

## Workflow

- Every change gets committed and pushed to `main` when done. Do not ask for
  confirmation and do not wait to be told — commit with a clear message and push.

## Conventions

- Windows-first: paths, packaging (jpackage), tray and taskbar integration
  assume Windows 10/11. User data lives in `%APPDATA%\AntennaPod`.
- Native integration degrades to doing nothing rather than failing, and each
  piece has an escape hatch: `-Dantennapod.desktop.customchrome=false` restores
  the system title bar, `-Dantennapod.desktop.taskbar=false` drops the taskbar
  progress, `-Dantennapod.desktop.thumbbar=false` leaves the window procedure
  unsubclassed, `-Dantennapod.desktop.tray=false` disables the tray,
  `-Dantennapod.desktop.icon=false` keeps the plain app icon on the taskbar
  instead of drawing the playing episode's artwork into it,
  `-Dantennapod.desktop.mediakeys=false` ignores the keyboard's media keys (they
  otherwise follow the active player through the media card; with the card off
  they fall back to claimed hotkeys),
  `-Dantennapod.desktop.smtc=false` hides the episode from the
  Windows volume flyout and media card. Use these
  to isolate a fault before changing the native code.
- `core` must not depend on JavaFX; UI code lives in `app`.
- Toolbar keeps only primary actions direct (subscribe, search, refresh, sync);
  views live under the Library menu, occasional actions under More.
- The episode list scrolls the playing episode into the middle on open and on
  episode switches, once per episode, without touching the selection.
- Each subscription keeps its own episode sort, defaulting to newest-first;
  reads of legacy `sort_code` values fall back to newest, never null.
- Buttons behind background work (subscribe, search, refresh, sync-now,
  test-login) show a spinner and disable until done.
- Episode state is written through the narrow `DesktopDatabase` writers
  (`updatePlaybackState`, `setMediaDownloaded`, `clearMediaDownload`,
  `setMediaCacheFile`, `updateMediaFromFeed`). The player, downloader, cache
  and refresh each hold their own `FeedMedia` copy, and `updateMedia` rewrites
  every column from whichever copy it is given, undoing the others' changes.
  Changes spanning several statements go through `inTransaction`.
- No database queries or `MediaPlayer` calls from list cells or other FX-thread
  hot paths: load what cells show in the background (see `feedCounts`,
  `syncedPositions` in `DesktopApp`) and let the cells read the snapshot.
- Shared dependencies are declared in `core/build.gradle` (OkHttp, RxJava3,
  org.json, commons-lang3/io, jsoup, xpp3, sqlite-jdbc, slf4j) and exposed via
  `api`, so `app` inherits them. `app` adds only what is desktop-specific: JNA
  (`jna`, `jna-platform`) for the Windows shell calls, which must not leak into
  `core`.
- UTF-8 encoding is forced on all compile tasks; keep sources ASCII-safe or
  encoded UTF-8.

## Releases / CI

- `.github/workflows/release.yml` — every push to `main` builds + tests and
  uploads artifacts; pushing a `v*` tag publishes a GitHub release
  (portable zip + WiX installer via `jpackage`).
- Both workflows package through `packaging/windows/package.ps1`. Change the
  jpackage options there, never inline in a workflow, so the tag release and
  the auto release cannot ship different installers. It also runs locally
  (`-SkipInstaller` builds the zip without WiX).
- `.github/workflows/auto-release.yml` — runs every 30 minutes: if no commit
  has landed for 30 minutes and the `version` in `app/build.gradle` has no
  `v<version>` tag, it releases that version automatically. To cut a release:
  bump `version = '...'` in `app/build.gradle`, push, and stop committing —
  it appears on its own.
- Version is defined only in `app/build.gradle`; the CI reads it from there.
- `packaging/windows/main.wxs` is jpackage's own WiX template (JDK 21) with one
  addition: the exit dialog's optional checkbox starts the app when the wizard
  is closed. It is passed with `--resource-dir`, and `--win-dir-chooser` is what
  brings in the wizard it sits on - without it jpackage builds a progress-only
  installer that closes itself. Keep the file in step if the JDK's template
  changes; jpackage says `Using custom package resource [Main WiX project file]`
  when it picks it up. WiX 3 is needed to build an installer locally.
