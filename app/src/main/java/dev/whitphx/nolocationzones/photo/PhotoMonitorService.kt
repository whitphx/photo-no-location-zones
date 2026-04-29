package dev.whitphx.nolocationzones.photo

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.whitphx.nolocationzones.App
import dev.whitphx.nolocationzones.MainActivity
import dev.whitphx.nolocationzones.R
import dev.whitphx.nolocationzones.data.ZoneRepository
import dev.whitphx.nolocationzones.data.ZoneStateStore
import dev.whitphx.nolocationzones.domain.Zone
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground service that runs while the user is inside any zone. Watches MediaStore for new
 * photos and strips GPS EXIF tags from each one.
 *
 * Started from a geofence ENTER broadcast (allowed FGS BG-start exemption when the app holds
 * ACCESS_BACKGROUND_LOCATION). Stops itself when ZoneStateStore reports no active zones.
 *
 * Service type: `location`. The work is location-triggered and only runs while inside a defined
 * geographic area, which matches the spirit of the type and is the only background-startable
 * type that lines up with the permissions this app already has.
 */
class PhotoMonitorService : LifecycleService() {

    private lateinit var stateStore: ZoneStateStore
    private lateinit var zoneRepo: ZoneRepository

    private val workQueue = Channel<Long>(capacity = Channel.UNLIMITED)
    private val mediaChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            mediaChanges.tryEmit(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = applicationContext as App
        stateStore = app.container.zoneStateStore
        zoneRepo = app.container.zoneRepository
    }

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat(buildNotification(currentZoneCount = 1, zoneName = null))

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants */ true,
            observer,
        )

        // Stop ourselves the moment the user has left every zone.
        lifecycleScope.launch {
            stateStore.activeZoneIds.collectLatest { active ->
                if (active.isEmpty()) {
                    Log.i(TAG, "No active zones — stopping service")
                    stopSelf()
                } else {
                    val name = active.firstOrNull()?.let { runCatching { zoneRepo.get(it)?.name }.getOrNull() }
                    val n = buildNotification(active.size, name)
                    ContextCompat.getSystemService(this@PhotoMonitorService, android.app.NotificationManager::class.java)
                        ?.notify(NOTIFICATION_ID, n)
                }
            }
        }

        // MediaStore -> debounce -> scan
        lifecycleScope.launch {
            mediaChanges.debounce(MEDIA_DEBOUNCE_MS).collect {
                scanForNewPhotos()
            }
        }

        // Process scrub queue serially so two rapidly-arriving photos can't stomp each other.
        val scrubExceptionHandler = CoroutineExceptionHandler { _, t ->
            Log.e(TAG, "Scrub coroutine crashed", t)
        }
        lifecycleScope.launch(Dispatchers.IO + scrubExceptionHandler) {
            workQueue.consumeAsFlow().filter { it > 0 }.collect { imageId ->
                scrubImageWithRetry(imageId)
            }
        }

        // Initial scan in case photos appeared while the service was being started.
        lifecycleScope.launch { scanForNewPhotos() }

        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        workQueue.close()
        super.onDestroy()
    }

    private suspend fun scanForNewPhotos() = withContext(Dispatchers.IO) {
        val cutoff = stateStore.getLastSeenImageId()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.MIME_TYPE,
        )
        val selection = "${MediaStore.Images.Media._ID} > ? AND " +
            "(${MediaStore.Images.Media.MIME_TYPE} = 'image/jpeg' OR " +
            "${MediaStore.Images.Media.MIME_TYPE} = 'image/heif' OR " +
            "${MediaStore.Images.Media.MIME_TYPE} = 'image/heic')"
        val args = arrayOf(cutoff.toString())
        val sort = "${MediaStore.Images.Media._ID} ASC"

        val cursor = runCatching {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                args,
                sort,
            )
        }.getOrNull() ?: return@withContext

        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            var maxSeen = cutoff
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                if (id > maxSeen) maxSeen = id
                workQueue.trySend(id)
            }
            if (maxSeen > cutoff) stateStore.setLastSeenImageId(maxSeen)
        }
    }

    private suspend fun scrubImageWithRetry(imageId: Long) {
        val backoffMs = longArrayOf(0L, 750L, 2_000L, 5_000L)
        for ((attempt, delayMs) in backoffMs.withIndex()) {
            if (delayMs > 0) delay(delayMs)
            val path = lookupPath(imageId) ?: run {
                Log.w(TAG, "Image $imageId disappeared from MediaStore")
                return
            }
            val file = File(path)
            // The camera may still be writing — if size keeps changing, wait one more round.
            val sizeA = file.length()
            delay(150)
            if (file.length() != sizeA) continue

            when (val r = ExifGpsStripper.strip(file)) {
                is ExifGpsStripper.Result.Stripped -> {
                    Log.i(TAG, "Stripped GPS from $path (cleared ${r.tagsCleared} tags)")
                    refreshMediaStoreEntry(imageId)
                    return
                }
                ExifGpsStripper.Result.NoChange -> {
                    Log.d(TAG, "$path had no GPS tags (idempotent skip)")
                    return
                }
                is ExifGpsStripper.Result.Failed -> {
                    Log.w(TAG, "Strip attempt ${attempt + 1} failed for $path: ${r.cause.message}")
                }
            }
        }
        Log.e(TAG, "Gave up scrubbing image $imageId after ${backoffMs.size} attempts")
    }

    private fun lookupPath(imageId: Long): String? {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media.DATA)
        val selection = "${MediaStore.Images.Media._ID} = ?"
        val args = arrayOf(imageId.toString())
        return contentResolver.query(uri, projection, selection, args, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }

    private fun refreshMediaStoreEntry(imageId: Long) {
        // Tell MediaStore the file changed so its cached EXIF is reread.
        runCatching {
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                .buildUpon().appendPath(imageId.toString()).build()
            contentResolver.notifyChange(uri, null)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(currentZoneCount: Int, zoneName: String?): Notification {
        val text = if (currentZoneCount > 1 || zoneName == null) {
            val n = currentZoneCount.coerceAtLeast(1)
            resources.getQuantityString(R.plurals.photo_monitor_notification_text_multi, n, n)
        } else {
            getString(R.string.photo_monitor_notification_text, zoneName)
        }
        val pi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_PHOTO_MONITOR)
            .setSmallIcon(R.drawable.ic_zone_pin)
            .setContentTitle(getString(R.string.photo_monitor_notification_title))
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    companion object {
        private const val TAG = "PhotoMonitorService"
        private const val NOTIFICATION_ID = 0xC4FE
        private const val MEDIA_DEBOUNCE_MS = 400L

        fun start(context: Context) {
            val i = Intent(context, PhotoMonitorService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhotoMonitorService::class.java))
        }
    }
}
