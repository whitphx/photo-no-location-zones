package io.github.whitphx.nolocationzones.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.content.res.AppCompatResources
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import io.github.whitphx.nolocationzones.R
import io.github.whitphx.nolocationzones.domain.Zone
import io.github.whitphx.nolocationzones.place.PhotonGeocoder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.maplibre.android.camera.CameraPosition as MlCameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Fill
import org.maplibre.android.plugins.annotation.FillManager
import org.maplibre.android.plugins.annotation.FillOptions
import org.maplibre.android.plugins.annotation.Line
import org.maplibre.android.plugins.annotation.LineManager
import org.maplibre.android.plugins.annotation.LineOptions
import org.maplibre.android.plugins.annotation.OnSymbolDragListener
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions

private const val ZONE_PIN_ICON = "zone-pin"

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
    var pinLatLng by remember {
        mutableStateOf(LatLng(state.initialLatitude, state.initialLongitude))
    }
    var pendingDelete by remember { mutableStateOf(false) }

    val hasFineLocationPermission = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    val refs = remember { MapRefs() }
    val primaryColor = MaterialTheme.colorScheme.primary

    var searchResultsCleared by remember { mutableStateOf(false) }
    val onSearchResultsCleared: () -> Unit = { searchResultsCleared = true }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            MapZoneMap(
                refs = refs,
                initialLatLng = LatLng(state.initialLatitude, state.initialLongitude),
                initialZoom = if (state.locationKnown) 15.0 else 1.0,
                pinLatLng = pinLatLng,
                radiusMeters = radius,
                otherZones = existing.filter { it.id != state.editingZoneId },
                primaryColor = primaryColor,
                onMapTap = { latLng ->
                    pinLatLng = latLng
                    refs.animateTo(latLng)
                    onSearchResultsCleared()
                },
                onPinDragged = { latLng -> pinLatLng = latLng },
            )

            SearchOverlay(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                clearResultsSignal = searchResultsCleared,
                onResultsConsumed = { searchResultsCleared = false },
                onPick = { result ->
                    val ll = LatLng(result.lat, result.lon)
                    pinLatLng = ll
                    refs.animateTo(ll, zoom = 14.0)
                },
            )

            FilledIconButton(
                onClick = {
                    coroutineScope.launch {
                        val ll = currentLocation(context) ?: return@launch
                        pinLatLng = ll
                        refs.animateTo(ll, zoom = 15.0)
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
                    latitude = pinLatLng.latitude,
                    longitude = pinLatLng.longitude,
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

    LaunchedEffect(hasFineLocationPermission) {
        if (state.mode == MapZoneUiState.Mode.Create && !state.locationKnown && hasFineLocationPermission) {
            val ll = currentLocation(context) ?: return@LaunchedEffect
            pinLatLng = ll
            refs.animateTo(ll, zoom = 15.0)
        }
    }
}

@Composable
private fun MapZoneMap(
    refs: MapRefs,
    initialLatLng: LatLng,
    initialZoom: Double,
    pinLatLng: LatLng,
    radiusMeters: Float,
    otherZones: List<Zone>,
    primaryColor: Color,
    onMapTap: (LatLng) -> Unit,
    onPinDragged: (LatLng) -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val primaryArgb = primaryColor.toArgb()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val mapView = MapView(ctx)
            mapView.onCreate(null)
            refs.mapView = mapView
            mapView.getMapAsync { map ->
                refs.map = map
                map.uiSettings.isAttributionEnabled = true
                map.uiSettings.isLogoEnabled = false
                map.cameraPosition = MlCameraPosition.Builder()
                    .target(initialLatLng)
                    .zoom(initialZoom)
                    .build()
                map.addOnMapClickListener { latLng ->
                    onMapTap(latLng)
                    true
                }
                map.setStyle(Style.Builder().fromUri(OPEN_FREE_MAP_LIBERTY)) { style ->
                    refs.style = style

                    val drawable = AppCompatResources.getDrawable(ctx, R.drawable.ic_zone_pin)
                        ?.mutate()
                        ?.apply { setTint(primaryArgb) }
                    drawable?.toBitmap(96, 96)?.let { bmp -> style.addImage(ZONE_PIN_ICON, bmp) }

                    refs.fillManager = FillManager(mapView, map, style)
                    refs.lineManager = LineManager(mapView, map, style)
                    refs.symbolManager = SymbolManager(mapView, map, style).apply {
                        iconAllowOverlap = true
                        iconIgnorePlacement = true
                        addDragListener(object : OnSymbolDragListener {
                            override fun onAnnotationDragStarted(annotation: Symbol) = Unit
                            override fun onAnnotationDrag(annotation: Symbol) {
                                onPinDragged(annotation.latLng)
                            }
                            override fun onAnnotationDragFinished(annotation: Symbol) {
                                onPinDragged(annotation.latLng)
                            }
                        })
                    }

                    refs.applyState()
                }
            }
            mapView
        },
        update = { _ ->
            refs.pendingPin = pinLatLng
            refs.pendingRadius = radiusMeters
            refs.pendingOthers = otherZones
            refs.pendingPrimaryArgb = primaryArgb
            refs.applyState()
        },
    )

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            val mv = refs.mapView ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> mv.onStart()
                Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause()
                Lifecycle.Event.ON_STOP -> mv.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            refs.symbolManager?.onDestroy()
            refs.lineManager?.onDestroy()
            refs.fillManager?.onDestroy()
            refs.mapView?.onDestroy()
            refs.mapView = null
            refs.map = null
            refs.style = null
            refs.symbolManager = null
            refs.fillManager = null
            refs.lineManager = null
        }
    }
}

