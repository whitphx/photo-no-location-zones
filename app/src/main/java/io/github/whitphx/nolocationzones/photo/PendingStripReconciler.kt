package io.github.whitphx.nolocationzones.photo

import android.content.ContentResolver
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import io.github.whitphx.nolocationzones.data.PendingStripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reconciles the pending-strip queue against MediaStore: any queued media id that no longer
 * resolves to a real entry (the user deleted the file from their gallery, the file was moved,
 * etc.) is dropped from the queue and its notification is dismissed.
 *
 * The presence check goes through `MediaStore.Files` — a single query that sees both images and
 * videos — because the queue can contain either.
 *
 * Without this, the queue accumulates ghost rows that fail to load thumbnails and crash the
 * Strip flow with `openFileDescriptor` errors.
 */
class PendingStripReconciler(
    private val resolver: ContentResolver,
    private val pendingRepo: PendingStripRepository,
    private val notificationManager: NotificationManagerCompat,
) {
    /** Returns the number of queued entries that were pruned. */
    suspend fun reconcile(): Int = withContext(Dispatchers.IO) {
        val all = pendingRepo.getAll()
        if (all.isEmpty()) return@withContext 0
        val ids = all.map { it.imageId }

        // SQLite's IN clause is capped at 999 parameters. Pending lists won't realistically
        // exceed that, but chunk anyway so we don't surprise ourselves later.
        val filesUri = MediaStore.Files.getContentUri("external")
        val existing = HashSet<Long>(ids.size)
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            val cursor = runCatching {
                resolver.query(
                    filesUri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns._ID} IN ($placeholders)",
                    chunk.map(Long::toString).toTypedArray(),
                    null,
                )
            }.getOrNull() ?: return@forEach
            cursor.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                while (c.moveToNext()) existing += c.getLong(idCol)
            }
        }

        val missing = ids.filterNot { it in existing }
        if (missing.isEmpty()) return@withContext 0

        pendingRepo.remove(missing)
        for (id in missing) {
            notificationManager.cancel(PhotoActionReceiver.notificationIdFor(id))
        }
        Log.i(TAG, "Reconcile dropped ${missing.size} deleted/missing photos: $missing")
        missing.size
    }

    private companion object {
        const val TAG = "PendingStripReconciler"
    }
}
