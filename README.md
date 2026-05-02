<picture>
  <source media="(prefers-color-scheme: dark)" srcset="branding/project-logo-dark.svg">
  <img src="branding/project-logo.svg" alt="Photo No-Location Zones">
</picture>

An Android app that strips GPS metadata from photos taken inside user-defined geographic zones (e.g. home, workplace), while preserving location data on photos taken anywhere else so they remain useful as travel logs.

## What it does

You define circular "no-location zones" on your phone. The OS uses native geofencing to detect when you cross into one of those zones. While you are inside any zone, the app watches for new photos taken on the device and queues each GPS-tagged one for review. Detection is automatic; the actual modification waits for you to authorize it from the app — every batch of edits is gated by an explicit system consent dialog. As soon as you leave every zone, monitoring stops and battery drain returns to baseline.

The stock camera app still runs, which means computational-photography features (night mode, portrait, HDR) are preserved. The app does not capture photos itself — it post-processes whatever the camera has already saved to disk.

## Architecture

```
                   ┌────────────────────────────────────────┐
                   │  Compose UI                             │
                   │  • HomeScreen (status + photo queue +   │
                   │    zones bottom sheet + PermissionsCard)│
                   │  • MapZoneScreen (create + edit)        │
                   │  • SettingsScreen                       │
                   │  • PhotoDetailDialog, FaqCard           │
                   │  • Main / MapZone / Review VMs          │
                   └──────────────┬─────────────────────────┘
                                  │ creates / deletes zones
                                  ▼
       ┌───────────────────┐   ┌──────────────────────────┐
       │  ZoneRepository   │◄──┤   AppDatabase (Room)     │
       │  PendingStripRepo │   │   zones, pending_strips  │
       │  ZoneStateStore   │   └──────────────────────────┘
       │  (DataStore)      │
       └────────┬──────────┘
                │ on add / delete
                ▼
   ┌──────────────────────────────┐    Play Services Geofencing
   │  GeofenceController          │────────────────►  OS
   └──────────────────────────────┘
                                       │ ENTER / EXIT pending intent
                                       ▼
                           ┌───────────────────────────┐
                           │ GeofenceBroadcastReceiver │ ──► updates ZoneStateStore
                           └────────────┬──────────────┘
                                        │ on ENTER: startForegroundService()
                                        ▼
                           ┌───────────────────────────┐
                           │ PhotoMonitorService       │
                           │ (foregroundServiceType =  │
                           │  "location")              │
                           │                           │
                           │ ContentObserver ─► scan ─►│ (no-GPS photos: drop)
                           │                          │ (GPS photos: enqueue)
                           └────────────┬──────────────┘
                                        │ post per-photo notification
                                        ▼
                           ┌───────────────────────────┐
                           │ Per-photo notification    │
                           │  tap body  ─► HomeScreen  │
                           │  "Strip GPS"  ─► MainActivity (deep-link)
                           │  "Show location" ─► MainActivity
                           │  "Skip" ─► PhotoActionReceiver (silent)
                           │                           │
                           │  on strip:                │
                           │  ─►  MediaStore           │
                           │       .createWriteRequest │
                           │  ─►  system consent       │
                           │  ─►  ExifGpsStripper      │
                           │       writes via URI FD   │
                           └───────────────────────────┘

         BootReceiver re-registers all geofences on ACTION_BOOT_COMPLETED
         and on ACTION_MY_PACKAGE_REPLACED (geofences don't survive reboot).
```

### Module layout

```
io.github.whitphx.nolocationzones
├── App.kt                  Application + notification channels + DI root
├── MainActivity.kt         Single Compose-hosting activity; honors OPEN_REVIEW /
│                           STRIP_PHOTO / SHOW_LOCATION deep-link intents
├── di/AppContainer.kt      Manual singleton DI (no Hilt — too much glue for 5 deps)
├── domain/                 Zone, PendingStrip
├── data/                   Room (zones, pending_strips) + DataStore (active-zone state, last-seen photo id)
├── geofence/               GeofenceController, BroadcastReceiver, BootReceiver
├── photo/                  PhotoMonitorService (detect+queue), ExifGpsStripper /
│                           ExifGpsReader, PhotoRescanner (manual catch-up scan),
│                           PendingStripReconciler (drop deleted photos),
│                           PhotoActionReceiver (notification action buttons)
├── permissions/            Permissions.kt — runtime permission state snapshot
├── theme/                  Material 3 color/typography
└── ui/                     Compose screens (Home / MapZone / Settings),
                            ViewModels, PhotoDetailDialog, FaqCard, navigation
```

