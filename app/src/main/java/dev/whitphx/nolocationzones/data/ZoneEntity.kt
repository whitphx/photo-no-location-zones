package dev.whitphx.nolocationzones.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.whitphx.nolocationzones.domain.Zone

@Entity(tableName = "zones")
data class ZoneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "radius_meters") val radiusMeters: Float,
) {
    fun toDomain(): Zone =
        Zone(
            id = id,
            name = name,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
        )

    companion object {
        fun fromDomain(zone: Zone): ZoneEntity =
            ZoneEntity(
                id = zone.id,
                name = zone.name,
                latitude = zone.latitude,
                longitude = zone.longitude,
                radiusMeters = zone.radiusMeters,
            )
    }
}
