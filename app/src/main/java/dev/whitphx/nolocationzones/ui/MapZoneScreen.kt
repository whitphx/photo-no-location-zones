package dev.whitphx.nolocationzones.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import dev.whitphx.nolocationzones.domain.Zone
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapZoneScreen(viewModel: MapZoneViewModel, onClose: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = when (val s = state) {
                        is MapZoneUiState.Ready -> if (s.mode == MapZoneUiState.Mode.Edit) "Edit zone" else "Add zone"
                        else -> "Zone"
                    }
                    Text(title)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                MapZoneUiState.Loading -> LoadingBox()
                is MapZoneUiState.Error -> ErrorBox(message = s.message)
                is MapZoneUiState.Ready -> ReadyBody(
                    state = s,
                    viewModel = viewModel,
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun ReadyBody(
    state: MapZoneUiState.Ready,
    viewModel: MapZoneViewModel,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val existing by viewModel.existingZones.collectAsStateWithLifecycle()

    var name by remember { mutableStateOf(state.initialName) }
    var radius by remember { mutableFloatStateOf(state.initialRadiusMeters) }
    @Suppress("DEPRECATION") // rememberMarkerState's mutability is what we need; the replacement
    // (rememberUpdatedMarkerState) is for one-way state, but our marker is dragged by the user.
    val markerState = rememberMarkerState(
        position = LatLng(state.initialLatitude, state.initialLongitude),
    )
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            LatLng(state.initialLatitude, state.initialLongitude),
            if (state.locationKnown) 15f else 1f,
        )
    }

    var pendingDelete by remember { mutableStateOf(false) }

    val hasFineLocationPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = hasFineLocationPermission),
                uiSettings = MapUiSettings(
                    myLocationButtonEnabled = false,
                    zoomControlsEnabled = false,
                    compassEnabled = true,
                ),
                onMapClick = { latLng ->
                    markerState.position = latLng
                    coroutineScope.launch {
                        cameraPositionState.animate(CameraUpdateFactory.newLatLng(latLng))
                    }
                },
            ) {
                // Other zones rendered as faint gray circles for context. Skip the one being
                // edited — its candidate circle is drawn below in the active style.
                for (z in existing) {
                    if (z.id == state.editingZoneId) continue
                    Circle(
                        center = LatLng(z.latitude, z.longitude),
                        radius = z.radiusMeters.toDouble(),
                        strokeColor = Color(0x66888888),
                        fillColor = Color(0x22888888),
                        strokeWidth = 2f,
                    )
                }

                // Candidate zone: marker + filled circle showing the radius.
                Circle(
                    center = markerState.position,
                    radius = radius.toDouble(),
                    strokeColor = MaterialTheme.colorScheme.primary,
                    fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                    strokeWidth = 4f,
                )
                Marker(
                    state = markerState,
                    draggable = true,
                    title = name.ifBlank { "New zone" },
                )
            }

            FilledIconButton(
                onClick = {
                    coroutineScope.launch {
                        recenterOnCurrentLocation(
                            context = context,
                            cameraPositionState = cameraPositionState,
                            markerState = markerState,
                        )
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "Use my current location")
            }
        }

        BottomPanel(
            mode = state.mode,
            name = name,
            onNameChange = { name = it },
            radius = radius,
            onRadiusChange = { radius = it },
            onSave = {
                viewModel.save(
                    name = name,
                    latitude = markerState.position.latitude,
                    longitude = markerState.position.longitude,
                    radiusMeters = radius,
                )
                onClose()
            },
            onDelete = { pendingDelete = true },
        )
    }

    if (pendingDelete) {
        AlertDialog(
            onDismissRequest = { pendingDelete = false },
            title = { Text("Delete zone?") },
            text = { Text("\"${state.initialName}\" will be removed and the geofence unregistered.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = false
                    viewModel.delete()
                    onClose()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = false }) { Text("Cancel") }
            },
        )
    }

    // If we couldn't get a location fix in create mode, retry once when permission lands.
    LaunchedEffect(hasFineLocationPermission) {
        if (state.mode == MapZoneUiState.Mode.Create && !state.locationKnown && hasFineLocationPermission) {
            recenterOnCurrentLocation(context, cameraPositionState, markerState)
        }
    }
}

@SuppressLint("MissingPermission") // checked at call sites via the permission card flow
private suspend fun recenterOnCurrentLocation(
    context: android.content.Context,
    cameraPositionState: CameraPositionState,
    markerState: MarkerState,
) {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) return
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = runCatching {
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
    }.getOrNull() ?: return
    val target = LatLng(loc.latitude, loc.longitude)
    markerState.position = target
    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(target, 15f))
}

@Composable
private fun BottomPanel(
    mode: MapZoneUiState.Mode,
    name: String,
    onNameChange: (String) -> Unit,
    radius: Float,
    onRadiusChange: (Float) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("Zone name") },
                placeholder = { Text("e.g. Home, Office") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            RadiusControl(radius = radius, onRadiusChange = onRadiusChange)
            Spacer(Modifier.height(4.dp))
            Text(
                "Tap the map to move the center. Drag the pin to fine-tune.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (mode == MapZoneUiState.Mode.Edit) {
                    TextButton(onClick = onDelete, modifier = Modifier.weight(1f)) {
                        Text("Delete")
                    }
                }
                // Disabled when the user has actively cleared the (pre-populated) name — acts as
                // a safeguard against creating a placeholder-named zone, without surprising
                // first-time users who never see this state because the field is non-blank by
                // default.
                Button(
                    onClick = onSave,
                    enabled = name.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save") }
            }
        }
    }
}

/**
 * Radius slider snapped to 100 m increments, paired with an editable numeric field for exact
 * values. Slider drives the field; field drives the slider — both bidirectionally.
 */
@Composable
private fun RadiusControl(radius: Float, onRadiusChange: (Float) -> Unit) {
    val minM = Zone.MIN_RADIUS_METERS.toInt()
    val maxM = Zone.MAX_RADIUS_METERS.toInt()
    var radiusText by remember { mutableStateOf(radius.toInt().toString()) }

    // Slider/external -> text: when [radius] changes from outside (slider drag, edit-load),
    // sync the text field unless the user has typed a value that already matches.
    LaunchedEffect(radius) {
        if (radiusText.toIntOrNull()?.toFloat() != radius) {
            radiusText = radius.toInt().toString()
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Radius",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = radiusText,
            onValueChange = { input ->
                val cleaned = input.filter(Char::isDigit).take(5)
                radiusText = cleaned
                cleaned.toIntOrNull()?.let { v ->
                    onRadiusChange(v.coerceIn(minM, maxM).toFloat())
                }
            },
            suffix = { Text("m") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp),
        )
    }
    Slider(
        value = radius,
        onValueChange = onRadiusChange,
        // 48 intermediate stops over [100, 5000] = 50 discrete values 100 m apart.
        valueRange = Zone.MIN_RADIUS_METERS..Zone.MAX_RADIUS_METERS,
        steps = 48,
    )
    Text(
        "Slider snaps to 100 m steps. Type the field above for an exact value (100–5000 m).",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LoadingBox() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text("Locating…")
        }
    }
}

@Composable
private fun ErrorBox(message: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}
