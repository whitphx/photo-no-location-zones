package dev.whitphx.nolocationzones.ui

import android.content.ContentUris
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whitphx.nolocationzones.domain.PendingStrip
import dev.whitphx.nolocationzones.domain.Zone
import dev.whitphx.nolocationzones.photo.PhotoRescanner
import kotlinx.coroutines.launch

/**
 * Single primary screen the user sees on launch:
 *
 *  - **Header**: status indicator (which zones the user is currently inside) + Settings.
 *  - **Main area**: pending photo list when permissions are granted, otherwise the
 *    [PermissionsCard] in its place. An empty hint when permissions are granted but no photos
 *    are queued.
 *  - **Footer**: condensed zone list, with `+` in its own header to add a zone. (Replaces the
 *    old global FAB — adding zones is occasional setup, not a daily action.)
 *
 * This screen merges what used to be split across `ZoneListScreen` (Home) and `ReviewScreen`
 * (a separate top-level destination) into one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    mainViewModel: MainViewModel,
    reviewViewModel: ReviewViewModel,
    pendingAction: PendingAction? = null,
    onActionConsumed: () -> Unit = {},
    onAddZone: () -> Unit,
    onEditZone: (Long) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val zoneItems by mainViewModel.zoneList.collectAsStateWithLifecycle()
    val items by reviewViewModel.pending.collectAsStateWithLifecycle()
    val event by reviewViewModel.events.collectAsState()
    val rescanning by reviewViewModel.rescanning.collectAsStateWithLifecycle()
    val sortBy by reviewViewModel.sortBy.collectAsStateWithLifecycle()
    val mapZones by reviewViewModel.zones.collectAsStateWithLifecycle()
    val permissions by rememberPermissionState()
    val snackbar = remember { SnackbarHostState() }

    var pendingTargetIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var preview: PendingStrip? by remember { mutableStateOf(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var rescanMenuOpen by remember { mutableStateOf(false) }
    var selectedIds: Set<Long> by remember { mutableStateOf(emptySet()) }

    // Prune selection when items disappear (strip success / skip / external delete).
    LaunchedEffect(items) {
        val present = items.mapTo(HashSet(items.size)) { it.imageId }
        val pruned = selectedIds.filter { it in present }.toSet()
        if (pruned.size != selectedIds.size) selectedIds = pruned
    }

    val writeAccessLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            reviewViewModel.onWriteGranted(pendingTargetIds)
        } else {
            reviewViewModel.onWriteDenied()
        }
        pendingTargetIds = emptyList()
    }

    LaunchedEffect(event) {
        when (val e = event) {
            is ReviewEvent.RequestWriteAccess -> {
                pendingTargetIds = e.targetIds
                writeAccessLauncher.launch(IntentSenderRequest.Builder(e.intent.intentSender).build())
                reviewViewModel.consumeEvent()
            }
            is ReviewEvent.StripCompleted -> {
                val parts = buildList {
                    if (e.stripped > 0) add("${e.stripped} stripped")
                    if (e.skipped > 0) add("${e.skipped} already clean")
                    if (e.failed > 0) add("${e.failed} failed")
                }
                snackbar.showSnackbar(parts.joinToString(", ").ifEmpty { "No changes" })
                reviewViewModel.consumeEvent()
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
                reviewViewModel.consumeEvent()
            }
            is ReviewEvent.Error -> {
                snackbar.showSnackbar(e.message)
                reviewViewModel.consumeEvent()
            }
            null -> Unit
        }
    }

    // Notification deep-links: react to pendingAction once. ShowLocation builds a synthetic
    // PendingStrip if the queue entry is gone (already skipped/stripped) so the dialog still
    // opens against the underlying file.
    LaunchedEffect(pendingAction, items) {
        when (val action = pendingAction) {
            is PendingAction.StripPhoto -> {
                reviewViewModel.requestStripOne(action.imageId)
                onActionConsumed()
            }
            is PendingAction.ShowLocation -> {
                val existing = items.firstOrNull { it.imageId == action.imageId }
                preview = existing ?: PendingStrip(
                    imageId = action.imageId,
                    contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
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

    val sheetState = rememberBottomSheetScaffoldState()
    val coroutineScope = rememberCoroutineScope()
    val expandSheet: () -> Unit = {
        coroutineScope.launch { sheetState.bottomSheetState.expand() }
    }

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = SHEET_PEEK_HEIGHT,
        sheetContent = {
            ZoneSheet(
                zones = zoneItems,
                onAddZone = onAddZone,
                onEditZone = onEditZone,
            )
        },
        topBar = {
            TopAppBar(
                title = {
                    // Whole title block is tappable to expand the zones sheet.
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable(onClick = expandSheet)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusIndicator(
                                permissionsAllGranted = permissions.allGranted,
                                activeZoneCount = zoneItems.count { it.isActive },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = computeStatusTitle(zoneItems, permissions.allGranted),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (permissions.allGranted) WatchingIndicator()
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(onClick = { sortMenuOpen = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = "Sort photos",
                            )
                        }
                        DropdownMenu(
                            expanded = sortMenuOpen,
                            onDismissRequest = { sortMenuOpen = false },
                        ) {
                            SortBy.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label) },
                                    leadingIcon = if (option == sortBy) {
                                        { Icon(Icons.Filled.Check, contentDescription = null) }
                                    } else null,
                                    onClick = {
                                        reviewViewModel.setSortBy(option)
                                        sortMenuOpen = false
                                    },
                                )
                            }
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
                                    reviewViewModel.rescan(days)
                                    rescanMenuOpen = false
                                },
                            )
                        }
                    }

                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // [padding] already includes the sheet peek as bottom inset, so the main content fills
        // exactly the area above the peek without any explicit bottom adjustment.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when {
                    !permissions.allGranted -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp),
                        ) {
                            Spacer(Modifier.height(16.dp))
                            PermissionsCard(onAllGranted = { mainViewModel.resyncGeofences() })
                        }
                    }
                    items.isEmpty() -> EmptyPhotoListHint(
                        rescanning = rescanning,
                        onRescan = { reviewViewModel.rescan(PhotoRescanner.DEFAULT_DAYS_BACK) },
                    )
                    else -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp),
                    ) {
                        // Selection-mode header — pinned, does not scroll. Hidden otherwise.
                        if (selectedIds.isNotEmpty()) {
                            PhotoListHeader(
                                selectedCount = selectedIds.size,
                                totalCount = items.size,
                                onToggleSelectAll = { selectAll ->
                                    selectedIds = if (selectAll) {
                                        items.mapTo(HashSet(items.size)) { it.imageId }
                                    } else {
                                        emptySet()
                                    }
                                },
                                onSkipSelected = {
                                    reviewViewModel.skipFor(selectedIds.toList())
                                    selectedIds = emptySet()
                                },
                                onStripSelected = {
                                    reviewViewModel.requestStripFor(selectedIds.toList())
                                },
                            )
                        }
                        if (rescanning) RescanProgressRow()
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item { Spacer(Modifier.height(4.dp)) }
                            items(items, key = { it.imageId }) { item ->
                                PendingRow(
                                    item = item,
                                    selectionMode = selectedIds.isNotEmpty(),
                                    selected = item.imageId in selectedIds,
                                    onSelectedChange = { checked ->
                                        selectedIds = if (checked) {
                                            selectedIds + item.imageId
                                        } else {
                                            selectedIds - item.imageId
                                        }
                                    },
                                    onClick = { preview = item },
                                    onStrip = { reviewViewModel.requestStripOne(item.imageId) },
                                    onSkip = { reviewViewModel.skipOne(item.imageId) },
                                )
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }

        }
    }


    preview?.let { item ->
        PhotoDetailDialog(
            item = item,
            zones = mapZones,
            onDismiss = { preview = null },
            onStrip = {
                reviewViewModel.requestStripOne(item.imageId)
                preview = null
            },
            onSkip = {
                reviewViewModel.skipOne(item.imageId)
                preview = null
            },
        )
    }
}

private fun computeStatusTitle(
    zones: List<ZoneListItem>,
    permissionsAllGranted: Boolean,
): String {
    if (!permissionsAllGranted) return "Setup needed"
    val active = zones.filter { it.isActive }.map { it.zone.name }
    return when (active.size) {
        0 -> "No active zones"
        1 -> "Inside ${active[0]}"
        2 -> "Inside ${active[0]} and ${active[1]}"
        else -> "Inside ${active[0]}, ${active[1]} +${active.size - 2}"
    }
}

@Composable
private fun EmptyPhotoListHint(
    rescanning: Boolean,
    onRescan: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No photos waiting",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "New photos taken inside a zone will appear here. You can also scan older photos already on the device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onRescan,
                enabled = !rescanning,
            ) {
                if (rescanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Rescanning…")
                } else {
                    Text("Rescan past photos")
                }
            }
        }
    }
}

/**
 * Selection-mode header pinned above the photo list. Tri-state global checkbox plus the count
 * on the left, bulk actions ("Skip selected" / "Strip GPS selected") on the right. Only
 * rendered when there is at least one selected item — caller gates this composable.
 */
