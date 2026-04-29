package dev.whitphx.nolocationzones

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import dev.whitphx.nolocationzones.theme.NoLocationZonesTheme
import dev.whitphx.nolocationzones.ui.AppNavHost
import dev.whitphx.nolocationzones.ui.MainViewModel
import dev.whitphx.nolocationzones.ui.NavSignal
import dev.whitphx.nolocationzones.ui.PendingAction

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.factory(application as App) }
    private val navSignal = NavSignal()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        applyIntent(intent)
        setContent {
            NoLocationZonesTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppNavHost(viewModel, navSignal)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyIntent(intent)
    }

    private fun applyIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_REVIEW -> navSignal.openReviewOnce = true
            ACTION_STRIP_PHOTO -> {
                navSignal.openReviewOnce = true
                val id = intent.getLongExtra(EXTRA_IMAGE_ID, -1L)
                if (id >= 0) navSignal.pendingAction = PendingAction.StripPhoto(id)
            }
            ACTION_SHOW_LOCATION -> {
                navSignal.openReviewOnce = true
                val id = intent.getLongExtra(EXTRA_IMAGE_ID, -1L)
                if (id >= 0) navSignal.pendingAction = PendingAction.ShowLocation(id)
            }
        }
    }

    companion object {
        const val ACTION_OPEN_REVIEW = "dev.whitphx.nolocationzones.OPEN_REVIEW"
        const val ACTION_STRIP_PHOTO = "dev.whitphx.nolocationzones.STRIP_PHOTO"
        const val ACTION_SHOW_LOCATION = "dev.whitphx.nolocationzones.SHOW_LOCATION"
        const val EXTRA_IMAGE_ID = "imageId"
    }
}
