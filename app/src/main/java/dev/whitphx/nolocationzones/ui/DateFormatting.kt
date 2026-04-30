package dev.whitphx.nolocationzones.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders a timestamp as `yyyy/MM/dd HH:mm` regardless of device locale.
 *
 * Earlier versions used `DateFormat.getBestDateTimePattern(Locale.getDefault(), "yyyyMMdd HHmm")`
 * to follow the device's language/region setting (the only date-format preference modern
 * Android exposes). That ordering trails the year on en-US devices (`04/21/2026, 14:57`) which
 * is the opposite of what we want, so the pattern is now hardcoded with [Locale.ROOT] for a
 * stable Y/M/D ordering everywhere.
 */
fun formatTimestamp(timestamp: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.ROOT).format(Date(timestamp))
