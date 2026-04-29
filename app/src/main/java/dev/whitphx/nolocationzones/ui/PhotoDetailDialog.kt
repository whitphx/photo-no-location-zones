package dev.whitphx.nolocationzones.ui

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.whitphx.nolocationzones.domain.PendingStrip
import java.text.DateFormat
import java.util.Date

/**
 * Full-screen dialog that shows the photo at near-display resolution and offers the same three
 * actions as the in-list row: Strip GPS / Show location / Skip.
 *
 * "Show location" is meant to open [LocationPreviewDialog] *on top of* this dialog (Compose's
 * [Dialog] stacks natively), so dismissing the location dialog returns the user to this one.
 */
@Composable
fun PhotoDetailDialog(
    item: PendingStrip,
    onDismiss: () -> Unit,
    onStrip: () -> Unit,
    onShowLocation: () -> Unit,
    onSkip: () -> Unit,
) {
    val config = LocalConfiguration.current
    val sizePx = with(LocalDensity.current) {
        maxOf(config.screenWidthDp.dp.roundToPx(), config.screenHeightDp.dp.roundToPx())
    }
    val bitmap = rememberPhotoBitmap(item.contentUri, sizePx)

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
                // Top bar
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
                        val subtitle = buildSubtitle(item)
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                color = Color.White.copy(alpha = 0.8f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }

                // Photo
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    } else {
                        CircularProgressIndicator(color = Color.White)
                    }
                }

                // Action row
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
                    OutlinedButton(
                        onClick = onShowLocation,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    ) { Text("Location") }
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

private fun buildSubtitle(item: PendingStrip): String? {
    val parts = mutableListOf<String>()
    if (item.dateTakenMs > 0L) {
        parts += "Taken " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(item.dateTakenMs))
    }
    item.zoneName?.let { parts += it }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