/**
 * Holds the imperative MapLibre objects across recompositions and lets the camera-recenter
 * button reach the underlying map without a separate state hoist.
 *
 * State the Compose layer wants drawn lives in the `pending*` fields. `applyState` is the only
 * mutator of map overlays — it's a no-op until both the MapView is attached and the style has
 * loaded, so calls during the async style load are safely buffered.
 */
private class MapRefs {
    var mapView: MapView? = null
    var map: MapLibreMap? = null
    var style: Style? = null
    var fillManager: FillManager? = null
    var lineManager: LineManager? = null
    var symbolManager: SymbolManager? = null

    private var pinSymbol: Symbol? = null
    private var candidateFill: Fill? = null
    private var candidateOutline: Line? = null
    private var existingFills: List<Fill> = emptyList()
    private var existingOutlines: List<Line> = emptyList()
    private var renderedOthersKey: List<OtherZoneKey> = emptyList()

    var pendingPin: LatLng = LatLng(0.0, 0.0)
    var pendingRadius: Float = Zone.MIN_RADIUS_METERS
    var pendingOthers: List<Zone> = emptyList()
    var pendingPrimaryArgb: Int = 0

    fun animateTo(latLng: LatLng, zoom: Double? = null) {
        val m = map ?: return
        val update = if (zoom != null) {
            CameraUpdateFactory.newLatLngZoom(latLng, zoom)
        } else {
            CameraUpdateFactory.newLatLng(latLng)
        }
        m.animateCamera(update)
    }

