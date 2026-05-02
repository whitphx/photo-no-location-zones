package io.github.whitphx.nolocationzones.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * User-facing summary of what the strip operation actually does and what it doesn't. Collapsed
 * by default; expanding reveals the limits a privacy-minded user would want to know about (XMP,
 * Motion Photo MP4 trailer, IPTC, sensor PRNU). Mirrors the README "Privacy gaps" section.
 */
@Composable
fun ScrubScopeCard(initiallyExpanded: Boolean = false) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "What gets stripped (and what doesn't)",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Cleared:",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Bullet("All EXIF GPS fields (latitude, longitude, altitude, timestamp, bearing, processing method, etc. — 32 tags total).")
                Bullet("Camera MakerNote — vendor-specific blob that frequently embeds a duplicate of the GPS coordinates plus Wi-Fi SSIDs and per-photo asset IDs.")
                Bullet("Identifying tags: Artist, Camera Owner Name, Body/Lens Serial Number, User Comment, Image Description.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "NOT cleared (known gaps):",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Bullet("Adobe XMP packet — a separate XML metadata block in JPEG/HEIF that can carry city/country/creator names. Stripping it requires rewriting the container format directly; not yet implemented.")
                Bullet("Motion Photo / Live Photo embedded MP4 video — the video portion has its own GPS metadata that this scrubber does not touch.")
                Bullet("IPTC — a third metadata block written by some photo editors, also untouched.")
                Bullet("Sensor PRNU — every camera's unique noise pattern. Forensic tools can match a photo to a specific physical camera from the pixels alone, regardless of metadata.")
                Spacer(Modifier.height(4.dp))
                Text(
                    "See README \"Privacy gaps\" for the full discussion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Bullet(text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text("•  ", style = MaterialTheme.typography.bodySmall)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
