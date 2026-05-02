package io.github.whitphx.nolocationzones.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import io.github.whitphx.nolocationzones.App
import io.github.whitphx.nolocationzones.photo.PhotoMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GeofenceBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) {
            Log.e(TAG, "Geofence error code=${event.errorCode}")
            return
        }
        val triggering = event.triggeringGeofences ?: return
        val zoneIds = triggering.mapNotNull { GeofenceController.fromRequestId(it.requestId) }
        if (zoneIds.isEmpty()) return

        val transition = event.geofenceTransition
        val app = context.applicationContext as App
        val store = app.container.zoneStateStore

        // BroadcastReceiver onReceive runs on the main thread with a short window.
        // goAsync() lets us use suspend code; we MUST call finish() when done.
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (transition) {
                    Geofence.GEOFENCE_TRANSITION_ENTER -> {
                        store.markEntered(zoneIds)
                        Log.i(TAG, "ENTER zones=$zoneIds")
                        PhotoMonitorService.start(context)
                    }
                    Geofence.GEOFENCE_TRANSITION_EXIT -> {
                        store.markExited(zoneIds)
                        Log.i(TAG, "EXIT zones=$zoneIds")
                        if (!store.isAnyZoneActive()) {
                            PhotoMonitorService.stop(context)
                        }
                    }
                    else -> Log.w(TAG, "Unknown transition $transition")
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "GeofenceReceiver"
    }
}
