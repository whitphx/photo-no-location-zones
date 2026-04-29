package dev.whitphx.nolocationzones.photo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.whitphx.nolocationzones.App
import dev.whitphx.nolocationzones.MainActivity
import dev.whitphx.nolocationzones.R
import dev.whitphx.nolocationzones.data.PendingStripRepository
import dev.whitphx.nolocationzones.data.ZoneRepository
import dev.whitphx.nolocationzones.data.ZoneStateStore
import dev.whitphx.nolocationzones.domain.PendingStrip
// PendingStripReconciler is in the same package so no explicit import needed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * While the user is inside any zone, watches MediaStore for new photos. For each new photo that
 * still has GPS metadata, inserts a row into the pending-strip queue (Room) and posts a
 * per-photo notification with a thumbnail preview and three quick actions: Strip GPS, Show
 * location, Skip.
 *
 * The service does NOT modify photos — modification requires user consent via
 * [MediaStore.createWriteRequest], which can only be invoked from an Activity. The service's only
 * job is detection + queueing + notifying.
 *
 * Service type: `location` — the work is gated by location and bounded to a defined geographic
 * area, which matches the type's spirit and is the only background-startable type that aligns
 * with the permissions this app already has.
 */
class PhotoMonitorService : LifecycleService() {

    private lateinit var stateStore: ZoneStateStore
    private lateinit var zoneRepo: ZoneRepository
    private lateinit var pendingRepo: PendingStripRepository
    private lateinit var reconciler: PendingStripReconciler

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
        pendingRepo = app.container.pendingStripRepository
        reconciler = app.container.pendingStripReconciler
    }

    @OptIn(FlowPreview::class)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForegroundCompat(buildOngoingNotification(activeZoneCount = 1, zoneName = null, pendingCount = 0))

        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            /* notifyForDescendants */ true,
            observer,
        )

        // Refresh the foreground (ongoing) notification whenever the active-zone set or pending
        // count changes. Stop the service on zone-exit regardless of pending count — per-photo
        // notifications persist independently and ContentObserver has nothing more to detect once
        // we leave every zone.
        lifecycleScope.launch {
            stateStore.activeZoneIds.collectLatest { active ->
                if (active.isEmpty()) {
                    Log.i(TAG, "No active zones — stopping service")
                    stopSelf()
                    return@collectLatest
                }
                val name = active.firstOrNull()?.let {
                    runCatching { zoneRepo.get(it)?.name }.getOrNull()
                }
                val pending = runCatching { pendingRepo.getAll().size }.getOrDefault(0)
                notifyIfPermitted(
                    NOTIFICATION_ID_ONGOING,
                    buildOngoingNotification(active.size, name, pending),
                )
            }
        }

        // MediaStore -> debounce -> reconcile (drop deleted) + scan-and-queue (pick up new)
        lifecycleScope.launch {
            mediaChanges.debounce(MEDIA_DEBOUNCE_MS).collect {
                runCatching { reconciler.reconcile() }
                    .onFailure { Log.w(TAG, "Reconcile failed", it) }
                if (stateStore.isAnyZoneActive()) scanAndQueue()
            }
        }

        // Initial pass: reconcile against any deletions that happened while we weren't running,
        // then scan for new photos.
        lifecycleScope.launch {
            runCatching { reconciler.reconcile() }
            if (stateStore.isAnyZoneActive()) scanAndQueue()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(observer)
        super.onDestroy()
    }

    private suspend fun scanAndQueue() = withContext(Dispatchers.IO) {
        val cutoff = stateStore.getLastSeenImageId()
        val activeId = stateStore.firstActiveZoneId()
        val zoneName = activeId?.let { runCatching { zoneRepo.get(it)?.name }.getOrNull() }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
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

        val now = System.currentTimeMillis()
        var maxSeen = cutoff
        val newlyQueued = mutableListOf<PendingStrip>()
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                if (id > maxSeen) maxSeen = id
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                if (!ExifGpsStripper.hasGpsTags(contentResolver, uri)) {
                    Log.d(TAG, "Image $id has no GPS — skipping queue")
                    continue
                }
                val item = PendingStrip(
                    imageId = id,
                    contentUri = uri,
                    displayName = c.getString(nameCol),
                    detectedAt = now,
                    zoneName = zoneName,
                    dateTakenMs = if (c.isNull(takenCol)) 0L else c.getLong(takenCol),
                )
                pendingRepo.add(item)
                newlyQueued += item
                Log.i(TAG, "Queued image $id for user review")
            }
        }
        if (maxSeen > cutoff) stateStore.setLastSeenImageId(maxSeen)

        for (item in newlyQueued) postPhotoNotification(item)
    }

    private suspend fun postPhotoNotification(item: PendingStrip) {
        if (!canPostNotifications()) return
        val thumb: Bitmap? = withContext(Dispatchers.IO) {
            runCatching {
                contentResolver.loadThumbnail(item.contentUri, Size(THUMB_PX, THUMB_PX), null)
            }.getOrNull()
        }
        val n = buildPhotoNotification(item, thumb)
        notifyIfPermitted(PhotoActionReceiver.notificationIdFor(item.imageId), n)
    }

    private fun buildPhotoNotification(item: PendingStrip, thumbnail: Bitmap?): Notification {
        val tapPi = activityPendingIntent(MainActivity.ACTION_OPEN_REVIEW, item.imageId)
        val stripPi = activityPendingIntent(MainActivity.ACTION_STRIP_PHOTO, item.imageId)
        val locationPi = activityPendingIntent(MainActivity.ACTION_SHOW_LOCATION, item.imageId)
        val skipPi = skipPendingIntent(item.imageId)

        val title = item.displayName ?: getString(R.string.photo_review_default_title)
        val text = item.zoneName?.let {
            getString(R.string.photo_review_text_with_zone, it)
        } ?: getString(R.string.photo_review_text_no_zone)

        val builder = NotificationCompat.Builder(this, App.CHANNEL_REVIEW)
            .setSmallIcon(R.drawable.ic_zone_pin)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tapPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(NOTIFICATION_GROUP_REVIEW)
            .addAction(R.drawable.ic_zone_pin, getString(R.string.photo_review_action_strip), stripPi)
            .addAction(R.drawable.ic_zone_pin, getString(R.string.photo_review_action_location), locationPi)
            .addAction(R.drawable.ic_zone_pin, getString(R.string.photo_review_action_skip), skipPi)

        if (thumbnail != null) {
            builder
                .setLargeIcon(thumbnail)
                .setStyle(
                    NotificationCompat.BigPictureStyle()
                        .bigPicture(thumbnail)
                        .bigLargeIcon(null as Bitmap?), // hide largeIcon when expanded so big picture stands alone
                )
        }
        return builder.build()
    }

    private fun activityPendingIntent(action: String, imageId: Long): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.action = action
            putExtra(MainActivity.EXTRA_IMAGE_ID, imageId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            requestCodeFor(imageId, action),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun skipPendingIntent(imageId: Long): PendingIntent {
        val intent = Intent(this, PhotoActionReceiver::class.java).apply {
            action = PhotoActionReceiver.ACTION_SKIP
            putExtra(PhotoActionReceiver.EXTRA_IMAGE_ID, imageId)
        }
        return PendingIntent.getBroadcast(
            this,
            requestCodeFor(imageId, PhotoActionReceiver.ACTION_SKIP),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Build a stable request code that distinguishes (imageId, action) pairs.
     * PendingIntent identity is decided by component + filterEquals(intent) + requestCode;
     * different actions already differ in filterEquals, but encoding action into requestCode is
     * a defence-in-depth against any flag mismatch leaking pending intents across actions.
     */
    private fun requestCodeFor(imageId: Long, action: String): Int =
        ((imageId and 0x0FFFFFFFL).toInt() xor action.hashCode())

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_ONGOING,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID_ONGOING, notification)
        }
    }

    private fun buildOngoingNotification(
        activeZoneCount: Int,
        zoneName: String?,
        pendingCount: Int,
    ): Notification {
        val text = when {
            pendingCount > 0 ->
                resources.getQuantityString(R.plurals.ongoing_with_pending, pendingCount, pendingCount)
            activeZoneCount > 1 || zoneName == null ->
                resources.getQuantityString(
                    R.plurals.photo_monitor_notification_text_multi,
                    activeZoneCount.coerceAtLeast(1),
                    activeZoneCount.coerceAtLeast(1),
                )
            else -> getString(R.string.photo_monitor_notification_text, zoneName)
        }
        val tapPi = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_REVIEW
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, App.CHANNEL_PHOTO_MONITOR)
            .setSmallIcon(R.drawable.ic_zone_pin)
            .setContentTitle(getString(R.string.photo_monitor_notification_title))
            .setContentText(text)
            .setContentIntent(tapPi)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    @SuppressLint("MissingPermission") // gated by canPostNotifications()
    private fun notifyIfPermitted(id: Int, notification: Notification) {
        if (canPostNotifications()) {
            NotificationManagerCompat.from(this).notify(id, notification)
        }
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "PhotoMonitorService"
        private const val NOTIFICATION_ID_ONGOING = 0xC4FE
        private const val NOTIFICATION_GROUP_REVIEW = "review_group"
        private const val MEDIA_DEBOUNCE_MS = 400L
        private const val THUMB_PX = 1024 // big enough to look good in BigPictureStyle on tall screens

        fun start(context: Context) {
            val i = Intent(context, PhotoMonitorService::class.java)
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PhotoMonitorService::class.java))
        }
    }
}