## Key design choices

1. **Post-process, don't intercept.** A custom camera would defeat the user's purpose: they get GPS-clean photos but lose every Pixel-Camera-style computational feature. So the stock camera writes photos with GPS as usual, and we modify the file in place after the fact.

2. **Detection automatic, modification user-gated.** The geofence-triggered foreground service watches MediaStore for new photos and *only* queues them. Actually rewriting a photo's bytes requires the user to explicitly accept a system consent dialog (`MediaStore.createWriteRequest()`), which Android renders with a built-in list of the targeted files. The user sees exactly what is about to be modified before any byte is written. This is a deliberate safety property: the app can never silently modify a file. Detect-and-queue is automatic; modify is consented.

3. **Native geofencing, not polling.** Polling location to compare against zone centers would either drain the battery (frequent polls) or miss transitions (sparse polls). The platform's geofencing API runs in the OS process, leverages cell-tower / Wi-Fi signals when available, and wakes the app only on transition.

4. **Foreground service of type `location`, started only on ENTER.** No service runs while the user is outside every zone. The ENTER broadcast is one of the foreground-service-from-background exemptions, so this works on Android 12+. Service type `location` is the closest legitimate fit: the work is location-triggered and bounded to a defined geographic area.

5. **No broad storage permissions.** The app does *not* request `MANAGE_EXTERNAL_STORAGE`. Reads use `READ_MEDIA_IMAGES` + `ACCESS_MEDIA_LOCATION`; writes go through per-batch `MediaStore.createWriteRequest()` which grants writable URIs only for the specific files the user just authorized. Nothing else on the device is reachable.

6. **Modern Android stack.** Kotlin 2.3, Jetpack Compose with Material 3, Room with KSP (no kapt), DataStore, coroutines. `minSdk = 30` (Android 11) — the floor below which scoped storage rules diverge dramatically and the legacy storage paths would have to be conditionally maintained. `targetSdk = 36`.

## Permissions and why each is needed

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | Read current location when the user creates a zone, and base permission required for geofencing. |
| `ACCESS_BACKGROUND_LOCATION` | Required so geofence transitions can be delivered while the app is closed. Must be granted via a separate request, and on Android 11+ the system routes the user through Settings rather than showing a runtime dialog. |
| `ACCESS_MEDIA_LOCATION` | Otherwise the platform redacts GPS EXIF from MediaStore reads, and we can't tell whether a photo has anything to strip. |
| `READ_MEDIA_IMAGES` | Required on Android 13+ to query MediaStore for newly-captured photos. |
| `FOREGROUND_SERVICE` | Required to run `PhotoMonitorService` while inside a zone. |
| `FOREGROUND_SERVICE_LOCATION` | Required on Android 14+ for any FGS declared with `foregroundServiceType="location"`. |
| `POST_NOTIFICATIONS` | Required on Android 13+ to display the foreground-service notification and the actionable review notification. |
| `RECEIVE_BOOT_COMPLETED` | Lets `BootReceiver` re-register geofences after reboot. (Geofences live in Play Services and are wiped at boot.) |

