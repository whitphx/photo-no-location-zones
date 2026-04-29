package dev.whitphx.nolocationzones.photo

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/**
 * Strips location-related metadata from a JPEG/HEIF photo.
 *
 * ## What we clear
 *
 * **Location tags** ([LOCATION_TAGS]) — all 32 EXIF GPS-IFD fields, plus `MakerNote`. The GPS-IFD
 * is the obvious target. `MakerNote` is the under-appreciated one: it's a vendor-defined binary
 * blob that Samsung / Apple / Google / etc. use to store **a duplicate copy of the GPS
 * coordinates** in their proprietary format, alongside things like Wi-Fi SSID at capture time,
 * Apple's per-photo `AssetIdentifier`, and scene/face recognition data. A scrubber that clears
 * GPS-IFD without clearing `MakerNote` will still resolve to the user's address in some forensic
 * tools.
 *
 * **Identity tags** ([IDENTITY_TAGS]) — fields that identify *who* took the photo or *which
 * device*: `Artist`, `CameraOwnerName`, `BodySerialNumber`, `LensSerialNumber`, the Windows
 * `XP*` fields, `UserComment`, `ImageDescription`. Most camera apps don't fill these — but the
 * ones that do (photo editors, some Samsung configurations, Lightroom imports) embed the user's
 * full name, the camera's unique serial number (which ties multiple photos to the same physical
 * device), or free-form captions that occasionally contain location names.
 *
 * ## What we do NOT clear (known gaps — see README "Privacy gaps" for detail)
 *
 *  - **XMP packet.** Adobe XMP is a separate XML metadata block in JPEG (a different `APP1`
 *    segment from EXIF) and a separate item in HEIF. AndroidX `ExifInterface` parses EXIF and
 *    preserves XMP byte-for-byte. XMP commonly carries `Iptc4xmpExt:LocationCreated`,
 *    `photoshop:City`/`State`/`Country`, `dc:creator` (your name), Apple's
 *    `apple:ContentIdentifier` for Live Photo grouping, etc. Stripping XMP requires either
 *    reading/rewriting the JPEG container ourselves or pulling in a metadata library; neither is
 *    in scope today.
 *
 *  - **Motion Photo / Live Photo embedded video.** Samsung Motion Photo, Google Camera Top Shot,
 *    and Apple Live Photos pack a still + an MP4 video into one file. The MP4 has its own
 *    metadata, including GPS in `moov/udta/©xyz` atoms. We only touch the still's EXIF — the MP4
 *    GPS is left intact.
 *
 *  - **IPTC.** A third metadata block sometimes used by photo editors. Same gap as XMP.
 *
 *  - **PRNU (sensor pixel-pattern fingerprinting).** Every camera sensor has a unique noise
 *    signature. Forensic software can match a photo to a specific physical camera from pixel
 *    statistics alone, no metadata required. Not solvable by metadata stripping; would need
 *    re-encoding or noise injection.
 *
 *  - **Embedded thumbnail.** EXIF can carry a small thumbnail JPEG with its own metadata. The
 *    [verifyClean] check below re-reads the file after save and logs if any GPS field reappears
 *    — that catches both thumbnail-IFD leaks and any other parser quirk where a tag we cleared
 *    came back from the dead.
 *
 * ## Operational details
 *
 * Operates entirely through [ContentResolver] so we don't need broad filesystem access — write
 * permission for the URI must be granted by the user via
 * [android.provider.MediaStore.createWriteRequest] before [strip] is called.
 *
 * Idempotent: if no target tags are present, [strip] returns [Result.NoChange] without touching
 * the file.
 *
 * After a successful strip we call [ContentResolver.notifyChange] so MediaStore observers (e.g.
 * gallery apps that cache `LATITUDE`/`LONGITUDE` columns) re-read the file and see the cleaned
 * metadata.
 */
object ExifGpsStripper {

    sealed interface Result {
        data object NoChange : Result
        data class Stripped(val locationTagsCleared: Int, val identityTagsCleared: Int) : Result {
            val total: Int get() = locationTagsCleared + identityTagsCleared
        }
        data class Failed(val cause: Throwable) : Result
    }

