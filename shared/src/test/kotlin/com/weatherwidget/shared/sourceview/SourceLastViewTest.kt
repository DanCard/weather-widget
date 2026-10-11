package com.weatherwidget.shared.sourceview

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

@Category(ShortDuration::class)
class SourceLastViewTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 10, 9)

    private fun row(sourceId: String, date: LocalDate, switches: Int = 1) =
        SourceViewDayRow(SourceViewTally.dayMs(date), sourceId, "DAILY", "TOGGLE", false, switches)

    @Test
    fun `timestamp wins over day rows`() {
        val ts = 1_000L
        assertEquals(ts, SourceLastView.resolve("NWS", mapOf("NWS" to ts), listOf(row("NWS", day)), zone, nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `without a timestamp the newest day row counts as the end of that local day`() {
        val rows = listOf(row("NWS", day.minusDays(3)), row("NWS", day), row("OPEN_METEO", day.plusDays(1)))
        val expected = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, SourceLastView.resolve("NWS", emptyMap(), rows, zone, nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `no timestamp and no rows is never viewed`() {
        assertNull(SourceLastView.resolve("NWS", emptyMap(), listOf(row("OPEN_METEO", day), row("NWS", day, switches = 0)), zone, nowMs = Long.MAX_VALUE))
    }

    @Test
    fun `a view counted today is never later than now`() {
        val noon = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(noon, SourceLastView.resolve("NWS", emptyMap(), listOf(row("NWS", day)), zone, nowMs = noon))
    }
}
