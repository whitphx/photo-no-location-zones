package dev.whitphx.nolocationzones.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.whitphx.nolocationzones.domain.Zone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddZoneScreen(viewModel: MainViewModel, onClose: () -> Unit) {
    val state by viewModel.addZoneState.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableFloatStateOf(Zone.DEFAULT_RADIUS_METERS) }

    LaunchedEffect(Unit) { viewModel.captureCurrentLocation() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add zone") },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                AddZoneState.Idle, AddZoneState.FetchingLocation -> {
                    LocationPlaceholder()
                }
                is AddZoneState.Error -> {
                    ErrorBox(message = s.message, onRetry = { viewModel.captureCurrentLocation() })
                }
                is AddZoneState.GotLocation -> {
                    LocationCard(latitude = s.latitude, longitude = s.longitude)

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Zone name (e.g. Home, Office)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Radius: ${radius.toInt()} m", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Smaller radii are more precise but produce more enter/exit events from GPS noise. 100 m is the floor.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            Slider(
                                value = radius,
                                onValueChange = { radius = it },
                                valueRange = Zone.MIN_RADIUS_METERS..Zone.MAX_RADIUS_METERS,
                                steps = 49,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onClose) { Text("Cancel") }
                        Spacer(Modifier.height(0.dp))
                        Button(
                            onClick = {
                                viewModel.saveZone(
                                    ProposedZone(
                                        name = name.ifBlank { "Zone" },
                                        latitude = s.latitude,
                                        longitude = s.longitude,
                                        radiusMeters = radius,
                                    )
                                )
                                onClose()
                            },
                            enabled = name.isNotBlank(),
                        ) { Text("Save") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationPlaceholder() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Getting your current location…")
            }
        }
    }
}

@Composable
private fun LocationCard(latitude: Double, longitude: Double) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Center", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("${"%.6f".format(latitude)}, ${"%.6f".format(longitude)}")
        }
    }
}

@Composable
private fun ErrorBox(message: String, onRetry: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Couldn't get a location fix", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("Try again") }
        }
    }
}
