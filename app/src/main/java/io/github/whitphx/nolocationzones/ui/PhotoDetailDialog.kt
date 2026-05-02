package io.github.whitphx.nolocationzones.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import io.github.whitphx.nolocationzones.domain.PendingStrip
import io.github.whitphx.nolocationzones.domain.Zone
import io.github.whitphx.nolocationzones.photo.ExifGpsReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full-screen dialog that splits the viewport into two equal panes: the photo on top and the
 * EXIF-derived location map below. Both visible at once, no scrolling. Skip / Strip GPS pinned
 * at the bottom.
 */
@Composable
fun PhotoDetailDialog(
    item: PendingStrip,
    zones: List<Zone>,
    onDismiss: () -> Unit,
    onStrip: () -> Unit,
    onSkip: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(item = item, onDismiss = onDismiss)

                PhotoPane(
                    item = item,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LocationPane(
                    item = item,
                    zones = zones,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onSkip,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    ) { Text("Skip") }
                    Button(
                        onClick = onStrip,
                        modifier = Modifier.weight(1f),
                    ) { Text("Strip GPS") }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun Header(item: PendingStrip, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDismiss) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.displayName ?: "Photo",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            buildSubtitle(item)?.let {
                Text(
                    text = it,
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PhotoPane(item: PendingStrip, modifier: Modifier = Modifier) {
    val config = LocalConfiguration.current
    val sizePx = with(LocalDensity.current) {
        maxOf(config.screenWidthDp.dp.roundToPx(), config.screenHeightDp.dp.roundToPx())
    }
    val bitmap = rememberPhotoBitmap(item.contentUri, sizePx)
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun LocationPane(item: PendingStrip, zones: List<Zone>, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var coords: DoubleArray? by remember(item.imageId) { mutableStateOf(null) }
    var loaded by remember(item.imageId) { mutableStateOf(false) }

    LaunchedEffect(item.imageId) {
        coords = withContext(Dispatchers.IO) {
            ExifGpsReader.readLatLong(context.contentResolver, item.contentUri)
        }
        loaded = true
    }

    Column(modifier = modifier) {
        when {
            !loaded -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = Color.White)
            }
            coords == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column {
                    Text(
                        "No GPS data on this photo.",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Either it never had any, or it has already been stripped.",
                        color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            else -> {
                Text(
                    text = "${"%.6f".format(coords!![0])}, ${"%.6f".format(coords!![1])}",
                    color = Color.White.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                MapBody(
                    lat = coords!![0],
                    lon = coords!![1],
                    zones = zones,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun MapBody(lat: Double, lon: Double, zones: List<Zone>, modifier: Modifier = Modifier) {
    val target = LatLng(lat, lon)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(target, 16f)
    }
    val markerState = rememberUpdatedMarkerState(position = target)
    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false,
            myLocationButtonEnabled = false,
            compassEnabled = true,
        ),
    ) {
        for (z in zones) {
            Circle(
                center = LatLng(z.latitude, z.longitude),
                radius = z.radiusMeters.toDouble(),
                strokeColor = MaterialTheme.colorScheme.primary,
                fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                strokeWidth = 3f,
            )
        }
        Marker(state = markerState, title = "Photo location")
    }
}

private fun buildSubtitle(item: PendingStrip): String? {
    val parts = mutableListOf<String>()
    if (item.dateTakenMs > 0L) {
        parts += formatTimestamp(item.dateTakenMs)
    }
    item.zoneName?.let { parts += it }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
