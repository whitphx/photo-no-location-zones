package io.github.whitphx.nolocationzones.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Snapshot of every runtime permission this app cares about. */
data class PermissionState(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val readImages: Boolean,
    val readVideos: Boolean,
    val mediaLocation: Boolean,
    val postNotifications: Boolean,
) {
    val readyForGeofencing: Boolean get() = fineLocation && backgroundLocation
    val readyForScrubbing: Boolean get() = readImages && readVideos && mediaLocation
    val allGranted: Boolean
        get() = fineLocation && backgroundLocation && readImages && readVideos &&
            mediaLocation && postNotifications
}

object Permissions {
    fun read(context: Context): PermissionState =
        PermissionState(
            fineLocation = isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION),
            backgroundLocation = isGranted(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            readImages = isGranted(
                context,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    Manifest.permission.READ_MEDIA_IMAGES
                } else {
                    Manifest.permission.READ_EXTERNAL_STORAGE
                },
            ),
            readVideos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isGranted(context, Manifest.permission.READ_MEDIA_VIDEO)
            } else {
                // Pre-Tiramisu, READ_EXTERNAL_STORAGE covers both images and videos. Mirror the
                // images flag so the combined gate is satisfied as soon as it is granted.
                isGranted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
            },
            mediaLocation = isGranted(context, Manifest.permission.ACCESS_MEDIA_LOCATION),
            postNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
            } else {
                true
            },
        )

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
