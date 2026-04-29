# Photo No-Location Zones

An Android app that strips GPS metadata from photos taken inside user-defined geographic zones (e.g. home, workplace), while preserving location data on photos taken anywhere else so they remain useful as travel logs.

## What it does

You define circular "no-location zones" on your phone. The OS uses native geofencing to detect when you cross into one of those zones. While you are inside any zone, the app watches for new photos appearing on the device and removes every GPS-related EXIF tag from each one — latitude/longitude, altitude, timestamp, bearing, processing method, and so on. As soon as you leave every zone, monitoring stops and battery drain returns to baseline.

The stock camera app still runs, which means computational-photography features (night mode, portrait, HDR) are preserved. The app does not capture photos itself — it post-processes whatever the camera has already saved to disk.

## Architecture

```
                   ┌─────────────────────────────────────┐
                   │  Compose UI                          │
                   │  • ZoneListScreen, AddZoneScreen     │
                   │  • PermissionsCard                   │
                   │  • MainViewModel                     │
                   └──────────────┬──────────────────────┘
                                  │ creates / deletes zones
                                  ▼
       ┌───────────────────┐   ┌──────────────────────────┐
       │  ZoneRepository   │◄──┤   AppDatabase (Room)     │
       │  + ZoneStateStore │   │   - zones table          │
       │  (DataStore)      │   └──────────────────────────┘
       └────────┬──────────┘
                │ on add / delete
                ▼
   ┌──────────────────────────────┐    Play Services Geofencing
   │  GeofenceController          │────────────────►  OS
   │  (LocationServices client)   │
   └──────────────────────────────┘
                                       │ ENTER / EXIT pending intent
                                       ▼
                           ┌───────────────────────────┐
                           │ GeofenceBroadcastReceiver │ ──► updates ZoneStateStore
                           └────────────┬──────────────┘
                                        │ if entering: startForegroundService()
                                        ▼
                           ┌───────────────────────────┐
                           │ PhotoMonitorService       │
                           │ (foregroundServiceType =  │
                           │  "location")              │
                           │                           │
                           │ ContentObserver ─► scan ─►│ ──► ExifGpsStripper
                           │                queue      │       (writes file in place)
                           └───────────────────────────┘

         BootReceiver re-registers all geofences on ACTION_BOOT_COMPLETED
         and on ACTION_MY_PACKAGE_REPLACED (geofences don't survive reboot).
```

### Module layout

```
dev.whitphx.nolocationzones
├── App.kt                  Application + notification channels + DI root
├── MainActivity.kt         Single Compose-hosting activity
├── di/AppContainer.kt      Manual singleton DI (no Hilt — too much glue for 5 deps)
├── domain/Zone.kt          Domain model
├── data/                   Room (zones) + DataStore (active-zone state, last-seen photo id)
├── geofence/               GeofenceController, BroadcastReceiver, BootReceiver
├── photo/                  PhotoMonitorService, ExifGpsStripper
├── permissions/            Snapshot reader for all runtime permissions
├── theme/                  Material 3 color/typography
└── ui/                     Compose screens, ViewModel, navigation
```

## Key design choices

1. **Post-process, don't intercept.** A custom camera would defeat the user's purpose: they get GPS-clean photos but lose every Pixel-Camera-style computational feature. So the stock camera writes photos with GPS as usual, and we modify the file in place after the fact. This means we deliberately accept a small window during which the unscrubbed file exists on disk — the README's "Known limitations" section lists the cloud-backup race that follows from this trade-off.

2. **Native geofencing, not polling.** Polling location to compare against zone centers would either drain the battery (frequent polls) or miss transitions (sparse polls). The platform's geofencing API runs in the OS process, leverages cell-tower / Wi-Fi signals when available, and wakes the app only on transition. Free, accurate, battery-friendly — and reliable enough that the foreground service runs **only** while the user is inside a zone.

3. **Foreground service of type `location`, started only on ENTER.** We do not run a service while the user is outside every zone. The ENTER broadcast is one of the foreground-service-from-background exemptions, so this works on Android 12+. Service type `location` is the closest legitimate fit: the work is location-triggered and bounded to a defined geographic area.

