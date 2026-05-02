package io.github.whitphx.nolocationzones.geofence

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.github.whitphx.nolocationzones.App
import io.github.whitphx.nolocationzones.photo.PhotoMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Geofences do not survive reboot — they live in Play Services and are wiped when the device
 * reboots. We re-register them here. Also re-starts the foreground monitor if a zone was active
 * when the device went down (the user may already be inside their home zone at boot time, and
 * INITIAL_TRIGGER_ENTER will refire ENTER once registration completes).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            intent.action != "android.intent.action.MY_PACKAGE_REPLACED"
        ) return

        val app = context.applicationContext as App
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val zones = app.container.zoneRepository.getAll()
                app.container.geofenceController.syncAll(zones)
                // INITIAL_TRIGGER_ENTER will deliver an ENTER for any currently-inside zone, but
                // there can be a brief window before that fires; if we already had active state
                // before reboot, kick the service so we don't miss photos in the meantime.
                if (app.container.zoneStateStore.isAnyZoneActive()) {
                    PhotoMonitorService.start(context)
                }
                Log.i(TAG, "Re-registered ${zones.size} geofence(s) after boot/upgrade")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
