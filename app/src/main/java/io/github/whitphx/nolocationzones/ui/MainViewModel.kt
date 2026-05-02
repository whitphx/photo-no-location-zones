package io.github.whitphx.nolocationzones.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.whitphx.nolocationzones.App
import io.github.whitphx.nolocationzones.data.PendingStripRepository
import io.github.whitphx.nolocationzones.data.ZoneRepository
import io.github.whitphx.nolocationzones.data.ZoneStateStore
import io.github.whitphx.nolocationzones.domain.Zone
import io.github.whitphx.nolocationzones.geofence.GeofenceController
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ZoneListItem(val zone: Zone, val isActive: Boolean)

class MainViewModel(app: App) : ViewModel() {
    private val zoneRepo: ZoneRepository = app.container.zoneRepository
    private val stateStore: ZoneStateStore = app.container.zoneStateStore
    private val geofenceController: GeofenceController = app.container.geofenceController
    private val pendingRepo: PendingStripRepository = app.container.pendingStripRepository

    val zoneList: StateFlow<List<ZoneListItem>> =
        combine(zoneRepo.zones, stateStore.activeZoneIds) { zones, active ->
            zones.map { ZoneListItem(it, it.id in active) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCount: StateFlow<Int> =
        pendingRepo.count.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

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
