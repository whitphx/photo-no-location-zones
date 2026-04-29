package dev.whitphx.nolocationzones.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.whitphx.nolocationzones.App

private sealed interface Screen {
    data object Home : Screen
    data class MapZone(val editId: Long?) : Screen
    data object Settings : Screen
}

/** A specific action that should fire on the home screen on next composition (e.g. delivered
 *  via a per-photo notification's "Strip GPS" or "Show location" action button). */
sealed interface PendingAction {
    data class StripPhoto(val imageId: Long) : PendingAction
    data class ShowLocation(val imageId: Long) : PendingAction
}

/**
 * One-shot signals passed from MainActivity into Compose. Each field is read-then-cleared by the
 * receiver, so a single intent only triggers one navigation/action.
 *
 * Backed by [mutableStateOf] so writes from `Activity.onNewIntent` are visible to the snapshot
 * system — without this, mutating a plain `var` from outside composition does not invalidate
 * any reader, and the [LaunchedEffect] in [AppNavHost] never re-fires.
 */
class NavSignal {
    /** Notifications that used to point at the (now-merged-in) Review screen now just need to
     *  ensure Home is the active destination — the photo list is there. */
    var openHomeOnce: Boolean by mutableStateOf(false)
    var pendingAction: PendingAction? by mutableStateOf(null)
}

@Composable
fun AppNavHost(viewModel: MainViewModel, signal: NavSignal) {
    var screen: Screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf(Screen.Home) }
    var actionForHome: PendingAction? by remember { mutableStateOf(null) }

    LaunchedEffect(signal.openHomeOnce, signal.pendingAction) {
        if (signal.openHomeOnce) {
            signal.openHomeOnce = false
            screen = Screen.Home
        }
        signal.pendingAction?.let {
            actionForHome = it
            signal.pendingAction = null
        }
    }

    BackHandler(enabled = screen != Screen.Home) {
        screen = Screen.Home
    }

    when (val current = screen) {
        Screen.Home -> {
            val app = LocalContext.current.applicationContext as App
            val reviewVm: ReviewViewModel = viewModel(factory = ReviewViewModel.factory(app))
            HomeScreen(
                mainViewModel = viewModel,
                reviewViewModel = reviewVm,
                pendingAction = actionForHome,
                onActionConsumed = { actionForHome = null },
                onAddZone = { screen = Screen.MapZone(editId = null) },
                onEditZone = { id -> screen = Screen.MapZone(editId = id) },
                onOpenSettings = { screen = Screen.Settings },
            )
        }
        is Screen.MapZone -> {
            val app = LocalContext.current.applicationContext as App
            val mapVm: MapZoneViewModel = viewModel(
                key = "MapZone:${current.editId ?: "new"}",
                factory = MapZoneViewModel.factory(app, current.editId),
            )
            MapZoneScreen(
                viewModel = mapVm,
                onClose = { screen = Screen.Home },
            )
        }
        Screen.Settings -> SettingsScreen(
            onResyncGeofences = { viewModel.resyncGeofences() },
            onClose = { screen = Screen.Home },
        )
    }
}

private val ScreenSaver = Saver<Screen, String>(
    save = {
        when (it) {
            Screen.Home -> "home"
            is Screen.MapZone -> "map:${it.editId ?: ""}"
            Screen.Settings -> "settings"
        }
    },
    restore = {
        when {
            it == "settings" -> Screen.Settings
            it.startsWith("map:") -> Screen.MapZone(it.removePrefix("map:").toLongOrNull())
            else -> Screen.Home
        }
    },
)
