package dev.whitphx.nolocationzones.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per photo we have detected (while inside a zone) that still has GPS metadata and is
 * awaiting user consent to scrub. Survives process death and reboots.
 */
@Entity(tableName = "pending_strips")
data class PendingStripEntity(
    @PrimaryKey val imageId: Long,
    @ColumnInfo(name = "content_uri") val contentUri: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "detected_at") val detectedAt: Long,
    @ColumnInfo(name = "zone_name") val zoneName: String?,
)
