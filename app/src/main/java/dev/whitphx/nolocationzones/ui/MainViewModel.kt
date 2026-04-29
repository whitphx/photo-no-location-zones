package dev.whitphx.nolocationzones.ui

import android.annotation.SuppressLint
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.whitphx.nolocationzones.App
import dev.whitphx.nolocationzones.data.ZoneRepository
import dev.whitphx.nolocationzones.data.ZoneStateStore
import dev.whitphx.nolocationzones.domain.Zone
import dev.whitphx.nolocationzones.geofence.GeofenceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class ZoneListItem(val zone: Zone, val isActive: Boolean)

data class ProposedZone(val name: String, val latitude: Double, val longitude: Double, val radiusMeters: Float)

sealed interface AddZoneState {
    data object Idle : AddZoneState
    data object FetchingLocation : AddZoneState
    data class GotLocation(val latitude: Double, val longitude: Double) : AddZoneState
    data class Error(val message: String) : AddZoneState
}

class MainViewModel(private val app: App) : ViewModel() {
    private val zoneRepo: ZoneRepository = app.container.zoneRepository
    private val stateStore: ZoneStateStore = app.container.zoneStateStore
    private val geofenceController: GeofenceController = app.container.geofenceController

    val zoneList: StateFlow<List<ZoneListItem>> =
        combine(zoneRepo.zones, stateStore.activeZoneIds) { zones, active ->
            zones.map { ZoneListItem(it, it.id in active) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _addZoneState = MutableStateFlow<AddZoneState>(AddZoneState.Idle)
    val addZoneState: StateFlow<AddZoneState> = _addZoneState.asStateFlow()

    @SuppressLint("MissingPermission")
    fun captureCurrentLocation() {
        _addZoneState.value = AddZoneState.FetchingLocation
        viewModelScope.launch {
            val client = LocationServices.getFusedLocationProviderClient(app)
            try {
                val loc = client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
                if (loc == null) {
                    _addZoneState.value = AddZoneState.Error("Couldn't get a location fix. Step outside or try again.")
                } else {
                    _addZoneState.value = AddZoneState.GotLocation(loc.latitude, loc.longitude)
                }
            } catch (t: Throwable) {
                _addZoneState.value = AddZoneState.Error(t.message ?: "Location request failed")
            }
        }
    }

    fun resetAddZone() {
        _addZoneState.value = AddZoneState.Idle
    }

    fun saveZone(proposed: ProposedZone) {
        viewModelScope.launch {
            zoneRepo.upsert(
                Zone(
                    id = 0L,
                    name = proposed.name,
                    latitude = proposed.latitude,
                    longitude = proposed.longitude,
                    radiusMeters = proposed.radiusMeters,
                )
            )
            geofenceController.syncAll(zoneRepo.getAll())
            _addZoneState.value = AddZoneState.Idle
        }
    }

    fun deleteZone(zone: Zone) {
        viewModelScope.launch {
            zoneRepo.delete(zone)
            stateStore.forget(listOf(zone.id))
            geofenceController.syncAll(zoneRepo.getAll())
        }
    }

    /** Called whenever the permission card transitions to "all granted" so geofences register. */
    fun resyncGeofences() {
        viewModelScope.launch {
            geofenceController.syncAll(zoneRepo.getAll())
        }
    }

    companion object {
        fun factory(app: App): ViewModelProvider.Factory =
            viewModelFactory { initializer { MainViewModel(app) } }
    }
}
