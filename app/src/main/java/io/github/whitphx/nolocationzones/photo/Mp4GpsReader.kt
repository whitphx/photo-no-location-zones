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

    private fun readGps(fd: FileDescriptor): DoubleArray? = readGps(FdBoxReader(fd))

    /**
     * Visible for testing. Same algorithm as the FD entry point, but operates on any
     * [BoxReader] so JVM tests can drive it with synthetic byte arrays.
     */
    internal fun readGps(reader: BoxReader): DoubleArray? {
        val end = reader.size
        var found: DoubleArray? = null
        Mp4Atoms.walkBoxes(reader, 0L, end) { type, payloadStart, payloadEnd, _ ->
            if (type != Mp4Atoms.MOOV || found != null) return@walkBoxes
            Mp4Atoms.walkBoxes(reader, payloadStart, payloadEnd) { t2, p2s, p2e, _ ->
                if (t2 != Mp4Atoms.UDTA || found != null) return@walkBoxes
                Mp4Atoms.walkBoxes(reader, p2s, p2e) { t3, p3s, p3e, _ ->
                    if (t3 != Mp4Atoms.XYZ || found != null) return@walkBoxes
                    val len = (p3e - p3s).toInt()
                    if (len < 1) return@walkBoxes
                    val buf = ByteArray(len)
                    val r = reader.read(p3s, buf, 0, len)
                    if (r < 1) return@walkBoxes
                    found = parseAtomPayload(buf, r)
                }
            }
        }
        return found
    }

    /**
     * Most cameras follow the QuickTime convention — `[u16 textLen][u16 language][text]` — so
     * we try the prefixed parse first. A few non-conformant writers store the raw ISO 6709
     * string with no header; in that case the prefixed parse either returns null (range
     * validation rejects bogus numbers) or never matches because the regex needs a leading
     * sign at offset 4. The fallback re-runs the regex against the entire payload.
     */
    internal fun parseAtomPayload(buf: ByteArray, length: Int): DoubleArray? {
        if (length >= 4) {
            val textLen = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
            val effective = minOf(textLen, length - 4).coerceAtLeast(0)
            if (effective > 0) {
                parseIso6709(String(buf, 4, effective, StandardCharsets.UTF_8))?.let { return it }
            }
        }
        return parseIso6709(String(buf, 0, length, StandardCharsets.UTF_8))
    }

    /**
     * ISO 6709 simple form: `±DD.DDDD±DDD.DDDD[±AAA.AAA]/`. We pluck the first two signed numbers
     * and ignore altitude / CRS suffixes — we only need lat/lon for the in-app map. Both values
     * are range-validated so a partial parse (e.g. when a leading sign was stripped) is rejected
     * rather than returned as a real location.
     */
    internal fun parseIso6709(s: String): DoubleArray? {
        val matcher = ISO_6709.find(s) ?: return null
        val lat = matcher.groupValues[1].toDoubleOrNull() ?: return null
        val lon = matcher.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return doubleArrayOf(lat, lon)
    }

    private val ISO_6709 = Regex("""([+\-]\d+(?:\.\d+)?)([+\-]\d+(?:\.\d+)?)""")

    private const val TAG = "Mp4GpsReader"
}
