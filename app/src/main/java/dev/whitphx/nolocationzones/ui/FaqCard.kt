package dev.whitphx.nolocationzones.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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

private data class Faq(val question: String, val answer: String)

private val FAQS = listOf(
    Faq(
        question = "Why do I see duplicate photos in Google Photos / OneDrive / Samsung Cloud after stripping?",
        answer = """
            Cloud-sync apps identify photos by their byte-content hash. When this app strips GPS metadata the file's bytes change, so the cloud service sees the modified file as a new upload — separate from the GPS-tagged original it backed up earlier. The on-device file is correctly overwritten in place; only the cloud library ends up with two entries.

            Cleanest fix: pause cloud backup → take photo → strip GPS → resume backup. The original never reaches the cloud.

            Alternative: use Google Photos' own "Details → Remove location" on the cloud entry. That edits the cloud copy in place without re-upload (but only fixes the cloud copy, not the on-device file).

            We can't programmatically delete or replace cloud entries from this app — third-party apps don't have that level of access to Google Photos, OneDrive, or Samsung Cloud libraries.
        """.trimIndent(),
    ),
    Faq(
        question = "Is the photo still safe to share after stripping?",
        answer = """
            For most casual use: yes. The 32 EXIF GPS fields and the vendor MakerNote (where Samsung/Apple/Google embed a duplicate of the coordinates) are all cleared in place.

            For an adversarial threat model: read the "What gets stripped (and what doesn't)" card above carefully. The Adobe XMP packet, IPTC, and any embedded MP4 video (Motion Photo / Live Photo) are NOT touched yet. Sensor PRNU also survives — forensic tools can fingerprint a photo to a specific physical camera by its noise pattern, regardless of metadata. Run "exiftool -all=" on a desktop if you need a deeper scrub.
        """.trimIndent(),
    ),
    Faq(
        question = "I tapped Strip GPS but the photo still has location on Google Photos.",
        answer = """
            Two likely causes:

            1. The cloud entry you're looking at is the original upload, made before our strip. The on-device file is clean; Google Photos just hasn't re-uploaded yet, or it has, and you're seeing the old entry. Check via "adb shell ls /sdcard/DCIM/Camera/" or any local file manager — there's only one file there, and it's clean.

            2. The strip succeeded but Google Photos cached the location server-side. Open the photo in Google Photos → Details → "Remove location" to clear it from the cloud copy directly.
        """.trimIndent(),
    ),
)

/**
 * Stacked, individually-expandable list of frequently-asked questions. Designed to grow over
 * time — add a new entry to [FAQS]; layout adapts automatically.
 */
@Composable
fun FaqCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "FAQ",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            FAQS.forEachIndexed { index, faq ->
                if (index > 0) HorizontalDivider()
                FaqRow(faq)
            }
        }
    }
}

@Composable
private fun FaqRow(faq: Faq) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                faq.question,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text(
                faq.answer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
