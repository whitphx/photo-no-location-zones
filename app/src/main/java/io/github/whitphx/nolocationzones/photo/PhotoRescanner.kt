package io.github.whitphx.nolocationzones.photo

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.location.Location
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import io.github.whitphx.nolocationzones.data.PendingStripRepository
import io.github.whitphx.nolocationzones.data.ZoneRepository
import io.github.whitphx.nolocationzones.domain.PendingStrip
import io.github.whitphx.nolocationzones.domain.Zone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Scans MediaStore photos and videos, reads each item's own embedded GPS (EXIF for images,
 * QuickTime `moov/udta/©xyz` for videos), and queues for review every item whose coordinates
 * fall inside any defined zone.
 *
 * This is the recovery path: it lets the user re-find media they skipped earlier, and pull in
 * past media taken before the app was installed (or while the app process wasn't running and the
 * live ContentObserver missed it).
 */
class PhotoRescanner(
    private val context: Context,
    private val zoneRepo: ZoneRepository,
    private val pendingRepo: PendingStripRepository,
) {
    /** Counters returned to the UI. [noGps] photos were walked but had no readable GPS — usually
     *  because they have no EXIF location to begin with, but it's also the symptom of a missing
     *  ACCESS_MEDIA_LOCATION permission, in which case the platform redacts GPS from every read. */
    data class Result(
        val matched: Int,
        val scanned: Int,
        val noGps: Int,
        val zonesAtScan: Int,
        val daysBack: Int,
    )

    /** [daysBack] of [Int.MAX_VALUE] means "no time filter" (walk everything in MediaStore). */
    suspend fun rescanRecent(daysBack: Int = DEFAULT_DAYS_BACK): Result = withContext(Dispatchers.IO) {
        val zones = zoneRepo.getAll()
        if (zones.isEmpty()) {
            return@withContext Result(matched = 0, scanned = 0, noGps = 0, zonesAtScan = 0, daysBack = daysBack)
        }

        val resolver = context.contentResolver
        val now = System.currentTimeMillis()
        val timeFilter = daysBack != Int.MAX_VALUE
        val cutoffSec = if (timeFilter) {
            (now - daysBack * 24L * 60L * 60L * 1000L) / 1000L
        } else 0L

        var scanned = 0
        var matched = 0
        var noGps = 0
        for (collection in MediaCollection.entries) {
            val (cs, cm, cn) = scanCollection(resolver, collection, zones, cutoffSec, timeFilter, now)
            scanned += cs
            matched += cm
            noGps += cn
        }
        Log.i(
            TAG,
            "Rescan: matched=$matched, scanned=$scanned, noGps=$noGps, zones=${zones.size}, " +
                "daysBack=${if (timeFilter) daysBack.toString() else "all"}",
        )
        Result(
            matched = matched,
            scanned = scanned,
            noGps = noGps,
            zonesAtScan = zones.size,
            daysBack = daysBack,
        )
    }

    /** Returns `(scanned, matched, noGps)` for one MediaStore collection. */
    private suspend fun scanCollection(
        resolver: ContentResolver,
        collection: MediaCollection,
        zones: List<Zone>,
        cutoffSec: Long,
        timeFilter: Boolean,
        now: Long,
    ): Triple<Int, Int, Int> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
        )
        val mimePlaceholders = collection.mimeTypes.joinToString(" OR ") {
            "${MediaStore.MediaColumns.MIME_TYPE} = ?"
        }
        val (selection, args) = if (timeFilter) {
            "${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ($mimePlaceholders)" to
                (arrayOf(cutoffSec.toString()) + collection.mimeTypes.toTypedArray())
        } else {
            "($mimePlaceholders)" to collection.mimeTypes.toTypedArray()
        }
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"

        val cursor = resolver.query(collection.uri, projection, selection, args, sort)
            ?: return Triple(0, 0, 0)

        var scanned = 0
        var matched = 0
        var noGps = 0
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            while (c.moveToNext()) {
                scanned++
                val id = c.getLong(idCol)
                val mime = c.getString(mimeCol)
                val uri: Uri = ContentUris.withAppendedId(collection.uri, id)
                val coords = readCoords(resolver, collection, uri)
                if (coords == null) {
                    noGps++
                    continue
                }
                val zone = zoneContaining(coords[0], coords[1], zones) ?: continue
                pendingRepo.add(
                    PendingStrip(
                        imageId = id,
                        contentUri = uri,
                        displayName = c.getString(nameCol),
                        detectedAt = now,
                        zoneName = zone.name,
                        dateTakenMs = if (c.isNull(takenCol)) 0L else c.getLong(takenCol),
                        mimeType = mime,
                    )
                )
                matched++
            }
        }
        return Triple(scanned, matched, noGps)
    }

    private fun readCoords(
        resolver: ContentResolver,
        collection: MediaCollection,
        uri: Uri,
    ): DoubleArray? = when (collection) {
        MediaCollection.Images -> ExifGpsReader.readLatLong(resolver, uri)
        MediaCollection.Videos -> Mp4GpsReader.readLatLong(resolver, uri)
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
        const val DAYS_BACK_ALL = Int.MAX_VALUE
    }
}
