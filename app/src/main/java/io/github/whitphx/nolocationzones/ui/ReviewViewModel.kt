package io.github.whitphx.nolocationzones.ui

import android.app.Application
import android.app.PendingIntent
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.whitphx.nolocationzones.App
import io.github.whitphx.nolocationzones.data.PendingStripRepository
import io.github.whitphx.nolocationzones.data.ZoneRepository
import io.github.whitphx.nolocationzones.domain.PendingStrip
import io.github.whitphx.nolocationzones.domain.Zone
import io.github.whitphx.nolocationzones.photo.ExifGpsStripper
import io.github.whitphx.nolocationzones.photo.PendingStripReconciler
import io.github.whitphx.nolocationzones.photo.PhotoActionReceiver
import io.github.whitphx.nolocationzones.photo.PhotoRescanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SortBy(val label: String) {
    DateTakenDesc("Date taken — newest first"),
    DateTakenAsc("Date taken — oldest first"),
    DetectedDesc("Recently detected first"),
    DetectedAsc("First detected first"),
    ZoneAsc("Zone name (A → Z)"),
    ;

    fun apply(items: List<PendingStrip>): List<PendingStrip> = when (this) {
        DateTakenDesc -> items.sortedWith(
            compareByDescending<PendingStrip> { it.dateTakenMs }.thenByDescending { it.detectedAt },
        )
        DateTakenAsc -> items.sortedWith(
            compareBy<PendingStrip> { if (it.dateTakenMs == 0L) Long.MAX_VALUE else it.dateTakenMs }
                .thenBy { it.detectedAt },
        )
        DetectedDesc -> items.sortedByDescending { it.detectedAt }
        DetectedAsc -> items.sortedBy { it.detectedAt }
        ZoneAsc -> {
            val byZone: Comparator<PendingStrip> =
                compareBy(nullsLast<String>()) { it.zoneName }
            items.sortedWith(byZone.thenByDescending { it.dateTakenMs })
        }
    }
}

sealed interface ReviewEvent {
    /** Tell the activity to launch this PendingIntent via an IntentSenderRequest. */
    data class RequestWriteAccess(val intent: PendingIntent, val targetIds: List<Long>) : ReviewEvent
    data class StripCompleted(val stripped: Int, val skipped: Int, val failed: Int) : ReviewEvent
    data class RescanCompleted(
        val matched: Int,
        val scanned: Int,
        val noGps: Int,
        val zonesAtScan: Int,
        val daysBack: Int,
    ) : ReviewEvent
    data class Error(val message: String) : ReviewEvent
}

class ReviewViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as App
    private val pendingRepo: PendingStripRepository = app.container.pendingStripRepository
    private val rescanner: PhotoRescanner = app.container.photoRescanner
    private val zoneRepo: ZoneRepository = app.container.zoneRepository
    private val reconciler: PendingStripReconciler = app.container.pendingStripReconciler

    init {
        // Drop entries whose underlying photo was deleted while the user was out of every zone
        // (i.e. while the foreground service wasn't running and couldn't observe MediaStore).
        viewModelScope.launch { runCatching { reconciler.reconcile() } }
    }

    private val _sortBy = MutableStateFlow(SortBy.DateTakenDesc)
    val sortBy: StateFlow<SortBy> = _sortBy.asStateFlow()

    fun setSortBy(value: SortBy) {
        _sortBy.value = value
    }

    val pending: StateFlow<List<PendingStrip>> =
        combine(pendingRepo.all, _sortBy) { items, sort -> sort.apply(items) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val zones: StateFlow<List<Zone>> =
        zoneRepo.zones.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableStateFlow<ReviewEvent?>(null)
    val events: StateFlow<ReviewEvent?> = _events.asStateFlow()

    private val _rescanning = MutableStateFlow(false)
    val rescanning: StateFlow<Boolean> = _rescanning.asStateFlow()

    fun consumeEvent() {
        _events.value = null
    }

    /**
     * Step 1: ask the system for write permission on every URI. We pass the PendingIntent up to
     * the activity so the activity can launch it with
     * [androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult].
     */
    fun requestStripAll() {
        viewModelScope.launch {
            requestStripForItems(pendingRepo.getAll())
        }
    }

    /** Like [requestStripAll] but limited to a specific set of image IDs (e.g. user selection). */
    fun requestStripFor(imageIds: Collection<Long>) {
        if (imageIds.isEmpty()) return
        viewModelScope.launch {
            val ids = imageIds.toSet()
            requestStripForItems(pendingRepo.getAll().filter { it.imageId in ids })
        }
    }

    fun requestStripOne(imageId: Long) = requestStripFor(listOf(imageId))

    private fun requestStripForItems(items: List<PendingStrip>) {
        if (items.isEmpty()) return
        try {
            val intent = MediaStore.createWriteRequest(
                getApplication<Application>().contentResolver,
                items.map { it.contentUri },
            )
            _events.value = ReviewEvent.RequestWriteAccess(intent, items.map { it.imageId })
        } catch (t: Throwable) {
            Log.e(TAG, "createWriteRequest failed", t)
            _events.value = ReviewEvent.Error(t.message ?: "Couldn't request write access")
        }
    }

    /**
     * Step 2: called by the activity after the user accepts the system dialog. Strip all targets,
     * then remove successfully-stripped (and ones the system says are no longer present) from the
     * queue.
     */
    fun onWriteGranted(targetIds: List<Long>) {
        viewModelScope.launch {
            val items = pendingRepo.getAll().filter { it.imageId in targetIds }
            val resolver = getApplication<Application>().contentResolver
            var stripped = 0
            var skipped = 0
            var failed = 0
            val processedIds = mutableListOf<Long>()
            withContext(Dispatchers.IO) {
                for (item in items) {
                    when (val r = ExifGpsStripper.strip(resolver, item.contentUri)) {
                        is ExifGpsStripper.Result.Stripped -> {
                            stripped++
                            processedIds += item.imageId
                        }
                        ExifGpsStripper.Result.NoChange -> {
                            skipped++
                            processedIds += item.imageId
                        }
                        is ExifGpsStripper.Result.Failed -> {
                            failed++
                            Log.w(TAG, "Failed to strip image ${item.imageId}: ${r.cause.message}")
                        }
                    }
                }
            }
            if (processedIds.isNotEmpty()) {
                pendingRepo.remove(processedIds)
                cancelNotificationsFor(processedIds)
            }
            _events.value = ReviewEvent.StripCompleted(stripped, skipped, failed)
        }
    }

    fun onWriteDenied() {
        // User declined. Leave items in the queue.
        _events.value = ReviewEvent.Error("Write access was not granted; nothing was modified.")
    }

    fun skipAll() {
        viewModelScope.launch {
            val ids = pendingRepo.getAll().map { it.imageId }
            pendingRepo.clear()
            cancelNotificationsFor(ids)
        }
    }

    /** Skip a specific set of image IDs (e.g. the user's checked selection). */
    fun skipFor(imageIds: Collection<Long>) {
        if (imageIds.isEmpty()) return
        viewModelScope.launch {
            val list = imageIds.toList()
            pendingRepo.remove(list)
            cancelNotificationsFor(list)
        }
    }

    fun skipOne(imageId: Long) = skipFor(listOf(imageId))

    private fun cancelNotificationsFor(imageIds: Collection<Long>) {
        if (imageIds.isEmpty()) return
        val nm = NotificationManagerCompat.from(getApplication())
        for (id in imageIds) nm.cancel(PhotoActionReceiver.notificationIdFor(id))
    }

    fun rescan(daysBack: Int = PhotoRescanner.DEFAULT_DAYS_BACK) {
        if (_rescanning.value) return
        viewModelScope.launch {
            _rescanning.value = true
            try {
                val r = rescanner.rescanRecent(daysBack)
                _events.value = ReviewEvent.RescanCompleted(
                    matched = r.matched,
                    scanned = r.scanned,
                    noGps = r.noGps,
                    zonesAtScan = r.zonesAtScan,
                    daysBack = r.daysBack,
                )
            } catch (t: Throwable) {
                _events.value = ReviewEvent.Error(t.message ?: "Rescan failed")
            } finally {
                _rescanning.value = false
            }
        }
    }

    companion object {
        private const val TAG = "ReviewViewModel"

        fun factory(app: App): ViewModelProvider.Factory =
            viewModelFactory { initializer { ReviewViewModel(app) } }
    }
}
