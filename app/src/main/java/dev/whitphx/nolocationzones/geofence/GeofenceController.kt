package dev.whitphx.nolocationzones.geofence

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import dev.whitphx.nolocationzones.domain.Zone
import kotlinx.coroutines.tasks.await

/**
 * Wraps Play Services geofencing. Each [Zone] is registered as one circular geofence whose
 * request id is `zone-<id>`. Both ENTER and EXIT transitions are reported.
 */
class GeofenceController(private val context: Context) {
    private val client: GeofencingClient = LocationServices.getGeofencingClient(context)

    private val pendingIntent: PendingIntent by lazy {
        val intent = Intent(context, GeofenceBroadcastReceiver::class.java).apply {
            action = ACTION_TRANSITION
        }
        PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun hasBackgroundLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Re-register the supplied zones, replacing any previously-registered geofences. */
    @SuppressLint("MissingPermission") // checked at the top of this function
    suspend fun syncAll(zones: List<Zone>) {
        if (!hasFineLocationPermission() || !hasBackgroundLocationPermission()) {
            Log.w(TAG, "Skipping geofence sync — missing location permissions")
            return
        }
        // Remove everything first to keep state in sync with our DB.
        runCatching { client.removeGeofences(pendingIntent).await() }
            .onFailure { Log.w(TAG, "removeGeofences failed (likely none registered)", it) }

        if (zones.isEmpty()) return

        val fences = zones.map { z ->
            Geofence.Builder()
                .setRequestId(toRequestId(z.id))
                .setCircularRegion(z.latitude, z.longitude, z.radiusMeters)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(
                    Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT
                )
                .build()
        }
        val request = GeofencingRequest.Builder()
            // INITIAL_TRIGGER_ENTER: fire ENTER immediately if user already inside a zone
            // when registration completes (e.g. after reboot).
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(fences)
            .build()

        try {
            client.addGeofences(request, pendingIntent).await()
            Log.i(TAG, "Registered ${fences.size} geofence(s)")
        } catch (t: Throwable) {
            Log.e(TAG, "addGeofences failed", t)
        }
    }

    suspend fun removeAll() {
        runCatching { client.removeGeofences(pendingIntent).await() }
    }

    suspend fun remove(zoneIds: Collection<Long>) {
        if (zoneIds.isEmpty()) return
        runCatching { client.removeGeofences(zoneIds.map(::toRequestId)).await() }
    }

    companion object {
        private const val TAG = "GeofenceController"
        const val ACTION_TRANSITION = "dev.whitphx.nolocationzones.GEOFENCE_TRANSITION"

        fun toRequestId(zoneId: Long): String = "zone-$zoneId"

        fun fromRequestId(requestId: String): Long? =
            requestId.removePrefix("zone-").toLongOrNull()
    }
}
