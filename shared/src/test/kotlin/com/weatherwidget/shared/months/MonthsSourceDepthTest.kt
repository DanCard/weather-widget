package com.weatherwidget.shared.months

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class MonthsSourceDepthTest {

    private val today = LocalDate.parse("2026-10-15")

    private fun row(source: String, back: Long, hi: Float? = 60f, lo: Float? = 40f, fHi: Float? = null, lat: Double = 37.42) =
        DailyHistory(
            date = today.minusDays(back).toEpochDay() * 86_400_000L,
            source = source,
            locationLat = lat,
            locationLon = -122.09,
            computedHighTemp = hi,
            computedLowTemp = lo,
            condition = "x",
            updatedAt = 1L,
            forecastHighTemp = fHi,
            forecastLowTemp = fHi?.minus(20f),
        )

    private fun span(source: String, from: Long, to: Long = 1L) = (to..from).map { row(source, it) }

    @Test
    fun `depth counts the days the months line would draw`() {
        val rows = listOf(
            row("NWS", 10),
            row("NWS", 10, lat = 37.4201), // same-day fragment counts once
            row("NWS", 9, hi = null, lo = null, fHi = 70f), // frozen-forecast stand-in draws
            row("NWS", 8, hi = null, lo = null), // nothing to draw
            row("NWS", 0), // today is not history
        )
        assertEquals(listOf(SourceDepth("NWS", today.minusDays(10), 2)), MonthsSourceDepth.depths(rows, today))
    }

    @Test
    fun `suggests the deeper source - the Pixel case, Google from 12 days, NWS from 135`() {
        val depths = MonthsSourceDepth.depths(span("GOOGLE_WEATHER", 12) + span("NWS", 135), today)
        val visible = listOf("GOOGLE_WEATHER", "NWS")
        assertEquals("NWS", MonthsSourceDepth.suggestion("GOOGLE_WEATHER", depths, visible)?.sourceId)
        assertEquals(today.minusDays(135), MonthsSourceDepth.suggestion("GOOGLE_WEATHER", depths, visible)?.first)
        assertNull("NWS is already the deepest", MonthsSourceDepth.suggestion("NWS", depths, visible))
    }

    @Test
    fun `needs thirty days more and fourteen days in all`() {
        val visible = listOf("A", "B")
        // 29 days earlier: not enough.
        assertNull(MonthsSourceDepth.suggestion("A", MonthsSourceDepth.depths(span("A", 20) + span("B", 49), today), visible))
        // 30 days earlier: enough.
        assertEquals("B", MonthsSourceDepth.suggestion("A", MonthsSourceDepth.depths(span("A", 20) + span("B", 50), today), visible)?.sourceId)
        // Starts much earlier but only 13 days of rows: a stray few rows from an old site.
        val sparse = (100L downTo 88L).map { row("B", it) }
        assertNull(MonthsSourceDepth.suggestion("A", MonthsSourceDepth.depths(span("A", 20) + sparse, today), visible))
    }

    @Test
    fun `a source with no history takes any source with enough`() {
        val depths = MonthsSourceDepth.depths(span("B", 14), today)
        assertEquals("B", MonthsSourceDepth.suggestion("A", depths, listOf("A", "B"))?.sourceId)
    }

    @Test
    fun `most days wins, then the earlier start, then the user's order, and hidden sources never`() {
        val depths = listOf(
            SourceDepth("B", today.minusDays(100), 60),
            SourceDepth("C", today.minusDays(120), 80),
            SourceDepth("D", today.minusDays(130), 80),
            SourceDepth("E", today.minusDays(130), 80),
            SourceDepth("HIDDEN", today.minusDays(500), 400),
        )
        assertEquals("D", MonthsSourceDepth.suggestion("A", depths, listOf("A", "B", "C", "D", "E"))?.sourceId)
        assertEquals("E", MonthsSourceDepth.suggestion("A", depths, listOf("A", "B", "C", "E", "D").filter { it != "D" })?.sourceId)
    }
}
