package dev.whitphx.nolocationzones.photo

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.IOException

/**
 * Strips every GPS-related EXIF tag from a JPEG/HEIF in MediaStore.
 *
 * Operates entirely through [ContentResolver] so we don't need broad filesystem access — write
 * permission for the URI must be granted by the user via [android.provider.MediaStore.createWriteRequest]
 * before [strip] is called.
 *
 * Idempotent: if no GPS tags are present, [strip] returns [Result.NoChange] without touching the
 * file. Removing all GPS tags (not just lat/lon) matters because altitude, timestamp, bearing,
 * processing method, and area information can each independently leak the user's location.
 */
object ExifGpsStripper {

    sealed interface Result {
        data object NoChange : Result
        data class Stripped(val tagsCleared: Int) : Result
        data class Failed(val cause: Throwable) : Result
    }

    private val GPS_TAGS: List<String> = listOf(
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
    )

    /**
     * Reads GPS tag presence without modifying the file. Requires READ access (granted by
     * READ_MEDIA_IMAGES + ACCESS_MEDIA_LOCATION); does NOT require write access.
     */
    fun hasGpsTags(resolver: ContentResolver, uri: Uri): Boolean {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                GPS_TAGS.any { exif.getAttribute(it) != null }
            } ?: false
        } catch (t: Throwable) {
            Log.w(TAG, "hasGpsTags failed for $uri", t)
            false
        }
    }

    /**
     * Strips GPS tags. Caller must have already obtained write permission for [uri] via
     * [android.provider.MediaStore.createWriteRequest].
     */
    fun strip(resolver: ContentResolver, uri: Uri): Result {
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw")
        } catch (t: Throwable) {
            return Result.Failed(t)
        } ?: return Result.Failed(IOException("openFileDescriptor returned null for $uri"))

        return pfd.use { descriptor ->
            try {
                val exif = ExifInterface(descriptor.fileDescriptor)
                var cleared = 0
                for (tag in GPS_TAGS) {
                    if (exif.getAttribute(tag) != null) {
                        exif.setAttribute(tag, null)
                        cleared++
                    }
                }
                if (cleared == 0) {
                    Result.NoChange
                } else {
                    exif.saveAttributes()
                    Log.i(TAG, "Cleared $cleared GPS tag(s) from $uri")
                    Result.Stripped(cleared)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Strip failed for $uri", t)
                Result.Failed(t)
            }
        }
    }

    private const val TAG = "ExifGpsStripper"
}
