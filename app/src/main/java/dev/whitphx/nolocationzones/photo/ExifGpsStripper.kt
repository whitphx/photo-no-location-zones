package dev.whitphx.nolocationzones.photo

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * Strips every GPS-related EXIF tag from a JPEG/HEIF file.
 *
 * Idempotent: if no GPS tags are present, [strip] returns [Result.NoChange] without rewriting the
 * file.
 *
 * Why all GPS tags and not just lat/lon: any one of altitude, timestamp, bearing, processing
 * method, or area information can leak the user's location.
 */
object ExifGpsStripper {

    sealed interface Result {
        data object NoChange : Result
        data class Stripped(val tagsCleared: Int) : Result
        data class Failed(val cause: Throwable) : Result
    }

    /** All GPS-related EXIF tag names known to ExifInterface. */
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

    fun strip(file: File): Result {
        if (!file.exists() || !file.canRead()) {
            return Result.Failed(IOException("File not readable: ${file.absolutePath}"))
        }
        return try {
            val exif = ExifInterface(file.absolutePath)
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
                Log.i(TAG, "Cleared $cleared GPS tag(s) from ${file.name}")
                Result.Stripped(cleared)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to strip ${file.name}", t)
            Result.Failed(t)
        }
    }

    private const val TAG = "ExifGpsStripper"
}