Write access to a photo is **not** granted by any of these — it is requested per batch via `MediaStore.createWriteRequest()` when the user triggers a strip (per-row action, selection-mode bulk, or the per-photo notification's **Strip GPS** action). The system shows its own dialog listing the affected files; if the user declines, no bytes are touched.

## Building and installing on your own phone

Prerequisites:

- JDK 17 (`brew install openjdk@17` on macOS)
- Android SDK with `platforms/android-36` and `build-tools/36.0.0`
- A device running Android 11 or newer with Google Play services (geofencing and the map both require it)
- A **Google Maps Android SDK API key** for the map-based zone editor. Create a project in the [Google Cloud Console](https://console.cloud.google.com/), enable *Maps SDK for Android*, generate an API key, and add it to `local.properties`:

  ```properties
  MAPS_API_KEY=PASTE_YOUR_GOOGLE_MAPS_API_KEY_HERE
  ```

  `local.properties` is gitignored. Without a key the app still builds and runs, but the map view will be blank — every other feature works fine. For a personal-use build, restrict the key to your app's package name + debug-signing SHA-1 fingerprint.

```sh
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First-run setup, in this order:

1. Open the app. A **Permissions** card lists every permission status.
2. Tap **Grant** on *Foreground location & photo access* — answer the system dialog with *While using the app*.
3. Tap **Grant** on *Background location*. On Android 11+ this opens Settings; choose *Allow all the time*.
4. Tap **Grant** on *Notifications* (Android 13+).
5. Tap **+** in the zones bottom sheet to add your first zone. A map opens centered on your current location; tap anywhere on the map to place the pin, set a name, adjust the radius (100 m – 5 km), and tap **Save**. To edit or delete an existing zone later, tap its row in the bottom sheet to reopen the same screen.
6. Take a test photo with the stock camera. Within a few seconds a per-photo notification appears with **Strip GPS** and **Show location** action buttons.
7. Tap the notification body to open the app's photo queue, or tap **Strip GPS** directly on the notification. Either way the system shows its *"Allow this app to modify these photos?"* consent dialog before any byte is written. In the queue you can also long-press to select multiple photos and tap **Strip GPS selected** in the header.
8. Verify the result with any EXIF viewer — GPS tags should be absent.
9. Walk outside the zone (or shrink the radius and step away). The **Inside** badge flips to **Outside** and monitoring stops.

## Privacy gaps — what the scrubber does and does NOT cover

The scrubber clears the obvious metadata. There are several places location and identity info can hide that we deliberately don't (or can't) reach yet — anyone using this for serious privacy needs to know about them.

### What we *do* clear

- **All 32 EXIF GPS-IFD fields.** Latitude, longitude, altitude, GPS-timestamp, dest-coordinates, bearing, processing method, area information, datestamp, differential, h-positioning-error, satellites, status, measure-mode, DOP, speed, track, image direction, map datum.
- **EXIF `MakerNote`.** A vendor-defined binary blob written by Samsung / Apple / Google / etc. that frequently embeds a *duplicate of the GPS coordinates* in proprietary format, plus Wi-Fi SSID at capture time, Apple's per-photo `AssetIdentifier`, scene/face recognition data, and similar. **A scrubber that clears GPS-IFD without clearing `MakerNote` will still resolve to your address in some forensic tools.**
- **Identifying tags.** `Artist`, `CameraOwnerName`, `BodySerialNumber`, `LensSerialNumber`, `UserComment`, `ImageDescription`. Most camera apps don't fill these — but the ones that do (some Samsung modes, Lightroom imports, photo editors) embed your full name, the camera's unique serial number (which ties multiple photos to the same physical device), or free-form captions that occasionally contain location names.
- **Post-strip verification.** After save, the file is re-read and the same tag list is checked. Any survivor (e.g. due to a thumbnail-IFD leak or a parser quirk) is logged at WARN level so the gap is visible in `adb logcat -s ExifGpsStripper`.
- **MediaStore cache.** We call `ContentResolver.notifyChange()` after each successful strip so gallery apps that cache `LATITUDE`/`LONGITUDE` columns flush their cache and re-read.

### What we do NOT clear (yet)

- **Adobe XMP packet.** XMP is an XML-based metadata block embedded in JPEG (a different `APP1` segment from EXIF) and as a separate item in HEIF. AndroidX `ExifInterface` parses EXIF and *preserves XMP byte-for-byte*. XMP commonly carries `Iptc4xmpExt:LocationCreated` / `LocationShown` (location names with country/city/sublocation), `photoshop:City` / `:State` / `:Country`, `dc:creator` (your name), Apple's `apple:ContentIdentifier` for Live Photo grouping, Google's `GCamera:` namespace for Pixel scene metadata, and Samsung's `<MicroVideo>` payload. Stripping XMP requires either rewriting the JPEG container directly (find the XMP `APP1` segment by its `http://ns.adobe.com/xap/1.0/` namespace marker) or pulling in a metadata library. **High-priority follow-up.**
- **Motion Photo / Live Photo embedded video.** Samsung "Motion Photo", Google "Top Shot", and Apple Live Photos pack a still + an MP4 video into one file (or a paired pair). The MP4 has its own metadata, including GPS in `moov/udta/©xyz` atoms. We only touch the still's EXIF — the MP4's GPS is left intact. **High-priority follow-up.**
- **IPTC.** A third metadata block sometimes used by photo editors and pro cameras. Same root cause as XMP — `ExifInterface` doesn't parse it. Lower-impact in practice on phones.
- **Sensor PRNU (Photo Response Non-Uniformity).** Every camera sensor has a unique noise pattern that survives metadata stripping entirely. Forensic software can match a photo to a specific physical camera from pixel statistics alone, with no metadata required. **Not solvable by metadata stripping**; would need re-encoding or noise injection. Out of scope.
- **Windows `XPTitle` / `XPComment` / `XPAuthor` / `XPKeywords` / `XPSubject`.** AndroidX `ExifInterface` doesn't expose `setAttribute` constants for these tags, so we cannot clear them through this library. They're rarely written by mobile cameras; the practical exposure is photos round-tripped through Windows Explorer's "Tags" UI.

### What we deliberately *keep*

- **`DateTime` / `DateTimeOriginal` / `DateTimeDigitized`** and the corresponding `OffsetTime*` and `SubSecTime*` fields. Useful for chronological sorting, and the user almost always wants them. Note: `OffsetTime` (timezone) is a coarse location proxy ("you were UTC+9") — strip it manually if that matters to you.
- **`Make`, `Model`, `Software`.** Identifies the device and camera app. Fingerprints your device but is also useful information; left alone by default.

A user who wants the most thorough scrub should run a separate desktop tool (e.g. `exiftool -all=`) on top of this app's output. This app is a "good 80%" pass; it is not a substitute for a careful adversarial-threat-model review.

## Known limitations and edge cases

- **Cloud-backup race.** Google Photos and similar services upload as soon as a photo lands. If they finish before you trigger a strip, the GPS-tagged original lands in the cloud first. The new design makes this *worse* than an automatic strip would: there is now an unbounded delay between capture and strip (the user might never act on the queue). **Mitigation:** disable photo backup in the cloud app's settings for `DCIM/Camera`, or accept that the cloud version is leaky. This is the explicit price of the consent-based design — the app cannot rewrite without your permission, so a backup app that doesn't wait for your permission will win the race.
- **Two-stage camera writes.** Some camera apps write JPEG bytes first and EXIF a moment later. The detection ContentObserver may fire on the partial file; we re-query MediaStore and only act on rows it has finished indexing, so this is usually safe — but if a row is enqueued during the partial-write window the strip itself can fail. Failed strips remain in the queue for retry on the next attempt.
- **Geofence accuracy floor.** GPS noise produces spurious enter/exit events at small radii. The UI enforces a 100 m minimum (Google's published guidance). Even at 100 m, expect a handful of false transitions per day if you live very close to a zone boundary.
- **HEIF.** AndroidX `ExifInterface` 1.4 supports HEIF read+write. Some devices write `.heic` containers that wrap a sequence of HEVC frames; ExifInterface handles them, but if you see scrub failures in the logs for `image/heif`, that is the suspect.
- **Reboot.** Geofences are stored inside Play Services and are wiped at reboot. `BootReceiver` re-registers them on `BOOT_COMPLETED`. There is a brief window during boot before our receiver runs; a photo taken in that window from inside a zone will not be detected.
- **Burst shots.** ContentObserver fires once per file but in rapid succession. Events are debounced 400 ms before scanning MediaStore, so dozens of burst photos all get queued together — and a single consent dialog covers the whole batch.
- **Non-camera image creation.** The MediaStore observer fires for *any* new image — screenshots, downloads, drawing apps. Most of those have no GPS tags and are filtered out at queue time (we check before inserting). We prefer this to filtering on `bucket_display_name = "Camera"`, which would miss photos taken with non-default cameras.
- **Selected Photos Access (Android 14+).** Lint warns we do not handle the user choosing partial photo access. With `READ_MEDIA_IMAGES` granted in full this is a no-op; the warning would matter for a Play-Store-ready fork that wanted to support partial access.
- **Single user only.** Geofences are per-app, per-device-user (Google's limit). Multi-user devices each get their own 100-zone budget.
- **Samsung Galaxy battery optimization.** Samsung's One UI silently un-registers geofences after a few days unless the app is added to *Settings → Battery → Background usage limits → Never sleeping apps*.

## Future work (deliberately out of MVP scope)

- **Per-zone schedules.** "Strip GPS only on weekends at the office" — would require a small scheduler layered on top of the active-zone state.
- **Video files.** Modifying GPS metadata in `.mp4` requires walking the `udta`/`gps0` boxes; ExifInterface only handles still images.
- **Auto-strip option for a Play-Store version.** With `MediaStore.createWriteRequest()` you can pre-collect a session's worth of photos and submit one consent dialog at the moment of a UI-foreground event (e.g. when the user opens the camera app). The current MVP keeps the model deliberately simple: detect, queue, prompt-on-review.
- **Bulk skip with tombstone.** Skipped photos are forgotten but could re-appear if MediaStore rescans (e.g. via the manual *Rescan* in Settings). A persistent "skipped" set would prevent that.
