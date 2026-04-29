package dev.whitphx.nolocationzones.data

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
 * Tracks two things across process restarts:
 *  - which zones the user is currently inside (set by geofence transitions)
 *  - the highest MediaStore image id we have already inspected (so we know what's "new")
 */
class ZoneStateStore(private val context: Context) {
    private val prefs get() = context.dataStore

    val activeZoneIds: Flow<Set<Long>> =
        prefs.data.map { it[ACTIVE_ZONE_IDS]?.mapNotNull(String::toLongOrNull)?.toSet() ?: emptySet() }

    suspend fun isAnyZoneActive(): Boolean = activeZoneIds.first().isNotEmpty()

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

    val lastSeenImageId: Flow<Long> = prefs.data.map { it[LAST_SEEN_IMAGE_ID] ?: 0L }

    suspend fun getLastSeenImageId(): Long = lastSeenImageId.first()

    suspend fun setLastSeenImageId(id: Long) {
        prefs.edit { p ->
            val current = p[LAST_SEEN_IMAGE_ID] ?: 0L
            if (id > current) p[LAST_SEEN_IMAGE_ID] = id
        }
    }

    companion object {
        private val ACTIVE_ZONE_IDS: Preferences.Key<Set<String>> = stringSetPreferencesKey("active_zone_ids")
        private val LAST_SEEN_IMAGE_ID: Preferences.Key<Long> = longPreferencesKey("last_seen_image_id")
    }
}
