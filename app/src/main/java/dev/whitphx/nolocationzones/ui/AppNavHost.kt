package dev.whitphx.nolocationzones.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.whitphx.nolocationzones.App

private sealed interface Screen {
    data object Home : Screen
    data class MapZone(val editId: Long?) : Screen
    data object Review : Screen
}

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

    when (val current = screen) {
        Screen.Home -> ZoneListScreen(
            viewModel = viewModel,
            onAddZone = { screen = Screen.MapZone(editId = null) },
            onEditZone = { id -> screen = Screen.MapZone(editId = id) },
            onReview = { screen = Screen.Review },
        )
        is Screen.MapZone -> {
            val app = LocalContext.current.applicationContext as App
            // Key by editId so the VM is recreated when navigating between different zones.
            val mapVm: MapZoneViewModel = viewModel(
                key = "MapZone:${current.editId ?: "new"}",
                factory = MapZoneViewModel.factory(app, current.editId),
            )
            MapZoneScreen(
                viewModel = mapVm,
                onClose = { screen = Screen.Home },
            )
        }
        Screen.Review -> {
            val app = LocalContext.current.applicationContext as App
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
            is Screen.MapZone -> "map:${it.editId ?: ""}"
            Screen.Review -> "review"
        }
    },
    restore = {
        when {
            it == "review" -> Screen.Review
            it.startsWith("map:") -> Screen.MapZone(it.removePrefix("map:").toLongOrNull())
            else -> Screen.Home
        }
    },
)
