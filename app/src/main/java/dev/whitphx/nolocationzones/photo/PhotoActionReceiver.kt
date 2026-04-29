package dev.whitphx.nolocationzones.photo

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dev.whitphx.nolocationzones.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles per-photo notification actions that don't need to open the app.
 *
 * Today only [ACTION_SKIP] uses this path: tapping "Skip" on a per-photo notification removes
 * the photo from the pending queue and dismisses its notification, all without launching the
 * Activity. (Strip GPS and Show location need an Activity context, so they go via
 * [dev.whitphx.nolocationzones.MainActivity] deep-link instead.)
 */
class PhotoActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SKIP) return
        val imageId = intent.getLongExtra(EXTRA_IMAGE_ID, -1L)
        if (imageId < 0) return

        val app = context.applicationContext as App
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                app.container.pendingStripRepository.remove(listOf(imageId))
                NotificationManagerCompat.from(context)
                    .cancel(notificationIdFor(imageId))
                Log.i(TAG, "Skipped image $imageId via notification action")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SKIP = "dev.whitphx.nolocationzones.action.SKIP"
        const val EXTRA_IMAGE_ID = "imageId"

        /**
         * Photo notifications need stable, unique-per-photo IDs that don't collide with the
         * service's own ongoing notification (`0xC4FE`) or any other notification ID we use.
         * MediaStore image IDs comfortably fit in 28 bits; we mask to that range and OR with
         * `0x10000000` so all per-photo IDs sit in `[0x10000000, 0x1FFFFFFF]` — comfortably
         * disjoint from our fixed IDs.
         */
        fun notificationIdFor(imageId: Long): Int =
            ((imageId and 0x0FFFFFFFL).toInt()) or 0x10000000

        private const val TAG = "PhotoActionReceiver"
    }
}
