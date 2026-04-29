package dev.whitphx.nolocationzones.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.whitphx.nolocationzones.App

private sealed interface Screen {
    data object Home : Screen
    data object AddZone : Screen
    data object Review : Screen
}

/** External signal: route to the Review screen on launch (e.g. notification tap). */
class NavSignal {
    var openReviewOnce: Boolean = false
}

@Composable
fun AppNavHost(viewModel: MainViewModel, signal: NavSignal) {
    var screen: Screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf(Screen.Home) }

    LaunchedEffect(signal.openReviewOnce) {
        if (signal.openReviewOnce) {
            signal.openReviewOnce = false
            screen = Screen.Review
        }
    }

    when (screen) {
        Screen.Home -> ZoneListScreen(
            viewModel = viewModel,
            onAddZone = { screen = Screen.AddZone },
            onReview = { screen = Screen.Review },
        )
        Screen.AddZone -> AddZoneScreen(
            viewModel = viewModel,
            onClose = {
                viewModel.resetAddZone()
                screen = Screen.Home
            },
        )
        Screen.Review -> {
            val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as App
            val reviewVm: ReviewViewModel = viewModel(factory = ReviewViewModel.factory(app))
            ReviewScreen(
                viewModel = reviewVm,
                onClose = { screen = Screen.Home },
            )
        }
    }
}

private val ScreenSaver = Saver<Screen, String>(
    save = {
        when (it) {
            Screen.Home -> "home"
            Screen.AddZone -> "add"
            Screen.Review -> "review"
        }
    },
    restore = {
        when (it) {
            "add" -> Screen.AddZone
            "review" -> Screen.Review
            else -> Screen.Home
        }
    },
)
