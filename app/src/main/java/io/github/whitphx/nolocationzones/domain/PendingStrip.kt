package io.github.whitphx.nolocationzones.domain

import android.net.Uri

data class PendingStrip(
    val imageId: Long,
    val contentUri: Uri,
    val displayName: String?,
    val detectedAt: Long,
    val zoneName: String?,
    /** When the photo was taken (EXIF DateTimeOriginal in millis). 0 if unknown. */
    val dateTakenMs: Long = 0L,
    /**
     * MediaStore MIME type at queue time, e.g. `image/jpeg` or `video/mp4`. Drives the strip
     * dispatcher (EXIF for images, MP4 atom rewrite for videos) and the UI badge.
     */
    val mimeType: String? = null,
) {
    val isVideo: Boolean get() = mimeType?.startsWith("video/", ignoreCase = true) == true
}
