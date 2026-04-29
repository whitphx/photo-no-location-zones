package dev.whitphx.nolocationzones.ui

import android.app.Application
import android.app.PendingIntent
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.whitphx.nolocationzones.App
import dev.whitphx.nolocationzones.data.PendingStripRepository
import dev.whitphx.nolocationzones.domain.PendingStrip
import dev.whitphx.nolocationzones.photo.ExifGpsStripper
import dev.whitphx.nolocationzones.photo.PhotoRescanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    val pending: StateFlow<List<PendingStrip>> =
        pendingRepo.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _events = MutableStateFlow<ReviewEvent?>(null)
    val events: StateFlow<ReviewEvent?> = _events.asStateFlow()

    private val _rescanning = MutableStateFlow(false)
    val rescanning: StateFlow<Boolean> = _rescanning.asStateFlow()

    fun consumeEvent() {
        _events.value = null
    }

    /**
     * Step 1: ask the system for write permission on every URI. We pass the PendingIntent up to the
     * activity so the activity can launch it with [androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult].
     */
    fun requestStripAll() {
        viewModelScope.launch {
            val items = pendingRepo.getAll()
            if (items.isEmpty()) return@launch
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
            if (processedIds.isNotEmpty()) pendingRepo.remove(processedIds)
            _events.value = ReviewEvent.StripCompleted(stripped, skipped, failed)
        }
    }

    fun onWriteDenied() {
        // User declined. Leave items in the queue.
        _events.value = ReviewEvent.Error("Write access was not granted; nothing was modified.")
    }

    fun skipAll() {
        viewModelScope.launch {
            pendingRepo.clear()
        }
    }

    fun skipOne(imageId: Long) {
        viewModelScope.launch {
            pendingRepo.remove(listOf(imageId))
        }
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
