package dev.whitphx.nolocationzones.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

private sealed interface Screen {
    data object Home : Screen
    data object AddZone : Screen
}

@Composable
fun AppNavHost(viewModel: MainViewModel) {
    var screen: Screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf(Screen.Home) }

    when (screen) {
        Screen.Home -> ZoneListScreen(
            viewModel = viewModel,
            onAddZone = { screen = Screen.AddZone },
        )
        Screen.AddZone -> AddZoneScreen(
            viewModel = viewModel,
            onClose = {
                viewModel.resetAddZone()
                screen = Screen.Home
            },
        )
    }
}

private val ScreenSaver = androidx.compose.runtime.saveable.Saver<Screen, String>(
    save = {
        when (it) {
            Screen.Home -> "home"
            Screen.AddZone -> "add"
        }
    },
    restore = {
        when (it) {
            "add" -> Screen.AddZone
            else -> Screen.Home
        }
    },
)
