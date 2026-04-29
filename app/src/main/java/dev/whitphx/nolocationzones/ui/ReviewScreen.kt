package dev.whitphx.nolocationzones.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whitphx.nolocationzones.domain.PendingStrip
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(viewModel: ReviewViewModel, onClose: () -> Unit) {
    val items by viewModel.pending.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    var pendingTargetIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var preview: PendingStrip? by remember { mutableStateOf(null) }

    val writeAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.onWriteGranted(pendingTargetIds)
        } else {
            viewModel.onWriteDenied()
        }
        pendingTargetIds = emptyList()
    }

    LaunchedEffect(event) {
        when (val e = event) {
            is ReviewEvent.RequestWriteAccess -> {
                pendingTargetIds = e.targetIds
                writeAccessLauncher.launch(IntentSenderRequest.Builder(e.intent.intentSender).build())
                viewModel.consumeEvent()
            }
            is ReviewEvent.StripCompleted -> {
                val parts = buildList {
                    if (e.stripped > 0) add("${e.stripped} stripped")
                    if (e.skipped > 0) add("${e.skipped} already clean")
                    if (e.failed > 0) add("${e.failed} failed")
                }
                snackbar.showSnackbar(parts.joinToString(", ").ifEmpty { "No changes" })
                viewModel.consumeEvent()
            }
            is ReviewEvent.Error -> {
                snackbar.showSnackbar(e.message)
                viewModel.consumeEvent()
            }
            null -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Review pending photos") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            if (items.isEmpty()) {
                EmptyHint(modifier = Modifier.weight(1f))
            } else {
                Header(count = items.size)
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(items, key = { it.imageId }) { item ->
                        PendingRow(
                            item = item,
                            onClick = { preview = item },
                            onSkip = { viewModel.skipOne(item.imageId) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { viewModel.skipAll() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Skip all") }
                    Button(
                        onClick = { viewModel.requestStripAll() },
                        modifier = Modifier.weight(1f),
                    ) { Text("Strip all") }
                }
            }
        }
    }

    preview?.let { item ->
        PhotoPreviewDialog(
            uri = item.contentUri,
            displayName = item.displayName,
            onDismiss = { preview = null },
        )
    }
}

@Composable
private fun Header(count: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "$count photo${if (count == 1) "" else "s"} taken inside a zone",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tapping Strip all opens a system dialog asking you to grant write access to these specific files. After you accept, GPS metadata is removed in place — no other change to the photos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PendingRow(item: PendingStrip, onClick: () -> Unit, onSkip: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(item = item, sizeDp = 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.displayName ?: "Image ${item.imageId}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(item.detectedAt))
                val zone = item.zoneName?.let { " · $it" } ?: ""
                Text(
                    "Detected $time$zone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Tap to preview",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onSkip) {
                Icon(Icons.Filled.Close, contentDescription = "Skip this photo")
            }
        }
    }
}

@Composable
private fun EmptyHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No photos waiting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Photos taken inside a zone will appear here for review.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Thumbnail(item: PendingStrip, sizeDp: Dp) {
    val sizePx = with(LocalDensity.current) { sizeDp.roundToPx() }
    val bitmap = rememberPhotoBitmap(item.contentUri, sizePx)
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(sizeDp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = "Thumbnail of ${item.displayName ?: "image ${item.imageId}"}",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Filled.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(sizeDp / 2),
            )
        }
    }
}