@Composable
private fun PhotoListHeader(
    selectedCount: Int,
    totalCount: Int,
    onToggleSelectAll: (Boolean) -> Unit,
    onSkipSelected: () -> Unit,
    onStripSelected: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val state = when {
            selectedCount == 0 -> ToggleableState.Off
            selectedCount >= totalCount -> ToggleableState.On
            else -> ToggleableState.Indeterminate
        }
        TriStateCheckbox(
            state = state,
            onClick = { onToggleSelectAll(state != ToggleableState.On) },
        )
        Text(
            "$selectedCount",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = onSkipSelected,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("Skip selected") }
        TextButton(
            onClick = onStripSelected,
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) { Text("Strip GPS selected") }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PendingRow(
    item: PendingStrip,
    selectionMode: Boolean,
    selected: Boolean,
    onSelectedChange: (Boolean) -> Unit,
    onClick: () -> Unit,
    onStrip: () -> Unit,
    onSkip: () -> Unit,
) {
    // Tap behaviour follows the Gmail / Files pattern:
    //  - Out of selection mode: tap → preview, long-press → enter selection mode (selects this row).
    //  - In selection mode:    tap → toggle this row, long-press → also toggles (cheap shortcut).
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    if (selectionMode) onSelectedChange(!selected) else onClick()
                },
                onLongClick = { onSelectedChange(!selected) },
            ),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Checkbox is hidden until selection mode kicks in, then it appears on every row.
            if (selectionMode) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = onSelectedChange,
                )
            } else {
                Spacer(Modifier.width(12.dp))
            }
            Thumbnail(item = item, sizeDp = 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                Text(
                    item.displayName ?: "Image ${item.imageId}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val zone = item.zoneName?.let { " · $it" } ?: ""
                val timestamp = if (item.dateTakenMs > 0L) item.dateTakenMs else item.detectedAt
                Text(
                    text = "${formatTimestamp(timestamp)}$zone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(
                onClick = onSkip,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Skip") }
            TextButton(
                onClick = onStrip,
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) { Text("Strip GPS") }
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

/** Peek height used by [BottomSheetScaffold] in [HomeScreen]. Sized so just the drag handle
 *  and the "Zones" header (with its count badge + add button) are visible. The badge tells
 *  the user how many zones exist; the user drags up to see the rows. */
private val SHEET_PEEK_HEIGHT = 110.dp

/**
 * Bottom-sheet content. The Material 3 [BottomSheetScaffold] renders its own drag handle above
 * this composable, so we just supply the header + the zone list.
 */
@Composable
private fun ZoneSheet(
    zones: List<ZoneListItem>,
    onAddZone: () -> Unit,
    onEditZone: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Zones",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (zones.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                ZoneCountBadge(count = zones.size)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onAddZone) {
                Icon(Icons.Filled.Add, contentDescription = "Add zone")
            }
        }
        if (zones.isEmpty()) {
            Text(
                "No zones yet — tap + to add one. Photos taken inside a zone will be queued for review.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(16.dp))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(zones, key = { it.zone.id }) { item ->
                    ZoneFooterRow(
                        item = item,
                        onClick = { onEditZone(item.zone.id) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/**
 * Pill-shaped count badge showing how many zones exist in total. Lives next to the "Zones"
 * title in the bottom-sheet header so the user can tell at a glance whether the peek-visible
 * row is one of many — without having to drag the sheet up to find out.
 */
@Composable
private fun ZoneCountBadge(count: Int) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/**
 * Small "the app is actively watching for new photos" indicator. A subtitle on line 2 of the
 * topbar title. Rendered only when permissions are all granted, since the foreground service
 * can't actually run otherwise.
 */
@Composable
private fun WatchingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 22.dp),
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color(0xFF2E7D32)),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "Watching",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Small status indicator shown to the left of the topbar title.
 *  - Inside any zone → green filled dot (active monitoring)
 *  - Outside all zones (perms OK) → muted filled dot (idle but ready)
 *  - Permissions incomplete → orange warning triangle (action needed)
 */
@Composable
private fun StatusIndicator(permissionsAllGranted: Boolean, activeZoneCount: Int) {
    val (icon, color) = when {
        !permissionsAllGranted -> Icons.Filled.Warning to Color(0xFFEF6C00) // orange
        activeZoneCount > 0 -> Icons.Filled.Circle to Color(0xFF2E7D32)     // green
        else -> Icons.Filled.Circle to MaterialTheme.colorScheme.outline    // muted
    }
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(14.dp),
    )
}

@Composable
private fun ZoneFooterRow(item: ZoneListItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = if (item.isActive) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            item.zone.name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        ActiveBadge(active = item.isActive)
    }
}

@Composable
private fun ActiveBadge(active: Boolean) {
    val (color, label) = if (active) {
        Color(0xFF2E7D32) to "Inside"
    } else {
        MaterialTheme.colorScheme.outline to "Outside"
    }
    Surface(color = color, shape = CircleShape) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
