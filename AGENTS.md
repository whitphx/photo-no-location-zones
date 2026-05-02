# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build, deploy, and test

Toolchain: JDK 17, Kotlin 2.3.20, AGP 9, `compileSdk 36`, `minSdk 30`, `targetSdk 36`. The Gradle wrapper enforces JDK 17 via `kotlin { jvmToolchain(17) }`.

```sh
./gradlew :app:assembleDebug                  # build debug APK
./gradlew :app:lintDebug                      # Android lint
./gradlew test                                # JVM unit tests (currently none)
./gradlew :app:connectedAndroidTest           # instrumented tests on a connected device
android run --apks=app/build/outputs/apk/debug/app-debug.apk   # deploy to connected device
```

A **`Stop` hook** in `.claude/settings.json` runs `./gradlew :app:assembleDebug && android run --apks=...` automatically after every agent turn. Don't manually build/deploy unless you're debugging that flow itself or the hook is disabled. Hook timeout is 600s; the auto-build may dominate turn time.

The map-based zone editor uses **MapLibre Native Android** with **OpenFreeMap** vector tiles. No API key, no signup — the style URL is hardcoded in `MapZoneScreen.kt` and tiles load over plain HTTPS at runtime. If the device is offline the map renders blank but the rest of the editor (radius, name, save) still works.

## Architecture invariants

The high-level diagram lives in `README.md` ("Architecture" section). The notes below are the non-obvious rules a change must respect.

### Detection vs. modification — never collapse the two

The geofence-triggered `PhotoMonitorService` only **observes and queues** newly captured photos and videos. It must never write to a file. Writes are *exclusively* gated by `MediaStore.createWriteRequest()` and the system consent dialog — `ReviewViewModel` raises a `RequestWriteAccess` event, `MainActivity` launches it via `StartIntentSenderForResult`, and only on success does the strip pipeline run on the granted URIs. Bypassing this is a privacy regression and breaks the safety story in the README.

`ReviewViewModel.onWriteGranted` dispatches per-row by `PendingStrip.mimeType`: images go to `ExifGpsStripper.strip()`, videos go to `Mp4GpsStripper.strip()`. Both are pure mutators — they never call `createWriteRequest` themselves.

### Foreground service start path is constrained

`PhotoMonitorService` (`foregroundServiceType="location"`) may only be started from the geofence ENTER `BroadcastReceiver`. That broadcast is one of the documented exemptions to Android 12+'s foreground-service-from-background restrictions. Starting it from arbitrary code paths (e.g., a UI button, an unrelated receiver) will throw `ForegroundServiceStartNotAllowedException` on real devices. If you need to "kick" monitoring for testing, fire a synthetic geofence ENTER, don't call `startForegroundService` directly.

### Geofence registration is non-durable

Geofences live in Play Services state and are wiped on reboot, on app reinstall, and silently after a few days of "background usage limits" on some Samsung devices. `BootReceiver` re-registers on `ACTION_BOOT_COMPLETED` and `ACTION_MY_PACKAGE_REPLACED`; `MainViewModel.resyncGeofences()` (wired to a button in `SettingsScreen`) is the manual escape hatch. Any change to `ZoneRepository` add/delete must keep `GeofenceController` in sync — the controller is the one place that owns the Play Services registration.

### `ZoneStateStore` is the source of truth for "inside any zone"

`GeofenceBroadcastReceiver` writes ENTER/EXIT into `ZoneStateStore` (DataStore). UI status indicators, the foreground service start/stop decision, and the badge in `HomeScreen` all read from there. Don't add a parallel piece of state.

### Stripping is two parallel pipelines, neither is a full metadata wipe

