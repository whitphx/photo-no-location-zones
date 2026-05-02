package io.github.whitphx.nolocationzones.ui

import android.annotation.SuppressLint
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.whitphx.nolocationzones.App
import io.github.whitphx.nolocationzones.data.ZoneRepository
import io.github.whitphx.nolocationzones.data.ZoneStateStore
import io.github.whitphx.nolocationzones.domain.Zone
import io.github.whitphx.nolocationzones.geofence.GeofenceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

sealed interface MapZoneUiState {
    data object Loading : MapZoneUiState
    data class Ready(
        val mode: Mode,
        val editingZoneId: Long?,
        val initialLatitude: Double,
        val initialLongitude: Double,
        val initialRadiusMeters: Float,
        val initialName: String,
        val locationKnown: Boolean,
    ) : MapZoneUiState

    data class Error(val message: String) : MapZoneUiState

    enum class Mode { Create, Edit }
}

class MapZoneViewModel(
    application: Application,
    private val savedZoneId: Long?,
) : AndroidViewModel(application) {
    private val app = application as App
    private val zoneRepo: ZoneRepository = app.container.zoneRepository
    private val stateStore: ZoneStateStore = app.container.zoneStateStore
    private val geofenceController: GeofenceController = app.container.geofenceController

    val existingZones: StateFlow<List<Zone>> =
        zoneRepo.zones.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow<MapZoneUiState>(MapZoneUiState.Loading)
    val uiState: StateFlow<MapZoneUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            _uiState.value = if (savedZoneId != null) loadEdit(savedZoneId) else loadCreate()
        }
    }

    private suspend fun loadEdit(id: Long): MapZoneUiState {
        val zone = zoneRepo.get(id)
            ?: return MapZoneUiState.Error("Zone $id not found")
        return MapZoneUiState.Ready(
            mode = MapZoneUiState.Mode.Edit,
            editingZoneId = id,
            initialLatitude = zone.latitude,
            initialLongitude = zone.longitude,
            initialRadiusMeters = zone.radiusMeters,
            initialName = zone.name,
            locationKnown = true,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun loadCreate(): MapZoneUiState {
        // Try fused current location; if unavailable (no permission, no fix, etc.), fall back to
        // a "world view" so the map is still usable.
        val client = LocationServices.getFusedLocationProviderClient(app)
        val loc = runCatching {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
        }.getOrNull()
        val defaultName = "Zone ${zoneRepo.getAll().size + 1}"
        return if (loc != null) {
            MapZoneUiState.Ready(
                mode = MapZoneUiState.Mode.Create,
                editingZoneId = null,
                initialLatitude = loc.latitude,
                initialLongitude = loc.longitude,
                initialRadiusMeters = Zone.DEFAULT_RADIUS_METERS,
                initialName = defaultName,
                locationKnown = true,
            )
        } else {
            MapZoneUiState.Ready(
                mode = MapZoneUiState.Mode.Create,
                editingZoneId = null,
                initialLatitude = 0.0,
                initialLongitude = 0.0,
                initialRadiusMeters = Zone.DEFAULT_RADIUS_METERS,
                initialName = defaultName,
                locationKnown = false,
            )
        }
    }

    fun save(name: String, latitude: Double, longitude: Double, radiusMeters: Float) {
        viewModelScope.launch {
            val finalName = name.trim().ifBlank { "Zone" }
            val zone = Zone(
                id = savedZoneId ?: 0L,
                name = finalName,
                latitude = latitude,
                longitude = longitude,
                radiusMeters = radiusMeters,
            )
            zoneRepo.upsert(zone)
            geofenceController.syncAll(zoneRepo.getAll())
        }
    }

    fun delete() {
        val id = savedZoneId ?: return
        viewModelScope.launch {
            zoneRepo.get(id)?.let { zoneRepo.delete(it) }
            stateStore.forget(listOf(id))
            geofenceController.syncAll(zoneRepo.getAll())
        }
    }

    companion object {
        fun factory(app: App, zoneId: Long?): ViewModelProvider.Factory =
            viewModelFactory { initializer { MapZoneViewModel(app, zoneId) } }
    }
}
