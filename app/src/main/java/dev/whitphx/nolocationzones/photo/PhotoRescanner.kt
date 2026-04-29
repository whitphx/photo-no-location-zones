package dev.whitphx.nolocationzones.photo

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.location.Location
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import dev.whitphx.nolocationzones.data.PendingStripRepository
import dev.whitphx.nolocationzones.data.ZoneRepository
import dev.whitphx.nolocationzones.domain.PendingStrip
import dev.whitphx.nolocationzones.domain.Zone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans recently-added photos in MediaStore, reads each photo's own EXIF GPS, and queues for
 * review every photo whose coordinates fall inside any defined zone.
 *
 * This is the recovery path: it lets the user re-find photos they skipped earlier, and pull in
 * past photos that were taken before the app was installed (or while the app process wasn't
 * running and the live ContentObserver missed them).
 */
class PhotoRescanner(
    private val context: Context,
    private val zoneRepo: ZoneRepository,
    private val pendingRepo: PendingStripRepository,
) {
    data class Result(val matched: Int, val scanned: Int, val zonesAtScan: Int)

    suspend fun rescanRecent(daysBack: Int = DEFAULT_DAYS_BACK): Result = withContext(Dispatchers.IO) {
        val zones = zoneRepo.getAll()
        if (zones.isEmpty()) return@withContext Result(matched = 0, scanned = 0, zonesAtScan = 0)

        val resolver = context.contentResolver
        val cutoffMs = System.currentTimeMillis() - daysBack * 24L * 60L * 60L * 1000L
        val cutoffSec = cutoffMs / 1000L

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ? AND " +
            "(${MediaStore.Images.Media.MIME_TYPE} = 'image/jpeg' OR " +
            "${MediaStore.Images.Media.MIME_TYPE} = 'image/heif' OR " +
            "${MediaStore.Images.Media.MIME_TYPE} = 'image/heic')"
        val args = arrayOf(cutoffSec.toString())
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        val cursor = resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            sort,
        ) ?: return@withContext Result(matched = 0, scanned = 0, zonesAtScan = zones.size)

        var scanned = 0
        var matched = 0
        val now = System.currentTimeMillis()
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                scanned++
                val id = c.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                val coords = readLatLong(resolver, uri) ?: continue
                val zone = zoneContaining(coords[0], coords[1], zones) ?: continue

                pendingRepo.add(
                    PendingStrip(
                        imageId = id,
                        contentUri = uri,
                        displayName = c.getString(nameCol),
                        detectedAt = now,
                        zoneName = zone.name,
                    )
                )
                matched++
            }
        }
        Log.i(TAG, "Rescan: matched=$matched, scanned=$scanned, zones=${zones.size}, daysBack=$daysBack")
        Result(matched = matched, scanned = scanned, zonesAtScan = zones.size)
    }

    private fun readLatLong(resolver: ContentResolver, uri: android.net.Uri): DoubleArray? {
        return try {
            resolver.openInputStream(uri)?.use { input ->
                ExifInterface(input).getLatLong()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "readLatLong failed for $uri", t)
            null
        }
    }

    private fun zoneContaining(latitude: Double, longitude: Double, zones: List<Zone>): Zone? {
        val results = FloatArray(1)
        return zones.firstOrNull { z ->
            Location.distanceBetween(latitude, longitude, z.latitude, z.longitude, results)
            results[0] <= z.radiusMeters
        }
    }

    companion object {
        private const val TAG = "PhotoRescanner"
        const val DEFAULT_DAYS_BACK = 30
    }
}