`ExifGpsStripper` (images) clears the 32 GPS-IFD fields plus a curated identifying-tags list. `Mp4GpsStripper` (videos) re-tags `moov/udta/©xyz` and `moov/udta/loci` atoms to `free` in place — the file's byte layout (and `mdat` chunk offsets in `stco`/`co64`) stay byte-identical, so we never re-mux the container. Both run a post-strip verification pass that re-reads the file and warns if any target survived (`adb logcat -s ExifGpsStripper Mp4GpsStripper`).

What neither pipeline touches:
- **XMP (in photos)** — `androidx.exifinterface` preserves the XMP `APP1` segment byte-for-byte. XMP often duplicates location info (`Iptc4xmpExt:LocationCreated`, `photoshop:City`, etc.).
- **Apple `moov/meta/keys` + `meta/ilst` (in videos)** — iPhone-recorded videos use a keys-table indirection to identify the location atom rather than the QuickTime `©xyz` shortcut. Decoding requires walking the keys table; not yet implemented.
- **Motion Photo / Live Photo embedded video (within a photo container)** — only the still's EXIF is rewritten; the embedded MP4's GPS atoms are left alone (we know how to clear them in standalone videos but haven't wired up the container-aware byte-range path).
- **IPTC**, **Windows XP\* tags**, **sensor PRNU**.

If you change either stripper, also update `README.md` "Privacy gaps" — it is the user-facing contract for what the scrubber does and doesn't cover.

### MediaStore scanning is per-collection

The pending-strip pipeline tracks images and videos in separate MediaStore collections (`MediaStore.Images.Media.EXTERNAL_CONTENT_URI` and `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`). `MediaCollection` (in `photo/`) holds the per-collection URIs and MIME-type allowlists; `PhotoMonitorService` registers a ContentObserver on each, `PhotoRescanner` iterates both, and `PendingStripReconciler` checks presence via the unified `MediaStore.Files` URI (which sees both collections). Adding a new media kind means adding a `MediaCollection` entry plus a per-kind GPS reader/stripper — don't try to overload the image path.

### Manual DI through `App.container`

There is no Hilt. `AppContainer` (`di/AppContainer.kt`) is constructed once in `App.onCreate()` and exposes singletons (`ZoneRepository`, `PendingStripRepository`, `PhotoRescanner`, `PendingStripReconciler`, `ZoneStateStore`, `GeofenceController`). ViewModels obtain dependencies via `application as App` then `app.container`. Don't introduce a DI framework for a single new class.

### Notification action plumbing

The per-photo review notification has three actions (`PhotoMonitorService.kt:228-230`):
- **Strip GPS** → `MainActivity` with `ACTION_STRIP_PHOTO` (deep-link, surfaces the system consent dialog)
- **Show location** → `MainActivity` with `ACTION_SHOW_LOCATION` (opens `PhotoDetailDialog`)
- **Skip** → `PhotoActionReceiver` (silent broadcast, no UI)

`MainActivity.applyIntent` translates these into a `NavSignal` (`ui/AppNavHost.kt`) backed by `mutableStateOf` so writes from `onNewIntent` re-trigger the Compose `LaunchedEffect`. Plain `var` will not work.

### `minSdk = 30` is intentional

Below Android 11, scoped storage rules diverge dramatically and the app would need a parallel legacy storage path. Don't lower the floor without revisiting the storage code.

## Conventions specific to this codebase

- **Single-activity Compose nav** via a hand-rolled `AppNavHost` (`Screen` sealed interface, `rememberSaveable` with a custom `Saver`). Don't pull in `androidx.navigation` for three destinations.
- **No backwards-compatibility shims** for the legacy density-bucket launcher icons; only `mipmap-anydpi-v26/` adaptive icons exist (minSdk 30 → adaptive is always used).
- **Vector drawables can't render `strokeDashArray`** — the SVG branding sources in `branding/` keep dashes for the README, but `ic_launcher_foreground.xml` renders the geofence ring as a solid stroke.
- The README's "Architecture" diagram and "Privacy gaps" tables are user-facing contracts. Keep them in sync with code changes that affect them.
