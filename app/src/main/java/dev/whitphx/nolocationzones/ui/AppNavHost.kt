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
    data object Review : Screen
}

/** A specific action that should fire on the review screen on next composition (e.g. delivered
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
    var openReviewOnce: Boolean by mutableStateOf(false)
    var pendingAction: PendingAction? by mutableStateOf(null)
}

@Composable
fun AppNavHost(viewModel: MainViewModel, signal: NavSignal) {
    var screen: Screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf(Screen.Home) }
    var actionForReview: PendingAction? by remember { mutableStateOf(null) }

    LaunchedEffect(signal.openReviewOnce, signal.pendingAction) {
        if (signal.openReviewOnce) {
            signal.openReviewOnce = false
            screen = Screen.Review
        }
        // Pull the action *every* time so a re-fire (notification tapped twice in a row) still
        // delivers it once.
        signal.pendingAction?.let {
            actionForReview = it
            signal.pendingAction = null
        }
    }

    // System back / gesture back on a sub-screen pops to Home instead of finishing the Activity.
    // On Home itself, BackHandler is disabled so the default behaviour (close the app) wins.
    BackHandler(enabled = screen != Screen.Home) {
        screen = Screen.Home
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
                pendingAction = actionForReview,
                onActionConsumed = { actionForReview = null },
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
