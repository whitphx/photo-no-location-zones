package dev.whitphx.nolocationzones.di

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import dev.whitphx.nolocationzones.data.AppDatabase
import dev.whitphx.nolocationzones.data.PendingStripRepository
import dev.whitphx.nolocationzones.data.ZoneRepository
import dev.whitphx.nolocationzones.data.ZoneStateStore
import dev.whitphx.nolocationzones.geofence.GeofenceController
import dev.whitphx.nolocationzones.photo.PendingStripReconciler
import dev.whitphx.nolocationzones.photo.PhotoRescanner

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val database by lazy { AppDatabase.create(appContext) }

    val zoneRepository by lazy { ZoneRepository(database.zoneDao()) }
    val pendingStripRepository by lazy { PendingStripRepository(database.pendingStripDao()) }
    val zoneStateStore by lazy { ZoneStateStore(appContext) }
    val geofenceController by lazy { GeofenceController(appContext) }
    val photoRescanner by lazy { PhotoRescanner(appContext, zoneRepository, pendingStripRepository) }
    val pendingStripReconciler by lazy {
        PendingStripReconciler(
            resolver = appContext.contentResolver,
            pendingRepo = pendingStripRepository,
            notificationManager = NotificationManagerCompat.from(appContext),
        )
    }
}
