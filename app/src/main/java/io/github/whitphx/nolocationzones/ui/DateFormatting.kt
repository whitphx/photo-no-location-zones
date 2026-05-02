package io.github.whitphx.nolocationzones.ui

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Locale-correct timestamp string with a 4-digit year, e.g.:
 *  - ja-JP → `2026/04/30 14:32`
 *  - en-US → `04/30/2026 2:32 PM` (or `04/30/2026, 14:57` with the 24-hour skeleton)
 *  - en-GB → `30/04/2026 14:32`
 *  - de-DE → `30.04.2026 14:32`
 *
 * The skeleton `yyyyMMdd HHmm` is passed through the platform's
 * [DateFormat.getBestDateTimePattern], which arranges the components in the order conventional
 * for the supplied locale and forces a 4-digit year + 24-hour clock. [Locale.getDefault] is the
 * device's language/region preference — the only date-format setting modern Android still
 * exposes — so changing the device language is the way to change the format.
 */
fun formatTimestamp(timestamp: Long): String {
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "yyyyMMdd HHmm")
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}
