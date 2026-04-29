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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.whitphx.nolocationzones.photo.PhotoRescanner
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel,
    pendingAction: PendingAction? = null,
    onActionConsumed: () -> Unit = {},
    onClose: () -> Unit,
) {
    val items by viewModel.pending.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsState()
    val rescanning by viewModel.rescanning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var pendingTargetIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var preview: PendingStrip? by remember { mutableStateOf(null) }
    var skipAllConfirm by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var rescanMenuOpen by remember { mutableStateOf(false) }
    val zones by viewModel.zones.collectAsStateWithLifecycle()
    val sortBy by viewModel.sortBy.collectAsStateWithLifecycle()

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
            is ReviewEvent.RescanCompleted -> {
                val window = if (e.daysBack == PhotoRescanner.DAYS_BACK_ALL) "all photos"
                else "last ${e.daysBack}d"
                val msg = when {
                    e.zonesAtScan == 0 -> "Add a zone first — nothing to scan against."
                    e.matched == 0 -> "Scanned ${e.scanned} ($window): ${e.noGps} have no GPS, " +
                        "${e.scanned - e.noGps} have GPS but none fall inside a zone."
                    else -> "Found ${e.matched} of ${e.scanned} ($window) — " +
                        "${e.noGps} skipped because they have no GPS."
                }
                snackbar.showSnackbar(msg)
                viewModel.consumeEvent()
            }
            is ReviewEvent.Error -> {
                snackbar.showSnackbar(e.message)
                viewModel.consumeEvent()
            }
            null -> Unit
        }
    }

    // Notification deep-links: react to pendingAction once. For ShowLocation we need the queue
    // entry (for displayName + zoneName); if the queue no longer contains the photo (e.g. already
    // skipped or stripped) we synthesise a minimal PendingStrip from the imageId so the location
    // tab still opens against the file.
    LaunchedEffect(pendingAction, items) {
        when (val action = pendingAction) {
            is PendingAction.StripPhoto -> {
                viewModel.requestStripOne(action.imageId)
                onActionConsumed()
            }
            is PendingAction.ShowLocation -> {
                val existing = items.firstOrNull { it.imageId == action.imageId }
                preview = existing ?: PendingStrip(
                    imageId = action.imageId,
                    contentUri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        action.imageId,
                    ),
                    displayName = null,
                    detectedAt = System.currentTimeMillis(),
                    zoneName = null,
                )
                onActionConsumed()
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
                actions = {
                    IconButton(
                        onClick = { sortMenuOpen = true },
                        enabled = items.isNotEmpty(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort photos")
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        SortBy.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                leadingIcon = if (option == sortBy) {
                                    {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                        )
                                    }
                                } else null,
                                onClick = {
                                    viewModel.setSortBy(option)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                    IconButton(
                        onClick = { rescanMenuOpen = true },
                        enabled = !rescanning,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan past photos")
                    }
                    DropdownMenu(
                        expanded = rescanMenuOpen,
                        onDismissRequest = { rescanMenuOpen = false },
                    ) {
                        Text(
                            "Rescan past photos",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                        listOf(
                            "Last 30 days" to 30,
                            "Last 90 days" to 90,
                            "Last year" to 365,
                            "All time" to PhotoRescanner.DAYS_BACK_ALL,
                        ).forEach { (label, days) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    viewModel.rescan(days)
                                    rescanMenuOpen = false
                                },
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
        ) {
            // The list dominates the screen now. Reference content (Rescan windows, "What gets
            // stripped", FAQ) lives in Settings. The Rescan icon in the topbar opens a dropdown
            // for the time-window selection.
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }
                if (rescanning) item { RescanProgressRow() }
                if (items.isEmpty()) {
                    item { EmptyHint() }
                } else {
                    items(items, key = { it.imageId }) { item ->
                        PendingRow(
                            item = item,
                            onClick = { preview = item },
                            onShowLocation = { preview = item },
                            onStrip = { viewModel.requestStripOne(item.imageId) },
                            onSkip = { viewModel.skipOne(item.imageId) },
                        )
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            if (items.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { skipAllConfirm = true },
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

    if (skipAllConfirm) {
        AlertDialog(
            onDismissRequest = { skipAllConfirm = false },
            title = { Text("Skip all ${items.size} photos?") },
            text = {
                Text(
                    "They will be removed from the queue. You can re-find them with Rescan if " +
                        "they are still on the device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.skipAll()
                    skipAllConfirm = false
                }) { Text("Skip all") }
            },
            dismissButton = {
                TextButton(onClick = { skipAllConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    preview?.let { item ->
        PhotoDetailDialog(
            item = item,
            zones = zones,
            onDismiss = { preview = null },
            onStrip = {
                viewModel.requestStripOne(item.imageId)
                preview = null
            },
            onSkip = {
                viewModel.skipOne(item.imageId)
                preview = null
            },
        )
    }
}

@Composable
private fun RescanProgressRow() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(
            "Rescanning past photos…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PendingRow(
    item: PendingStrip,
    onClick: () -> Unit,
    onShowLocation: () -> Unit,
    onStrip: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumbnail(item = item, sizeDp = 56.dp)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.displayName ?: "Image ${item.imageId}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    val fmt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    val zone = item.zoneName?.let { " · $it" } ?: ""
                    val primary = if (item.dateTakenMs > 0L) {
                        "Taken ${fmt.format(Date(item.dateTakenMs))}$zone"
                    } else {
                        "Detected ${fmt.format(Date(item.detectedAt))}$zone"
                    }
                    Text(
                        primary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Tap to preview",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onShowLocation) { Text("Location") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onSkip) { Text("Skip") }
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = onStrip) { Text("Strip GPS") }
            }
        }
    }
}

@Composable
private fun EmptyHint() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "No photos waiting",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "New photos taken inside a zone will appear here. To re-find skipped or older " +
                    "photos still on the device, use Rescan above.",
                style = MaterialTheme.typography.bodySmall,
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
