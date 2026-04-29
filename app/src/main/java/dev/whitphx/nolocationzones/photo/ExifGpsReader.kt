package dev.whitphx.nolocationzones.photo

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface

object ExifGpsReader {
    /** Returns `[lat, lon]` or null if the photo has no readable GPS in its EXIF. */
    fun readLatLong(resolver: ContentResolver, uri: Uri): DoubleArray? = try {
        resolver.openInputStream(uri)?.use { input -> ExifInterface(input).getLatLong() }
    } catch (t: Throwable) {
        Log.w("ExifGpsReader", "readLatLong failed for $uri", t)
        null
    }
}
