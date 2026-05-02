package io.github.whitphx.nolocationzones.photo

import android.content.ContentResolver
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.FileDescriptor
import java.io.IOException

/**
 * Removes location atoms (`moov/udta/©xyz`, `moov/udta/loci`) from an MP4 / MOV by overwriting
 * each atom's 4-byte type field in place with `free`.
 *
 * Why retag instead of delete: a `free` box is a valid ISO BMFF atom whose contents must be
 * ignored by readers. Retagging keeps the file's byte layout identical (every other atom's offset
 * is preserved), so we never have to relocate `moov`, recompute chunk offsets in `stco`/`co64`, or
 * re-index `mdat`. The cost is a sliver of dead bytes — worth it for the simplicity.
 *
 * What we don't cover (documented in README "Privacy gaps"):
 *  - **Apple `moov/meta/keys` + `meta/ilst` location indirection** (used by iPhone-recorded
 *    videos). Decoding requires walking the keys table and matching by name; not yet implemented.
 *  - **`mdat`-embedded telemetry** (GoPro GPMF, DJI subtitle tracks). Different file altogether.
 *
 * Caller must hold write access to [uri] — typically obtained via
 * [android.provider.MediaStore.createWriteRequest].
 */
object Mp4GpsStripper {

    sealed interface Result {
        data object NoChange : Result
        data class Stripped(val locationAtomsCleared: Int) : Result
        data class Failed(val cause: Throwable) : Result
    }

    /** Read-only existence check. Cheap because we walk only `moov/udta`, never the bulk `mdat`. */
    fun hasLocationAtoms(resolver: ContentResolver, uri: Uri): Boolean = try {
        resolver.openFileDescriptor(uri, "r")?.use { pfd ->
            findLocationAtomTypeOffsets(pfd.fileDescriptor).isNotEmpty()
        } ?: false
    } catch (t: Throwable) {
        Log.w(TAG, "hasLocationAtoms failed for $uri", t)
        false
    }

    fun strip(resolver: ContentResolver, uri: Uri): Result {
        val pfd = try {
            resolver.openFileDescriptor(uri, "rw")
        } catch (t: Throwable) {
            return Result.Failed(t)
        } ?: return Result.Failed(IOException("openFileDescriptor returned null for $uri"))

        val result = pfd.use { descriptor ->
            try {
                val fd = descriptor.fileDescriptor
                val typeOffsets = findLocationAtomTypeOffsets(fd)
                if (typeOffsets.isEmpty()) {
                    Result.NoChange
                } else {
                    for (off in typeOffsets) {
                        Os.lseek(fd, off, OsConstants.SEEK_SET)
                        Os.write(fd, Mp4Atoms.FREE_BYTES, 0, 4)
                    }
                    runCatching { Os.fsync(fd) }
                    Log.i(TAG, "Cleared ${typeOffsets.size} location atom(s) from $uri")
                    Result.Stripped(typeOffsets.size)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Strip failed for $uri", t)
                Result.Failed(t)
            }
        }

        if (result is Result.Stripped) {
            verifyClean(resolver, uri)
            runCatching { resolver.notifyChange(uri, null) }
        }
        return result
    }

    private fun findLocationAtomTypeOffsets(fd: FileDescriptor): List<Long> {
        val end = Mp4Atoms.fileSize(fd)
        val results = mutableListOf<Long>()
        Mp4Atoms.walkBoxes(fd, 0L, end) { type, payloadStart, payloadEnd, _ ->
            if (type != Mp4Atoms.MOOV) return@walkBoxes
            Mp4Atoms.walkBoxes(fd, payloadStart, payloadEnd) { t2, p2s, p2e, _ ->
                if (t2 != Mp4Atoms.UDTA) return@walkBoxes
                Mp4Atoms.walkBoxes(fd, p2s, p2e) { t3, _, _, t3o ->
                    if (t3 in Mp4Atoms.LOCATION_ATOMS) results += t3o
                }
            }
        }
        return results
    }

    /** Re-walk after the strip and warn loudly if any location atom survived. */
    private fun verifyClean(resolver: ContentResolver, uri: Uri) {
        try {
            resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                val survivors = findLocationAtomTypeOffsets(pfd.fileDescriptor)
                if (survivors.isNotEmpty()) {
                    Log.w(TAG, "Post-strip verification: ${survivors.size} location atom(s) survived for $uri")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Post-strip verification read failed for $uri", t)
        }
    }

    private const val TAG = "Mp4GpsStripper"
}
