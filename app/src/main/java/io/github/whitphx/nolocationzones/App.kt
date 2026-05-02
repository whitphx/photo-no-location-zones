package io.github.whitphx.nolocationzones

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import io.github.whitphx.nolocationzones.di.AppContainer

class App : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_PHOTO_MONITOR,
                getString(R.string.photo_monitor_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.photo_monitor_channel_description)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REVIEW,
                getString(R.string.review_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = getString(R.string.review_channel_description)
            }
        )
    }

    companion object {
        const val CHANNEL_PHOTO_MONITOR = "photo_monitor"
        const val CHANNEL_REVIEW = "review_pending"
    }
}
