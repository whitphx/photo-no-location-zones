package io.github.whitphx.nolocationzones.di

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import io.github.whitphx.nolocationzones.data.AppDatabase
import io.github.whitphx.nolocationzones.data.PendingStripRepository
import io.github.whitphx.nolocationzones.data.ZoneRepository
import io.github.whitphx.nolocationzones.data.ZoneStateStore
import io.github.whitphx.nolocationzones.geofence.GeofenceController
import io.github.whitphx.nolocationzones.photo.PendingStripReconciler
import io.github.whitphx.nolocationzones.photo.PhotoRescanner

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
