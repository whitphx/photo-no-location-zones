package io.github.whitphx.nolocationzones.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "zone_state")

/**
 * Tracks state that must survive process restarts:
 *  - which zones the user is currently inside (set by geofence transitions)
 *  - the highest MediaStore image id we have already inspected
 *  - the highest MediaStore video id we have already inspected
 *
 * Image and video cutoffs are kept separately because the two MediaStore collections are queried
 * with their own cursors — `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` and
 * `MediaStore.Video.Media.EXTERNAL_CONTENT_URI`. A row's `_ID` is globally unique across both
 * collections, but tracking per-collection cutoffs avoids any "scan everything once on first
 * launch" behaviour when the unified video path was added.
 */
class ZoneStateStore(private val context: Context) {
    private val prefs get() = context.dataStore

    val activeZoneIds: Flow<Set<Long>> =
        prefs.data.map { it[ACTIVE_ZONE_IDS]?.mapNotNull(String::toLongOrNull)?.toSet() ?: emptySet() }

    suspend fun isAnyZoneActive(): Boolean = activeZoneIds.first().isNotEmpty()

    suspend fun firstActiveZoneId(): Long? = activeZoneIds.first().firstOrNull()

    suspend fun markEntered(ids: Collection<Long>) {
        prefs.edit { p ->
            val current = p[ACTIVE_ZONE_IDS]?.toMutableSet() ?: mutableSetOf()
            current.addAll(ids.map(Long::toString))
            p[ACTIVE_ZONE_IDS] = current
        }
    }

    suspend fun markExited(ids: Collection<Long>) {
        prefs.edit { p ->
            val current = p[ACTIVE_ZONE_IDS]?.toMutableSet() ?: mutableSetOf()
            current.removeAll(ids.map(Long::toString).toSet())
            p[ACTIVE_ZONE_IDS] = current
        }
    }

    suspend fun forget(ids: Collection<Long>) = markExited(ids)

    suspend fun clearAll() {
        prefs.edit { it.remove(ACTIVE_ZONE_IDS) }
    }

    suspend fun getLastSeenImageId(): Long =
        prefs.data.map { it[LAST_SEEN_IMAGE_ID] ?: 0L }.first()

    suspend fun setLastSeenImageId(id: Long) {
        prefs.edit { p ->
            val current = p[LAST_SEEN_IMAGE_ID] ?: 0L
            if (id > current) p[LAST_SEEN_IMAGE_ID] = id
        }
    }

    suspend fun getLastSeenVideoId(): Long =
        prefs.data.map { it[LAST_SEEN_VIDEO_ID] ?: 0L }.first()

    suspend fun setLastSeenVideoId(id: Long) {
        prefs.edit { p ->
            val current = p[LAST_SEEN_VIDEO_ID] ?: 0L
            if (id > current) p[LAST_SEEN_VIDEO_ID] = id
        }
    }

    companion object {
        private val ACTIVE_ZONE_IDS: Preferences.Key<Set<String>> = stringSetPreferencesKey("active_zone_ids")
        private val LAST_SEEN_IMAGE_ID: Preferences.Key<Long> = longPreferencesKey("last_seen_image_id")
        private val LAST_SEEN_VIDEO_ID: Preferences.Key<Long> = longPreferencesKey("last_seen_video_id")
    }
}