    /** GPS-IFD fields plus MakerNote (which holds a vendor-format duplicate of the coordinates). */
    val LOCATION_TAGS: List<String> = listOf(
        ExifInterface.TAG_GPS_VERSION_ID,
        ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LATITUDE,
        ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE,
        ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE,
        ExifInterface.TAG_GPS_TIMESTAMP,
        ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS,
        ExifInterface.TAG_GPS_MEASURE_MODE,
        ExifInterface.TAG_GPS_DOP,
        ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_SPEED,
        ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_TRACK,
        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION,
        ExifInterface.TAG_GPS_MAP_DATUM,
        ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE,
        ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE,
        ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_BEARING,
        ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE,
        ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
        // Vendor-defined blob — often a duplicate GPS position, plus Wi-Fi SSID, AssetIdentifier,
        // face/scene recognition data, etc. Clearing the entire blob is the only way to reliably
        // drop the duplicate without a per-vendor parser.
        ExifInterface.TAG_MAKER_NOTE,
    )

    /**
     * Tags that identify the photographer or the specific physical device. Cleared in addition
     * to [LOCATION_TAGS] because (a) `BodySerialNumber` ties multiple photos to one camera, and
     * (b) free-form text fields like `UserComment` and `ImageDescription` sometimes contain
     * location names ("Home", "Mom's house") that survive a pure GPS strip.
     *
     * Note: the Windows-specific `XPTitle`/`XPComment`/`XPAuthor`/`XPKeywords`/`XPSubject` tags
     * are *not* listed here. AndroidX `ExifInterface` doesn't expose `setAttribute` constants for
     * them, so we cannot clear them through this library. They're rarely written by mobile
     * cameras; the practical exposure is photos round-tripped through Windows Explorer's "Tags"
     * UI. Documented as a known gap in the README.
     */
    val IDENTITY_TAGS: List<String> = listOf(
        ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_CAMERA_OWNER_NAME,
        ExifInterface.TAG_BODY_SERIAL_NUMBER,
        ExifInterface.TAG_LENS_SERIAL_NUMBER,
        ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_DESCRIPTION,
    )

    private val ALL_TARGET_TAGS: List<String> = LOCATION_TAGS + IDENTITY_TAGS

    /**
     * Reads GPS tag presence without modifying the file. Requires READ access (granted by
     * READ_MEDIA_IMAGES + ACCESS_MEDIA_LOCATION); does NOT require write access.
     */
    fun hasGpsTags(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                LOCATION_TAGS.any { exif.getAttribute(it) != null }
            } ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "hasGpsTags failed for $uri", t)
            false
        }
    }

    /**
     * Strips location and identity tags. Caller must have already obtained write permission for
     * [uri] via [android.provider.MediaStore.createWriteRequest].
     */
    fun strip(resolver: ContentResolver, uri: Uri): Result {
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw")
        } catch (t: Throwable) {
            return Result.Failed(t)
        } ?: return Result.Failed(IOException("openFileDescriptor returned null for $uri"))

        val result = pfd.use { descriptor ->
            try {
                val exif = ExifInterface(descriptor.fileDescriptor)
                var locationCleared = 0
                var identityCleared = 0
                for (tag in LOCATION_TAGS) {
                    if (exif.getAttribute(tag) != null) {
                        exif.setAttribute(tag, null)
                        locationCleared++
                    }
                }
                for (tag in IDENTITY_TAGS) {
                    if (exif.getAttribute(tag) != null) {
                        exif.setAttribute(tag, null)
                        identityCleared++
                    }
                }
                if (locationCleared == 0 && identityCleared == 0) {
                    Result.NoChange
                } else {
                    exif.saveAttributes()
                    Log.i(
                        TAG,
                        "Cleared $locationCleared location + $identityCleared identity tag(s) from $uri",
                    )
                    Result.Stripped(locationCleared, identityCleared)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Strip failed for $uri", t)
                Result.Failed(t)
            }
        }

        // Defensive: re-read after save and warn loudly if any target tag survived. This catches
        // thumbnail-IFD leaks, parser quirks, and anything else where a tag we cleared came back.
        if (result is Result.Stripped) verifyClean(resolver, uri)
        // Tell MediaStore to re-index this file so cached LATITUDE/LONGITUDE columns flush.
        if (result is Result.Stripped) runCatching { resolver.notifyChange(uri, null) }

        return result
    }

    private fun verifyClean(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                val survivors = ALL_TARGET_TAGS.filter { exif.getAttribute(it) != null }
                if (survivors.isNotEmpty()) {
                    Log.w(TAG, "Post-strip verification: tags survived for $uri: $survivors")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Post-strip verification read failed for $uri", t)
        }
    }

    private const val TAG = "ExifGpsStripper"
}
