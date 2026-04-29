package dev.whitphx.nolocationzones.di

import android.content.Context
import dev.whitphx.nolocationzones.data.AppDatabase
import dev.whitphx.nolocationzones.data.ZoneRepository
import dev.whitphx.nolocationzones.data.ZoneStateStore
import dev.whitphx.nolocationzones.geofence.GeofenceController

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database by lazy { AppDatabase.create(appContext) }

    val zoneRepository by lazy { ZoneRepository(database.zoneDao()) }
    val zoneStateStore by lazy { ZoneStateStore(appContext) }
    val geofenceController by lazy { GeofenceController(appContext) }
}