4. **Personal-build storage shortcut: `MANAGE_EXTERNAL_STORAGE`.** Modifying photos written by another app on Android 11+ otherwise requires either the per-photo `MediaStore.createWriteRequest()` consent dialog (every photo prompts the user) or migrating photos into the app sandbox (which would lose them from the user's gallery). Both are unacceptable for "automatic" scrubbing. The All Files Access permission lets us read and write any photo file directly, granted once via Settings. This is the explicit pragmatic shortcut the spec calls out, and the manifest comment flags it for replacement in any Play-Store-ready fork.

5. **Modern Android stack.** Kotlin 2.3, Jetpack Compose with Material 3, Room with KSP (no kapt), DataStore, coroutines. `minSdk = 30` (Android 11) — the floor below which scoped storage rules diverge dramatically and the legacy storage paths would have to be conditionally maintained. `targetSdk = 36`.

## Permissions and why each is needed

| Permission | Why |
|---|---|
| `ACCESS_FINE_LOCATION` | Read current location when the user creates a zone, and base permission required for geofencing. |
| `ACCESS_BACKGROUND_LOCATION` | Required so geofence transitions can be delivered while the app is closed. Must be granted via a separate request, and on Android 11+ the system routes the user through Settings rather than showing a runtime dialog. |
| `ACCESS_MEDIA_LOCATION` | Otherwise the platform redacts GPS EXIF from MediaStore reads, and we can't see what we are about to scrub. |
| `READ_MEDIA_IMAGES` | Required on Android 13+ to query MediaStore for newly-captured photos. |
| `MANAGE_EXTERNAL_STORAGE` | The personal-build shortcut described above — lets us write the scrubbed bytes back to the original file path without a per-photo consent dance. Granted via Settings ► Special app access ► All files access. |
| `FOREGROUND_SERVICE` | Required to run `PhotoMonitorService` while inside a zone. |
| `FOREGROUND_SERVICE_LOCATION` | Required on Android 14+ for any FGS declared with `foregroundServiceType="location"`. |
| `POST_NOTIFICATIONS` | Required on Android 13+ to display the foreground-service notification. |
| `RECEIVE_BOOT_COMPLETED` | Lets `BootReceiver` re-register geofences after reboot. (Geofences live in Play Services and are wiped at boot.) |

## Building and installing on your own phone

Prerequisites:

- JDK 17 (`brew install openjdk@17` on macOS)
- Android SDK with `platforms/android-36` and `build-tools/36.0.0`
- A device running Android 11 or newer with Google Play services (geofencing requires it)

```sh
# 1. Build a debug APK
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# 2. Install on your phone (USB debugging enabled)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

First-run setup, in this order:

1. Open the app. A **Permissions** card lists every permission status.
2. Tap **Grant** on *Foreground location & photo access* — answer the system dialog with *While using the app*.
3. Tap **Grant** on *Background location*. On Android 11+ this opens Settings; choose *Allow all the time*.
4. Tap **Grant** on *Notifications* (Android 13+).
5. Tap **Grant** on *All files access*. This opens Settings ► Special app access ► All files access. Toggle the app on. Return to the app.
6. Tap **+** to add your first zone — typically your home. The app reads your current location and lets you name it and set a radius (100 m – 5 km).
7. The system will deliver an `ENTER` event because of `INITIAL_TRIGGER_ENTER`; the **Inside** badge appears next to the zone, and a low-priority notification confirms monitoring is active.
8. Take a test photo with the stock camera. Inspect the EXIF (e.g. `exiftool /sdcard/DCIM/Camera/<file>.jpg`) — GPS tags should be absent.
9. Walk outside the zone (or temporarily set a small radius and step away). The badge flips to **Outside** and the notification disappears.

## Known limitations and edge cases

- **Cloud-backup race.** Google Photos and similar services upload as soon as a photo lands. If they finish before our `~150 ms` size-stability check + EXIF rewrite, the GPS-tagged original lands in the cloud first and the scrubbed copy syncs as a "modified" version. **Mitigation:** disable photo backup in settings of the cloud app for `DCIM/Camera` while inside zones, or accept that the cloud version is leaky. Genuinely solving this in code would require intercepting the camera's `IMAGE_CAPTURE` intent or running our own camera, which we explicitly chose not to do.
- **Two-stage camera writes.** Some camera apps write JPEG bytes first and EXIF a moment later. The service watches for file-size stability between two reads `~150 ms` apart and retries up to four times with backoff before giving up.
- **Geofence accuracy floor.** GPS noise produces spurious enter/exit events at small radii. The UI enforces a 100 m minimum (Google's published guidance). Even at 100 m, expect a handful of false transitions per day if you live very close to a zone boundary.
- **HEIF.** AndroidX `ExifInterface` 1.3+ supports HEIF read+write. Some devices write `.heic` containers that wrap a sequence of HEVC frames; ExifInterface handles them, but if you see scrub failures in the logs for `image/heif`, that is the suspect.
- **Reboot.** Geofences are stored inside Play Services and are wiped at reboot. `BootReceiver` re-registers them on `BOOT_COMPLETED`. There is a brief window during boot before our receiver runs; a photo taken in that window from inside a zone will not be scrubbed.
- **Burst shots.** ContentObserver fires once per file but in rapid succession. Events are debounced 400 ms and processed serially through a `Channel`, so dozens of burst photos all get scrubbed without stomping on each other — but the user-visible delay between capture and scrub grows with the burst length.
- **Non-camera image creation.** The MediaStore observer fires for *any* new image — screenshots, downloads, drawing apps. Most of those have no GPS tags so the scrubber is a no-op (and idempotent), but it is technically work the app performs that the user might not have asked for. We prefer this to filtering on `bucket_display_name = "Camera"`, which would miss photos taken with non-default cameras.
- **Selected Photos Access (Android 14+).** Lint warns we do not handle the user choosing partial photo access. With `MANAGE_EXTERNAL_STORAGE` granted this does not apply; the warning would matter for a Play-Store-ready fork.
- **Single user only.** Geofences are per-app, per-device-user (Google's limit). Multi-user devices each get their own 100-zone budget.

## Future work (deliberately out of MVP scope)

- **Map-based zone editing.** Today zones can only be created at the user's current location. A map picker would allow creating zones for places the user is not currently at (e.g. "scrub everything taken at my parents' house") and dragging existing zones to refine them. Requires Google Maps SDK + an API key.
- **Per-zone schedules.** "Strip GPS only on weekends at the office" — would require a small scheduler (WorkManager periodic checks against a per-zone cron) layered on top of the active-zone state.
- **Video files.** Modifying GPS metadata in `.mp4` requires walking the `udta`/`gps0` boxes; ExifInterface only handles still images. A separate path with `mp4parser` or `ffmpeg-kit` would be needed.
- **Per-photo strip results.** Today's logs and a single low-priority foreground notification are enough; a result feed showing exactly which photos were scrubbed (and which failed) would help users audit behavior.
- **Play Store readiness.** Replace `MANAGE_EXTERNAL_STORAGE` with `MediaStore.createWriteRequest()` and accept the per-photo consent dialog; replace per-app permission grant flows with the new system permission rationale APIs; add the Selected Photos Access path; and choose a more conservative foreground-service type (likely `dataSync` plus a `WorkManager` constraint, accepting the worse latency).
