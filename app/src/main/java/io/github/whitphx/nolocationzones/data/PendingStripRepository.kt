package io.github.whitphx.nolocationzones.data

import android.net.Uri
import io.github.whitphx.nolocationzones.domain.PendingStrip
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class PendingStripRepository(private val dao: PendingStripDao) {
    val all: Flow<List<PendingStrip>> = dao.observeAll().map { it.map(::toDomain) }
    val count: Flow<Int> = dao.observeCount()

    suspend fun getAll(): List<PendingStrip> = dao.getAll().map(::toDomain)

    suspend fun add(item: PendingStrip) {
        dao.insert(
            PendingStripEntity(
                imageId = item.imageId,
                contentUri = item.contentUri.toString(),
                displayName = item.displayName,
                detectedAt = item.detectedAt,
                zoneName = item.zoneName,
                dateTakenMs = item.dateTakenMs,
                mimeType = item.mimeType,
            )
        )
    }

    suspend fun remove(ids: Collection<Long>) = dao.deleteByIds(ids)
    suspend fun clear() = dao.deleteAll()

    private fun toDomain(e: PendingStripEntity): PendingStrip =
        PendingStrip(
            imageId = e.imageId,
            contentUri = Uri.parse(e.contentUri),
            displayName = e.displayName,
            detectedAt = e.detectedAt,
            zoneName = e.zoneName,
            dateTakenMs = e.dateTakenMs,
            mimeType = e.mimeType,
        )
}
