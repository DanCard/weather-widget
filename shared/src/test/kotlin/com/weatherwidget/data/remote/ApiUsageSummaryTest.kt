package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Category(ShortDuration::class)
class ApiUsageSummaryTest {
    private val pacific = ZoneId.of("America/Los_Angeles")
    private val kyiv = ZoneId.of("Europe/Kyiv")

    private fun day(date: String) = LocalDate.parse(date).toEpochDay() * 86_400_000L

    private fun row(date: String, source: String, endpoint: String, calls: Int, errors: Int = 0, refused: Int = 0) =
        ApiUsageRow(day(date), source, endpoint, calls, errors, refused)

    // 2026-10-08 09:00 PDT.
    private val now = Instant.parse("2026-10-08T16:00:00Z")

    @Test
    fun `columns are today, this month, last month and 90 days`() {
        val rows = listOf(
            row("2026-10-08", "NWS", "points/{id}", 5),
            row("2026-10-02", "NWS", "points/{id}", 7),
            row("2026-09-30", "NWS", "points/{id}", 11),
            row("2026-09-01", "NWS", "points/{id}", 13),
            row("2026-08-15", "NWS", "points/{id}", 17),
            // 90 days back from Oct 8 inclusive is Jul 11; Jul 10 is out.
            row("2026-07-11", "NWS", "points/{id}", 19),
            row("2026-07-10", "NWS", "points/{id}", 1000),
        )
        val nws = ApiUsageSummary.summarize(rows, now, pacific).single()
        assertEquals(UsageCounts(today = 5, thisMonth = 12, lastMonth = 24, last90Days = 72), nws.counts)
    }

    @Test
    fun `google uses Pacific days and months on a device in Kyiv, other sources the local ones`() {
        // Oct 31 23:30 PDT = Nov 1 09:30 in Kyiv. Google is still in October; NWS is in November.
        val lateOct31Pt = Instant.parse("2026-11-01T06:30:00Z")
        val rows = listOf(
            row("2026-10-31", "GOOGLE_WEATHER", "forecast/hours", 3),
            row("2026-10-31", "NWS", "points/{id}", 4),
            row("2026-11-01", "NWS", "points/{id}", 2),
        )
        val bySource = ApiUsageSummary.summarize(rows, lateOct31Pt, kyiv).associateBy { it.sourceId }

        val google = bySource.getValue("GOOGLE_WEATHER")
        assertTrue(google.pacificDays)
        assertEquals(UsageCounts(today = 3, thisMonth = 3, lastMonth = 0, last90Days = 3), google.counts)

        val nws = bySource.getValue("NWS")
        assertFalse(nws.pacificDays)
        assertEquals(UsageCounts(today = 2, thisMonth = 2, lastMonth = 4, last90Days = 6), nws.counts)
    }

    @Test
    fun `endpoints add up to the source and pre-endpoint rows come last`() {
        val rows = listOf(
            row("2026-10-08", "GOOGLE_WEATHER", "", 10),
            row("2026-10-08", "GOOGLE_WEATHER", "forecast/days", 2),
            row("2026-10-08", "GOOGLE_WEATHER", "forecast/hours", 6),
            row("2026-10-07", "GOOGLE_WEATHER", "forecast/hours", 3, errors = 2, refused = 1),
        )
        val google = ApiUsageSummary.summarize(rows, now, pacific).single()
        assertEquals("Google Weather", google.displayName)
        assertEquals(listOf("forecast/hours", "forecast/days", ""), google.endpoints.map { it.endpoint })
        assertEquals(UsageCounts(today = 6, thisMonth = 9, lastMonth = 0, last90Days = 9), google.endpoints[0].counts)
        assertEquals(google.endpoints.fold(UsageCounts()) { acc, e -> acc + e.counts }, google.counts)
        assertEquals(21, google.counts.last90Days)
        assertEquals(2, google.errors)
        assertEquals(1, google.quotaRefused)
    }

    @Test
    fun `busiest source first, unknown ids kept, empty window dropped`() {
        val rows = listOf(
            row("2026-10-08", "NWS", "points/{id}", 50),
            row("2026-10-08", "SYNOPTIC", "stations/timeseries", 4),
            row("2026-10-08", "SOMETHING_NEW", "x", 9),
            row("2026-01-01", "TOMORROW_IO", "timelines", 99),
        )
        val summary = ApiUsageSummary.summarize(rows, now, pacific)
        assertEquals(listOf("NWS", "SOMETHING_NEW", "Synoptic"), summary.map { it.displayName })
    }
}
