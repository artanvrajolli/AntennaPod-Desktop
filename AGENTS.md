# AGENTS.md — AntennaPod Desktop

Guidance for coding agents working in this repository. Current version: **0.1.8**.

## What this is

A native Windows port of [AntennaPod](https://github.com/AntennaPod/AntennaPod).
The Java engine (feed parser, model, sync, storage) was ported out of the
Android app and rebuilt on top of a JavaFX desktop UI. No Android runtime —
the Android/Androidx APIs the engine needs are re-implemented as JVM shims
inside `core`.

## Modules

- `core/` — Java library: ported AntennaPod engine
  (`de.danoeh.antennapod.*` under `core/src/main/java`)
  - `model/` (feed, playback, download), `parser/` (feed, transcript),
    `net/` (discovery: Apple/fyyd/PodcastIndex, sync: gPodder/Nextcloud, ssl),
    `storage/` (SQLite-backed desktop storage, import/export OPML, preferences)
  - `android/` + `androidx/` — hand-written compatibility shims the ported
    engine depends on (Log, XML, media, collections). Keep them minimal.
  - 17 test classes (JUnit 4) in `core/src/test`.
- `app/` — JavaFX UI (8 classes under
  `app/src/main/java/de/danoeh/antennapod/desktop`): `DesktopApp` (scenes),
  `PlaybackManager` (JavaFX media playback), `TrayManager` (system tray),
  `ThemeManager`/`SystemTheme`, `ImageCache`, `Icons`, `Launcher` (entry
  point / main class). 3 test classes in `app/src/test`.

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

- Windows-first: paths, packaging (jpackage), and tray integration assume
  Windows 10/11. User data lives in `%APPDATA%\AntennaPod`.
- `core` must not depend on JavaFX; UI code lives in `app`.
- Dependencies are declared in `core/build.gradle` (OkHttp, RxJava3, org.json,
  commons-lang3/io, jsoup, xpp3, sqlite-jdbc, slf4j) and shared via `api`,
  so `app` inherits them.
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
