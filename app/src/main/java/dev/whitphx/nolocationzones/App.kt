package dev.whitphx.nolocationzones

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.content.getSystemService
import dev.whitphx.nolocationzones.di.AppContainer

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
                CHANNEL_RESULTS,
                getString(R.string.results_channel_name),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = getString(R.string.results_channel_description)
            }
        )
    }

    companion object {
        const val CHANNEL_PHOTO_MONITOR = "photo_monitor"
        const val CHANNEL_RESULTS = "scrub_results"
    }
}
