package dev.whitphx.nolocationzones.ui

import android.text.format.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Locale-correct timestamp string with a 4-digit year, e.g.:
 *  - ja-JP / ja-JP-u-rg-jpzzzz → `2026/04/30 14:32`
 *  - en-US                    → `04/30/2026 2:32 PM`
 *  - en-GB                    → `30/04/2026 14:32`
 *  - de-DE                    → `30.04.2026 14:32`
 *
 * The skeleton `yyyyMMdd HHmm` is passed through the platform's
 * [DateFormat.getBestDateTimePattern], which arranges the components in the order conventional
 * for the supplied locale. We use [Locale.getDefault] so the device's language/region preference
 * — which is the only date-format setting modern Android exposes — drives the result.
 */
fun formatTimestamp(timestamp: Long): String {
    val locale = Locale.getDefault()
    val pattern = DateFormat.getBestDateTimePattern(locale, "yyyyMMdd HHmm")
    return SimpleDateFormat(pattern, locale).format(Date(timestamp))
}
