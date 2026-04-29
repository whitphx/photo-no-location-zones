package dev.whitphx.nolocationzones.domain

import android.net.Uri

data class PendingStrip(
    val imageId: Long,
    val contentUri: Uri,
    val displayName: String?,
    val detectedAt: Long,
    val zoneName: String?,
    /** When the photo was taken (EXIF DateTimeOriginal in millis). 0 if unknown. */
    val dateTakenMs: Long = 0L,
)
