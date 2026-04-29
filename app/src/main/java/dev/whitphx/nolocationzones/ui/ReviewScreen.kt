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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Button
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
fun ReviewScreen(viewModel: ReviewViewModel, onClose: () -> Unit) {
    val items by viewModel.pending.collectAsStateWithLifecycle()
    val event by viewModel.events.collectAsState()
    val rescanning by viewModel.rescanning.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var pendingTargetIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var preview: PendingStrip? by remember { mutableStateOf(null) }
    var locationPreview: PendingStrip? by remember { mutableStateOf(null) }
    var skipAllConfirm by remember { mutableStateOf(false) }
    var sortMenuOpen by remember { mutableStateOf(false) }
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
                        onClick = { viewModel.rescan(PhotoRescanner.DEFAULT_DAYS_BACK) },
                        enabled = !rescanning,
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Rescan last 30 days")
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
            RescanCard(rescanning = rescanning, onRescan = { viewModel.rescan(it) })
            Spacer(Modifier.height(8.dp))
            ScrubScopeCard()
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
                            onShowLocation = { locationPreview = item },
                            onStrip = { viewModel.requestStripOne(item.imageId) },
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
        PhotoPreviewDialog(
            uri = item.contentUri,
            displayName = item.displayName,
            onDismiss = { preview = null },
        )
    }

    locationPreview?.let { item ->
        LocationPreviewDialog(
            uri = item.contentUri,
            displayName = item.displayName,
            zones = zones,
            onDismiss = { locationPreview = null },
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
private fun EmptyHint(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No photos waiting", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "New photos taken inside a zone will appear here. To re-find skipped or older " +
                    "photos still on the device, use Rescan above.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RescanCard(rescanning: Boolean, onRescan: (Int) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Rescan past photos",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Re-queues any photo whose EXIF GPS places it inside one of your zones. " +
                            "Pick a window — older photos take longer.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (rescanning) {
                    Spacer(Modifier.width(12.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RescanChip(label = "30d", days = 30, enabled = !rescanning, onRescan = onRescan)
                RescanChip(label = "90d", days = 90, enabled = !rescanning, onRescan = onRescan)
                RescanChip(label = "1y", days = 365, enabled = !rescanning, onRescan = onRescan)
                RescanChip(
                    label = "All",
                    days = PhotoRescanner.DAYS_BACK_ALL,
                    enabled = !rescanning,
                    onRescan = onRescan,
                )
            }
        }
    }
}

@Composable
private fun RescanChip(label: String, days: Int, enabled: Boolean, onRescan: (Int) -> Unit) {
    AssistChip(
        onClick = { onRescan(days) },
        label = { Text(label) },
        enabled = enabled,
        colors = AssistChipDefaults.assistChipColors(),
    )
}

/**
 * User-facing summary of what the strip operation actually does and what it doesn't. Collapsed by
 * default; expanding reveals the limits a privacy-minded user would want to know about (XMP,
 * Motion Photo MP4 trailer, IPTC, sensor PRNU). Mirrors the README "Privacy gaps" section.
 */
@Composable
private fun ScrubScopeCard() {
    var expanded by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "What gets stripped (and what doesn't)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cleared:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Bullet("All EXIF GPS fields (latitude, longitude, altitude, timestamp, bearing, processing method, etc. — 32 tags total).")
                Bullet("Camera MakerNote — vendor-specific blob that frequently embeds a duplicate of the GPS coordinates plus Wi-Fi SSIDs and per-photo asset IDs.")
                Bullet("Identifying tags: Artist, Camera Owner Name, Body/Lens Serial Number, User Comment, Image Description.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "NOT cleared (known gaps):",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Bullet("Adobe XMP packet — a separate XML metadata block in JPEG/HEIF that can carry city/country/creator names. Stripping it requires rewriting the container format directly; not yet implemented.")
                Bullet("Motion Photo / Live Photo embedded MP4 video — the video portion has its own GPS metadata that this scrubber does not touch.")
                Bullet("IPTC — a third metadata block written by some photo editors, also untouched.")
                Bullet("Sensor PRNU — every camera's unique noise pattern. Forensic tools can match a photo to a specific physical camera from the pixels alone, regardless of metadata.")
                Spacer(Modifier.height(4.dp))
                Text(
                    "See README \"Privacy gaps\" for the full discussion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodySmall)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
