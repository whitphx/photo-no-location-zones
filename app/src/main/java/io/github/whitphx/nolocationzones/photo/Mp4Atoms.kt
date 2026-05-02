package io.github.whitphx.nolocationzones.photo

import android.system.Os
import android.system.OsConstants
import java.io.FileDescriptor

/**
 * Minimal ISO BMFF / QuickTime atom walker — the thinnest layer of MP4 parsing the GPS reader and
 * stripper need.
 *
 * An ISO BMFF file is a sequence of "boxes" (a.k.a. atoms): each box is `[size:4][type:4][payload]`.
 *  - `size == 1` means an extended 64-bit size follows in the next 8 bytes.
 *  - `size == 0` means "extends to the end of the enclosing scope" (top-level: end of file).
 * "Container" boxes (e.g. `moov`, `udta`) just contain more boxes; "leaf" boxes have format-specific
 * payloads we don't decode here. The walker is intentionally non-recursive — callers re-invoke it
 * for the children of a container they care about (`moov`, `udta`).
 *
 * On the wire the type field is conventionally a 4-char ASCII code (`'m','o','o','v'`). We pack it
 * into a single 32-bit big-endian Int for cheap equality checks. QuickTime location atoms use the
 * non-ASCII byte `0xA9` ('©'), so we expose [atomBytes] alongside [atom] for those.
 */
internal object Mp4Atoms {
    val MOOV: Int = atom("moov")
    val UDTA: Int = atom("udta")
    val META: Int = atom("meta")
    val FREE: Int = atom("free")

    /** Apple/QuickTime location string in ISO 6709 format: `©xyz`. */
    val XYZ: Int = atomBytes(0xA9, 'x'.code, 'y'.code, 'z'.code)

    /** 3GPP location info atom — older but still seen on some Android cameras. */
    val LOCI: Int = atom("loci")

    /** Atoms whose type-tag the stripper rewrites to `free` to neutralize the GPS payload. */
    val LOCATION_ATOMS: Set<Int> = setOf(XYZ, LOCI)

    /** Wire bytes of `free` — used by the stripper when re-tagging a location atom. */
    val FREE_BYTES: ByteArray = byteArrayOf(
        'f'.code.toByte(),
        'r'.code.toByte(),
        'e'.code.toByte(),
        'e'.code.toByte(),
    )

    fun atom(s: String): Int {
        require(s.length == 4) { "atom code must be 4 chars: $s" }
        return (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code
    }

    fun atomBytes(a: Int, b: Int, c: Int, d: Int): Int =
        ((a and 0xFF) shl 24) or ((b and 0xFF) shl 16) or ((c and 0xFF) shl 8) or (d and 0xFF)

    fun fileSize(fd: FileDescriptor): Long = Os.fstat(fd).st_size

    /** Read [length] bytes at absolute [position] into [buf] starting at [bufOffset]. */
    fun readFully(fd: FileDescriptor, position: Long, buf: ByteArray, bufOffset: Int, length: Int): Int {
        Os.lseek(fd, position, OsConstants.SEEK_SET)
        var got = 0
        while (got < length) {
            val n = Os.read(fd, buf, bufOffset + got, length - got)
            if (n <= 0) break
            got += n
        }
        return got
    }

    /**
     * Walk the boxes between `[start, end)`. For each, invoke [onBox] with:
     *  - `type`: 4-byte type packed big-endian (use [atom] / [atomBytes] for comparisons)
     *  - `payloadStart`: offset of the first payload byte (after size + type, and after any
     *    extended-size word)
     *  - `payloadEnd`: offset one past the last payload byte
     *  - `typeOffset`: offset of the 4-byte type field — useful for in-place re-tag.
     *
     * Aborts the scan silently on a malformed size (negative, zero-length payload, or running off
     * the end). The strip flow tolerates partial walks: if we can't reach a GPS atom we just don't
     * clear it, and the post-strip verification will warn.
     */
    inline fun walkBoxes(
        fd: FileDescriptor,
        start: Long,
        end: Long,
        onBox: (type: Int, payloadStart: Long, payloadEnd: Long, typeOffset: Long) -> Unit,
    ) {
        var pos = start
        val header = ByteArray(8)
        val ext = ByteArray(8)
        while (pos + 8 <= end) {
            val r = readFully(fd, pos, header, 0, 8)
            if (r < 8) return
            val size32 = ((header[0].toInt() and 0xFF) shl 24) or
                ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)
            val type = ((header[4].toInt() and 0xFF) shl 24) or
                ((header[5].toInt() and 0xFF) shl 16) or
                ((header[6].toInt() and 0xFF) shl 8) or
                (header[7].toInt() and 0xFF)
            val typeOffset = pos + 4
            val payloadStart: Long
            val payloadEnd: Long
            when (size32) {
                0 -> {
                    payloadStart = pos + 8
                    payloadEnd = end
                }
                1 -> {
                    val r2 = readFully(fd, pos + 8, ext, 0, 8)
                    if (r2 < 8) return
                    val size64 = ((ext[0].toLong() and 0xFF) shl 56) or
                        ((ext[1].toLong() and 0xFF) shl 48) or
                        ((ext[2].toLong() and 0xFF) shl 40) or
                        ((ext[3].toLong() and 0xFF) shl 32) or
                        ((ext[4].toLong() and 0xFF) shl 24) or
                        ((ext[5].toLong() and 0xFF) shl 16) or
                        ((ext[6].toLong() and 0xFF) shl 8) or
                        (ext[7].toLong() and 0xFF)
                    if (size64 < 16 || pos + size64 > end) return
                    payloadStart = pos + 16
                    payloadEnd = pos + size64
                }
                else -> {
                    if (size32 < 8) return
                    val absEnd = pos + size32.toLong()
                    if (absEnd > end) return
                    payloadStart = pos + 8
                    payloadEnd = absEnd
                }
            }
            if (payloadEnd <= pos) return
            onBox(type, payloadStart, payloadEnd, typeOffset)
            pos = payloadEnd
        }
    }
}
