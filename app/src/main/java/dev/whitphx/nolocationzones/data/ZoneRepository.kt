package dev.whitphx.nolocationzones.data

import dev.whitphx.nolocationzones.domain.Zone
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ZoneRepository(private val dao: ZoneDao) {
    val zones: Flow<List<Zone>> = dao.observeAll().map { list -> list.map(ZoneEntity::toDomain) }

    suspend fun getAll(): List<Zone> = dao.getAll().map(ZoneEntity::toDomain)

    suspend fun get(id: Long): Zone? = dao.getById(id)?.toDomain()

    suspend fun upsert(zone: Zone): Long = dao.insert(ZoneEntity.fromDomain(zone))

    suspend fun delete(zone: Zone) = dao.delete(ZoneEntity.fromDomain(zone))
}
