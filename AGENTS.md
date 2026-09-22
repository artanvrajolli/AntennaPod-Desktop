# AGENTS.md — AntennaPod Desktop

Guidance for coding agents working in this repository. Current version: **0.1.10**.

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
  - 20 test classes (JUnit 4) in `core/src/test`.
- `app/` — JavaFX UI (13 classes under
  `app/src/main/java/de/danoeh/antennapod/desktop`): `DesktopApp` (scenes),
  `PlaybackManager` (JavaFX media playback), `TrayManager` (system tray),
  `WindowChrome` (the app-drawn title bar; the stage is undecorated),
  `WindowsTaskbar` + `ThumbBar` (ITaskbarList3 via JNA: taskbar progress and
  the media buttons under the taskbar thumbnail), `MediaKeys` (the keyboard's
  media keys), `TaskbarIcon` (the playing episode's artwork drawn into the
  window icon), `ThemeManager`/`SystemTheme`, `ImageCache`, `Icons`, `Launcher`
  (entry point / main class). 13 test classes in `app/src/test`.

## Build, test, run

Requires JDK 17+ (CI uses Microsoft Build of OpenJDK 21). Gradle wrapper only.

```bat
gradlew :core:test :app:installDist   :: build + all tests (what CI runs)
gradlew :app:run                      :: run the app from source
app\build\install\app\bin\app.bat     :: run the installed distribution
```

- Both modules use the Java 17 toolchain; JavaFX 21.0.4 via the
  `org.openjfx.javafxplugin` Gradle plugin (`app` only).
- Run task adds `--enable-native-access=javafx.media`; keep that flag when
  launching directly.
- Tests are JUnit 4 (`useJUnit()` in both build files). `core` tests are pure
  JVM; `app` tests cover theme/tray/UI helpers.
- `DesktopIntegrationTest` exercises the full storage + engine flow end to
  end — run it after touching `core/storage` or the sync engine.

## Conventions

- Windows-first: paths, packaging (jpackage), tray and taskbar integration
  assume Windows 10/11. User data lives in `%APPDATA%\AntennaPod`.
- Native integration degrades to doing nothing rather than failing, and each
  piece has an escape hatch: `-Dantennapod.desktop.customchrome=false` restores
  the system title bar, `-Dantennapod.desktop.taskbar=false` drops the taskbar
  progress, `-Dantennapod.desktop.thumbbar=false` leaves the window procedure
  unsubclassed, `-Dantennapod.desktop.tray=false` disables the tray,
  `-Dantennapod.desktop.icon=false` keeps the plain app icon on the taskbar
  instead of drawing the playing episode's artwork into it. Use these
  to isolate a fault before changing the native code.
- `core` must not depend on JavaFX; UI code lives in `app`.
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
