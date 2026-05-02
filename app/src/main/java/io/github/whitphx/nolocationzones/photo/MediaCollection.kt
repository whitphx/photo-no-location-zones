package io.github.whitphx.nolocationzones.photo

import android.net.Uri
import android.provider.MediaStore

/**
 * The two MediaStore collections this app scans for GPS-tagged content. Each collection has its
 * own URI, MIME-type allowlist, and per-row GPS-presence check (EXIF for images, MP4 atom walk
 * for videos). The `_ID` namespace is shared across both collections — MediaStore stores all
 * rows in a single underlying table — but we still scan them separately because the cursor
 * URI dictates which legacy projection columns are guaranteed to resolve, and because the
 * queue's "last seen" cutoff is naturally per-collection (videos are added far less frequently
 * than images, so a unified cutoff would skip videos sitting between two image bursts).
 */
internal enum class MediaCollection(
    val uri: Uri,
    val mimeTypes: List<String>,
    val tag: String,
) {
    Images(
        uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        // image/jpeg covers stock-camera photos. image/heif + image/heic cover Apple-style HEIC and
        // Pixel HEIF. AndroidX ExifInterface 1.4 reads/writes all three.
        mimeTypes = listOf("image/jpeg", "image/heif", "image/heic"),
        tag = "image",
    ),
    Videos(
        uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        // video/mp4 covers Android stock camera videos. video/quicktime is iPhone-recorded MOV.
        // video/3gpp is older Android. All three are ISO BMFF / QuickTime — the same atom layout
        // Mp4GpsStripper walks.
        mimeTypes = listOf("video/mp4", "video/quicktime", "video/3gpp"),
        tag = "video",
    ),
}
