package io.github.whitphx.nolocationzones.photo

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.FileDescriptor
import java.nio.charset.StandardCharsets

/**
 * Reads the location embedded in an MP4 / MOV by parsing the QuickTime `moov/udta/©xyz` atom.
 *
 * Stock Android camera apps (Pixel, Samsung One UI, OnePlus, etc.) write GPS into this atom as an
 * ISO 6709 string, e.g. `+35.6895+139.6917+25.000/`. We only target this single path: it covers
 * the common Android case at the cost of missing iPhone videos that use Apple's
 * `moov/meta/keys` + `moov/meta/ilst` indirection (a known follow-up — see README "Privacy gaps").
 */
object Mp4GpsReader {

    /** Returns `[lat, lon]` or null if no readable GPS atom is present. */
    fun readLatLong(resolver: ContentResolver, uri: Uri): DoubleArray? = try {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            readGps(pfd.fileDescriptor)
        }
    } catch (t: Throwable) {
        Log.w(TAG, "readLatLong failed for $uri", t)
        null
    }

    private fun readGps(fd: FileDescriptor): DoubleArray? {
        val end = Mp4Atoms.fileSize(fd)
        var found: DoubleArray? = null
        Mp4Atoms.walkBoxes(fd, 0L, end) { type, payloadStart, payloadEnd, _ ->
            if (type != Mp4Atoms.MOOV || found != null) return@walkBoxes
            Mp4Atoms.walkBoxes(fd, payloadStart, payloadEnd) { t2, p2s, p2e, _ ->
                if (t2 != Mp4Atoms.UDTA || found != null) return@walkBoxes
                Mp4Atoms.walkBoxes(fd, p2s, p2e) { t3, p3s, p3e, _ ->
                    if (t3 != Mp4Atoms.XYZ || found != null) return@walkBoxes
                    val len = (p3e - p3s).toInt()
                    if (len < 4) return@walkBoxes
                    val buf = ByteArray(len)
                    val r = Mp4Atoms.readFully(fd, p3s, buf, 0, len)
                    if (r < 4) return@walkBoxes
                    // QuickTime ©xyz payload: 2-byte text length (big-endian), 2-byte language code,
                    // then UTF-8 ISO 6709 text. The trailing '/' is part of the standard format.
                    val textLen = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
                    val effective = minOf(textLen, r - 4).coerceAtLeast(0)
                    if (effective <= 0) return@walkBoxes
                    val text = String(buf, 4, effective, StandardCharsets.UTF_8)
                    found = parseIso6709(text)
                }
            }
        }
        return found
    }

    /**
     * ISO 6709 simple form: `±DD.DDDD±DDD.DDDD[±AAA.AAA]/`. We pluck the first two signed numbers
     * and ignore altitude/CRS suffixes — we only need lat/lon for the in-app map.
     */
    private fun parseIso6709(s: String): DoubleArray? {
        val matcher = ISO_6709.find(s) ?: return null
        val lat = matcher.groupValues[1].toDoubleOrNull() ?: return null
        val lon = matcher.groupValues[2].toDoubleOrNull() ?: return null
        return doubleArrayOf(lat, lon)
    }

    private val ISO_6709 = Regex("""([+\-]\d+(?:\.\d+)?)([+\-]\d+(?:\.\d+)?)""")

    private const val TAG = "Mp4GpsReader"
}
