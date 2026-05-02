package io.github.whitphx.nolocationzones.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.whitphx.nolocationzones.permissions.PermissionState
import io.github.whitphx.nolocationzones.permissions.Permissions

/**
 * Reads the current runtime-permission snapshot and re-reads it whenever the activity returns
 * to the foreground (e.g. after a Settings trip). Returns a [MutableState] so callers can also
 * trigger a refresh manually after a launcher result.
 */
@Composable
fun rememberPermissionState(): MutableState<PermissionState> {
    val context = LocalContext.current
    val state = remember { mutableStateOf(Permissions.read(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) state.value = Permissions.read(context)
        }
        lifecycle.addObserver(observer)
    }
    return state
}
