package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class NoHourlyCheckerTest {

    private val zone = ZoneId.systemDefault()

    private fun epochMs(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun hourly(date: LocalDate, hour: Int, source: String = "NWS"): HourlyForecast =
        HourlyForecast(
            dateTime = epochMs(date, hour),
            temperature = 72f,
            condition = "clear",
            source = source,
        )

    // ── the view the graph draws (Fold 4, 2026-11-15) ───────────────────────

    private val sunday = LocalDate.of(2026, 11, 22)
    private val tapNow = java.time.LocalDateTime.of(2026, 11, 15, 14, 43)
    private val tapView = DayClickResolver.dayTapView(tapNow, sunday)

    @Test
    fun `NWS ending at the tapped day's midnight is not covered - rows repeated at 00 00 are one hour`() {
        // The Fold: NWS hourly ends at Sun 00:00, stored at two sites; the tap opens 03:00-21:00.
        val list = listOf(hourly(sunday, 0), hourly(sunday, 0), hourly(sunday, 0, "GOOGLE_WEATHER"))
        println("tapView=$tapView")
        assertFalse(NoHourlyChecker.hasHourlyForDay(list, sunday, setOf("NWS"), tapView))
        // The whole-day question too: one distinct hour draws no line.
        assertFalse(NoHourlyChecker.hasHourlyForDay(list, sunday, setOf("NWS")))
    }

    @Test
    fun `two hours inside the view are covered`() {
        val list = listOf(hourly(sunday, 9), hourly(sunday, 10))
        assertTrue(NoHourlyChecker.hasHourlyForDay(list, sunday, setOf("NWS"), tapView))
    }

    @Test
    fun `hours only outside the view are not covered`() {
        val list = listOf(hourly(sunday, 0), hourly(sunday, 1), hourly(sunday, 2), hourly(sunday, 22), hourly(sunday, 23))
        assertFalse(NoHourlyChecker.hasHourlyForDay(list, sunday, setOf("NWS"), tapView))
        assertTrue(NoHourlyChecker.hasHourlyForDay(list, sunday, setOf("NWS")))
    }

    @Test
    fun `a day the view does not reach counts as covered`() {
        assertTrue(NoHourlyChecker.hasHourlyForDay(emptyList(), sunday.plusDays(1), setOf("NWS"), tapView))
    }

    @Test
    fun `emptyViewDay names the tapped day for an empty future view`() {
        val list = listOf(hourly(sunday, 0))
        assertEquals(sunday, NoHourlyChecker.emptyViewDay(list, tapView, setOf("NWS"), tapNow))
    }

    @Test
    fun `emptyViewDay is null when the view draws a line`() {
        val list = listOf(hourly(sunday, 3), hourly(sunday, 4))
        assertNull(NoHourlyChecker.emptyViewDay(list, tapView, setOf("NWS"), tapNow))
    }

    @Test
    fun `emptyViewDay is null for a view reaching before the current hour - observations may draw`() {
        val now = sunday.atTime(12, 20)
        val view = WeatherTimeUtils.hourWindow(now, 9, 9) // 03:00..21:00, straddles now
        assertNull(NoHourlyChecker.emptyViewDay(emptyList(), view, setOf("NWS"), now))
    }

    // ── hasHourlyForDay ──────────────────────────────────────────────────────

    @Test
    fun `hasHourlyForDay returns true when two or more points fall within the day`() {
        val date = LocalDate.of(2026, 7, 7)
        val list = listOf(hourly(date, 6), hourly(date, 12))
        assertTrue(NoHourlyChecker.hasHourlyForDay(list, date))
    }

    @Test
    fun `hasHourlyForDay returns false when fewer than two points in window`() {
        val date = LocalDate.of(2026, 7, 7)
        val list = listOf(hourly(date, 6))
        assertFalse(NoHourlyChecker.hasHourlyForDay(list, date))
    }

    @Test
    fun `hasHourlyForDay returns false when list is empty`() {
        assertFalse(NoHourlyChecker.hasHourlyForDay(emptyList(), LocalDate.of(2026, 7, 7)))
    }

    @Test
    fun `hasHourlyForDay filters by sourceIds when provided`() {
        val date = LocalDate.of(2026, 7, 7)
        val list = listOf(hourly(date, 6, "NWS"), hourly(date, 12, "OPEN_METEO"))
        // Only NWS requested — one point, not enough
        assertFalse(NoHourlyChecker.hasHourlyForDay(list, date, setOf("NWS")))
        // Both sources — two points, sufficient
        assertTrue(NoHourlyChecker.hasHourlyForDay(list, date, setOf("NWS", "OPEN_METEO")))
    }

    @Test
    fun `hasHourlyForDay counts GENERIC_GAP even when not in sourceIds`() {
        val date = LocalDate.of(2026, 7, 7)
        val list = listOf(
            hourly(date, 6, "NWS"),
            hourly(date, 12, NoHourlyChecker.GENERIC_GAP_SOURCE),
        )
        assertTrue(NoHourlyChecker.hasHourlyForDay(list, date, setOf("NWS")))
    }

    @Test
    fun `hasHourlyForDay ignores points outside the target day`() {
        val date = LocalDate.of(2026, 7, 7)
        val otherDate = LocalDate.of(2026, 7, 8)
        val list = listOf(hourly(date, 6), hourly(otherDate, 6), hourly(otherDate, 12))
        // Only one point on target date
        assertFalse(NoHourlyChecker.hasHourlyForDay(list, date))
    }

    // ── formatDayLabel ───────────────────────────────────────────────────────

    @Test
    fun `formatDayLabel produces EEE MMM d pattern`() {
        val label = NoHourlyChecker.formatDayLabel(LocalDate.of(2026, 7, 7))
        // "Tue Jul 7" — exact weekday depends on locale, but structure is EEE MMM d
        assertTrue(label.contains("Jul"))
        assertTrue(label.contains("7"))
        assertFalse(label.contains("07")) // no zero-padding
    }

    // ── lastHourlyEndLabel ───────────────────────────────────────────────────

    @Test
    fun `lastHourlyEndLabel returns null for empty list`() {
        assertNull(NoHourlyChecker.lastHourlyEndLabel(emptyList()))
    }

    @Test
    fun `lastHourlyEndLabel returns null when all points are in the past`() {
        val past = HourlyForecast(
            dateTime = System.currentTimeMillis() - 3_600_000L,
            temperature = 70f,
            condition = "clear",
        )
        assertNull(NoHourlyChecker.lastHourlyEndLabel(listOf(past)))
    }

    @Test
    fun `lastHourlyEndLabel picks the latest future point`() {
        val now = System.currentTimeMillis()
        val earlier = HourlyForecast(dateTime = now + 3_600_000L, temperature = 70f, condition = "clear", source = "NWS")
        val later   = HourlyForecast(dateTime = now + 7_200_000L, temperature = 72f, condition = "clear", source = "NWS")
        val label = NoHourlyChecker.lastHourlyEndLabel(listOf(earlier, later))
        val expectedLabel = NoHourlyChecker.lastHourlyEndLabel(listOf(later))
        assertEquals(expectedLabel, label)
    }

    @Test
    fun `lastHourlyEndLabel respects sourceIds filter`() {
        val now = System.currentTimeMillis()
        val nws  = HourlyForecast(dateTime = now + 3_600_000L,  temperature = 70f, condition = "clear", source = "NWS")
        val meta = HourlyForecast(dateTime = now + 14_400_000L, temperature = 72f, condition = "clear", source = "OPEN_METEO")
        // NWS-only should see only the NWS point
        val nwsLabel  = NoHourlyChecker.lastHourlyEndLabel(listOf(nws, meta), setOf("NWS"))
        val metaLabel = NoHourlyChecker.lastHourlyEndLabel(listOf(nws, meta), setOf("OPEN_METEO"))
        // meta point is later, so its label differs from nws-only
        assertTrue(nwsLabel != metaLabel)
    }

    // ── buildMessage ─────────────────────────────────────────────────────────

    @Test
    fun `buildMessage with endLabel includes both day and end`() {
        val msg = NoHourlyChecker.buildMessage("Tue Jul 7", "Mon Jul 6 at 4 PM")
        assertTrue(msg.contains("Tue Jul 7"))
        assertTrue(msg.contains("Mon Jul 6 at 4 PM"))
        assertTrue(msg.contains("data ends"))
    }

    @Test
    fun `buildMessage without endLabel omits data-ends clause`() {
        val msg = NoHourlyChecker.buildMessage("Tue Jul 7", null)
        assertTrue(msg.contains("Tue Jul 7"))
        assertFalse(msg.contains("data ends"))
    }

    // ── buildPendingMessage ───────────────────────────────────────────────────

    @Test
    fun `buildPendingMessage says the day's hourly forecast is being fetched`() {
        assertEquals("Fetching hourly forecast for Tue Jul 7…", NoHourlyChecker.buildPendingMessage("Tue Jul 7"))
    }

    // ── buildResultMessage ────────────────────────────────────────────────────

    @Test
    fun `buildResultMessage hasHourly true reports data available`() {
        val msg = NoHourlyChecker.buildResultMessage("Tue Jul 7", hasHourly = true, endLabel = null)
        assertTrue(msg.contains("Tue Jul 7"))
        assertTrue(msg.contains("now available", ignoreCase = true))
        assertTrue(msg.contains("Results of refresh", ignoreCase = true))
    }

    @Test
    fun `buildResultMessage hasHourly false with endLabel reports data ends`() {
        val msg = NoHourlyChecker.buildResultMessage("Tue Jul 7", hasHourly = false, endLabel = "Mon Jul 6 at 4 PM")
        assertTrue(msg.contains("Tue Jul 7"))
        assertTrue(msg.contains("Mon Jul 6 at 4 PM"))
        assertTrue(msg.contains("Results of refresh", ignoreCase = true))
        assertFalse(msg.contains("now available", ignoreCase = true))
    }

    @Test
    fun `buildResultMessage hasHourly false without endLabel omits data-ends clause`() {
        val msg = NoHourlyChecker.buildResultMessage("Tue Jul 7", hasHourly = false, endLabel = null)
        assertTrue(msg.contains("Tue Jul 7"))
        assertTrue(msg.contains("Results of refresh", ignoreCase = true))
        assertFalse(msg.contains("data ends", ignoreCase = true))
    }
}