    fun applyState() {
        val sm = symbolManager ?: return
        val fm = fillManager ?: return
        val lm = lineManager ?: return

        val primaryHex = String.format("#%06X", pendingPrimaryArgb and 0xFFFFFF)

        // Pin symbol — create once, then reposition. Drag handler updates pendingPin via the
        // Compose state path, so re-asserting it here from pendingPin is a no-op during drag.
        val existingSym = pinSymbol
        if (existingSym == null) {
            pinSymbol = sm.create(
                SymbolOptions()
                    .withLatLng(pendingPin)
                    .withIconImage(ZONE_PIN_ICON)
                    .withIconAnchor("bottom")
                    .withDraggable(true),
            )
        } else if (existingSym.latLng != pendingPin) {
            existingSym.latLng = pendingPin
            sm.update(existingSym)
        }

        // Other zones: rebuild only when the set changes (not on every radius slider tick).
        val currentKey = pendingOthers.map { OtherZoneKey(it.id, it.latitude, it.longitude, it.radiusMeters) }
        if (currentKey != renderedOthersKey) {
            existingFills.forEach(fm::delete)
            existingOutlines.forEach(lm::delete)
            existingFills = pendingOthers.map { z ->
                val poly = circlePolygon(LatLng(z.latitude, z.longitude), z.radiusMeters.toDouble())
                fm.create(
                    FillOptions()
                        .withLatLngs(listOf(poly))
                        .withFillColor("#888888")
                        .withFillOpacity(0.13f),
                )
            }
            existingOutlines = pendingOthers.map { z ->
                val poly = circlePolygon(LatLng(z.latitude, z.longitude), z.radiusMeters.toDouble())
                lm.create(
                    LineOptions()
                        .withLatLngs(poly)
                        .withLineColor("#666666")
                        .withLineOpacity(0.6f)
                        .withLineWidth(2f),
                )
            }
            renderedOthersKey = currentKey
        }

        // Candidate circle: full re-create on every change. With a 64-vertex polygon and at
        // most one fill+line, this is cheap.
        candidateFill?.let(fm::delete)
        candidateOutline?.let(lm::delete)
        val candidatePoly = circlePolygon(pendingPin, pendingRadius.toDouble())
        candidateFill = fm.create(
            FillOptions()
                .withLatLngs(listOf(candidatePoly))
                .withFillColor(primaryHex)
                .withFillOpacity(0.2f),
        )
        candidateOutline = lm.create(
            LineOptions()
                .withLatLngs(candidatePoly)
                .withLineColor(primaryHex)
                .withLineWidth(4f),
        )
    }

    private data class OtherZoneKey(
        val id: Long,
        val lat: Double,
        val lng: Double,
        val radius: Float,
    )
}

@SuppressLint("MissingPermission") // checked at the top of the function
private suspend fun currentLocation(context: Context): LatLng? {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
        PackageManager.PERMISSION_GRANTED
    ) return null
    val client = LocationServices.getFusedLocationProviderClient(context)
    val loc = runCatching {
        client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null).await()
    }.getOrNull() ?: return null
    return LatLng(loc.latitude, loc.longitude)
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
                "Tap the map to set the pin location.",
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

/**
 * Search box overlaid at the top of the map. As the user types, debounces 350 ms and queries
 * Photon (no API key, OSM-backed). Tapping a result flies the camera there and moves the pin.
 *
 * The caller drives a "clear results" pulse via [clearResultsSignal]: when the user taps
 * elsewhere on the map, the overlay collapses its result list. We use a one-shot bool +
 * `onResultsConsumed` callback rather than a regular state so successive taps work without the
 * caller having to reset on its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchOverlay(
    modifier: Modifier = Modifier,
    clearResultsSignal: Boolean,
    onResultsConsumed: () -> Unit,
    onPick: (PhotonGeocoder.GeoResult) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PhotonGeocoder.GeoResult>>(emptyList()) }
    var lastPickedQuery by remember { mutableStateOf<String?>(null) }
    val keyboard = LocalSoftwareKeyboardController.current

    // Debounced search. Skip when the current query is exactly the one we just auto-set after a
    // pick — otherwise the field text would re-trigger a network call for the picked place.
    LaunchedEffect(query) {
        if (query == lastPickedQuery) return@LaunchedEffect
        delay(350)
        results = PhotonGeocoder.search(query)
    }

    // Map taps (or other external "dismiss" sources) collapse the result list without losing the
    // typed query — the user can resume by tapping back into the field.
    LaunchedEffect(clearResultsSignal) {
        if (clearResultsSignal) {
            results = emptyList()
            onResultsConsumed()
        }
    }

    Column(modifier = modifier.widthIn(max = 560.dp)) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (lastPickedQuery != null && it != lastPickedQuery) lastPickedQuery = null
                },
                placeholder = { Text("Search a place") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = {
                            query = ""
                            results = emptyList()
                            lastPickedQuery = null
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                } else null,
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (results.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp,
            ) {
                LazyColumn(
                    // Cap so the dropdown never eats the whole map even with 8 long-named results.
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    items(results) { result ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(result)
                                    query = result.name
                                    lastPickedQuery = result.name
                                    results = emptyList()
                                    keyboard?.hide()
                                }
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Filled.Search,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    result.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                if (result.description.isNotBlank()) {
                                    Text(
                                        result.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
