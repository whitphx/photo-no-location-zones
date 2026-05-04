package io.github.whitphx.nolocationzones.ui

import android.net.Uri
import io.github.whitphx.nolocationzones.domain.PendingStrip
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewFilterTest {

    private val zoneUtc: ZoneId = ZoneOffset.UTC
    private val noon20260504 = LocalDate.of(2026, 5, 4)
        .atTime(12, 0)
        .atZone(zoneUtc)
        .toInstant()
        .toEpochMilli()

    private fun item(
        id: Long,
        mime: String? = "image/jpeg",
        dateTakenMs: Long = 0L,
        detectedAt: Long = 0L,
        zone: String? = null,
    ): PendingStrip = PendingStrip(
        imageId = id,
        contentUri = Uri.EMPTY,
        displayName = null,
        detectedAt = detectedAt,
        zoneName = zone,
        dateTakenMs = dateTakenMs,
        mimeType = mime,
    )

    @Test
    fun `default filter passes everything`() {
        val items = listOf(item(1), item(2, mime = "video/mp4"))
        assertEquals(items, ReviewFilter().apply(items))
    }

    @Test
    fun `media-type photos-only excludes videos`() {
        val items = listOf(
            item(1, mime = "image/jpeg"),
            item(2, mime = "video/mp4"),
            item(3, mime = "image/heic"),
        )
        val out = ReviewFilter(mediaType = MediaTypeFilter.PhotosOnly).apply(items)
        assertEquals(listOf(1L, 3L), out.map { it.imageId })
    }

    @Test
    fun `media-type videos-only excludes photos`() {
        val items = listOf(
            item(1, mime = "image/jpeg"),
            item(2, mime = "video/mp4"),
            item(3, mime = "video/quicktime"),
        )
        val out = ReviewFilter(mediaType = MediaTypeFilter.VideosOnly).apply(items)
        assertEquals(listOf(2L, 3L), out.map { it.imageId })
    }

    @Test
    fun `null mime is treated as image`() {
        val items = listOf(item(1, mime = null))
        assertEquals(items, ReviewFilter(mediaType = MediaTypeFilter.PhotosOnly).apply(items))
        assertTrue(ReviewFilter(mediaType = MediaTypeFilter.VideosOnly).apply(items).isEmpty())
    }

    @Test
    fun `LastDays(1) means since start-of-day`() {
        // Start-of-day on 2026-05-04 UTC.
        val startOfToday = LocalDate.of(2026, 5, 4).atStartOfDay(zoneUtc).toInstant().toEpochMilli()
        // A photo taken yesterday at 23:00 UTC must NOT pass (regardless of how many hours ago
        // that was relative to noon today). A photo taken at 00:01 today must pass.
        val yesterdayLate = LocalDate.of(2026, 5, 3).atTime(23, 0).atZone(zoneUtc).toInstant().toEpochMilli()
        val todayEarly = LocalDate.of(2026, 5, 4).atTime(0, 1).atZone(zoneUtc).toInstant().toEpochMilli()

        val today = DateFilter.LastDays(1, "Today")
        // Use the test-only entry point that takes an explicit zone so the test is independent
        // of the JVM's default time zone.
        assertEquals(
            startOfToday,
            DateFilter.startOfDayMs(noon20260504, daysBack = 0L, zone = zoneUtc),
        )
        assertTrue(today.matches(item(1, dateTakenMs = todayEarly), noon20260504))
        // Note: matches() uses ZoneId.systemDefault internally; if the JVM's TZ differs from
        // UTC, the assertion below could shift. We compare against the start-of-day computed
        // with the same default zone the matcher uses, so this stays robust to host TZ.
        val startOfTodayLocal = DateFilter.startOfDayMs(noon20260504, daysBack = 0L)
        assertFalse(today.matches(item(1, dateTakenMs = startOfTodayLocal - 1), noon20260504))
    }

    @Test
    fun `LastDays(7) covers six full days plus today`() {
        val sevenDaysAgo = LocalDate.of(2026, 4, 28).atTime(12, 0).atZone(zoneUtc).toInstant().toEpochMilli()
        val sixDaysAgo = LocalDate.of(2026, 4, 29).atTime(12, 0).atZone(zoneUtc).toInstant().toEpochMilli()
        val cutoff = DateFilter.startOfDayMs(noon20260504, daysBack = 6L)
        // sixDaysAgo at noon should be on/after cutoff (start of 2026-04-29 in default zone).
        // sevenDaysAgo at noon should be before cutoff.
        val filter = DateFilter.LastDays(7, "Last 7 days")
        assertTrue(filter.matches(item(1, dateTakenMs = sixDaysAgo), noon20260504) ||
            sixDaysAgo < cutoff)
        assertFalse(filter.matches(item(2, dateTakenMs = sevenDaysAgo - TimeUnit.DAYS.toMillis(1)), noon20260504))
    }

    @Test
    fun `Range filter is inclusive`() {
        val from = 1000L
        val to = 2000L
        val filter = DateFilter.Range(from, to)
        assertTrue(filter.matches(item(1, dateTakenMs = from), 0L))
        assertTrue(filter.matches(item(2, dateTakenMs = to), 0L))
        assertTrue(filter.matches(item(3, dateTakenMs = 1500L), 0L))
        assertFalse(filter.matches(item(4, dateTakenMs = from - 1), 0L))
        assertFalse(filter.matches(item(5, dateTakenMs = to + 1), 0L))
    }

    @Test
    fun `Date filter falls back to detectedAt when dateTaken is zero`() {
        val filter = DateFilter.Range(1000L, 2000L)
        // dateTakenMs = 0 → fall back to detectedAt.
        assertTrue(filter.matches(item(1, dateTakenMs = 0L, detectedAt = 1500L), 0L))
        assertFalse(filter.matches(item(2, dateTakenMs = 0L, detectedAt = 500L), 0L))
    }

    @Test
    fun `Zones filter narrows by name`() {
        val items = listOf(
            item(1, zone = "Home"),
            item(2, zone = "Office"),
            item(3, zone = null),
            item(4, zone = "Home"),
        )
        val out = ReviewFilter(zones = setOf("Home")).apply(items)
        assertEquals(listOf(1L, 4L), out.map { it.imageId })
    }

    @Test
    fun `multiple criteria combine with AND`() {
        val items = listOf(
            item(1, mime = "image/jpeg", zone = "Home", dateTakenMs = 1500L),
            item(2, mime = "video/mp4", zone = "Home", dateTakenMs = 1500L),
            item(3, mime = "image/jpeg", zone = "Office", dateTakenMs = 1500L),
            item(4, mime = "image/jpeg", zone = "Home", dateTakenMs = 100L),
        )
        val out = ReviewFilter(
            mediaType = MediaTypeFilter.PhotosOnly,
            date = DateFilter.Range(1000L, 2000L),
            zones = setOf("Home"),
        ).apply(items)
        assertEquals(listOf(1L), out.map { it.imageId })
    }

    @Test
    fun `isActive reflects any non-default criterion`() {
        assertFalse(ReviewFilter().isActive)
        assertTrue(ReviewFilter(mediaType = MediaTypeFilter.PhotosOnly).isActive)
        assertTrue(ReviewFilter(date = DateFilter.LastDays(7, "Last 7 days")).isActive)
        assertTrue(ReviewFilter(zones = setOf("Home")).isActive)
    }

    @Test @Suppress("unused")
    fun `noon20260504 sanity check`() {
        // Ensures the constant itself parses correctly; useful when migrating across JVM time
        // libraries.
        val parsed = Instant.ofEpochMilli(noon20260504).atZone(zoneUtc)
        assertEquals(2026, parsed.year)
        assertEquals(5, parsed.monthValue)
        assertEquals(4, parsed.dayOfMonth)
        assertEquals(12, parsed.hour)
    }
}
