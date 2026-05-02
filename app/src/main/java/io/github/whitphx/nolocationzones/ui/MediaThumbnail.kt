package io.github.whitphx.nolocationzones.ui

import android.net.Uri
import android.util.Size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads a MediaStore thumbnail for [uri] sized to [sizePx]×[sizePx]. Returns null while loading or
 * if the load fails (file deleted, decoder error, permission revoked).
 *
 * The system's MediaStore thumbnail cache is hit when available; fresh decodes happen off the main
 * thread. EXIF orientation is already applied by the platform.
 */
@Composable
fun rememberPhotoBitmap(uri: Uri, sizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    var bitmap: ImageBitmap? by remember(uri, sizePx) { mutableStateOf(null) }
    LaunchedEffect(uri, sizePx) {
        bitmap = runCatching {
            withContext(Dispatchers.IO) {
                context.contentResolver
                    .loadThumbnail(uri, Size(sizePx, sizePx), null)
                    .asImageBitmap()
            }
        }.getOrNull()
    }
    return bitmap
}
