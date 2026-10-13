package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.ForecastHistoryViewLogic.LocationActuals
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Which "Location actual" lines Forecast History draws: past both, today the settled low only. */
@Category(ShortDuration::class)
class ForecastHistoryLocationActualsTest {

    private val pacific = ZoneId.of("America/Los_Angeles")
    private val oct13 = LocalDate.of(2026, 10, 13) // Mountain View sunrise ≈ 07:17 → cutoff ≈ 07:47
    private val highAt = 1_000L
    private val lowAt = 2_000L

    private data class Hour(val ms: Long, val temp: Float)

    private fun hour(t: LocalDateTime, temp: Float) = Hour(t.atZone(pacific).toInstant().toEpochMilli(), temp)

    private fun resolve(
        date: LocalDate,
        now: LocalDateTime,
        low: Float? = 52f,
        hourly: List<Hour> = emptyList(),
    ): LocationActuals = ForecastHistoryViewLogic.locationActuals(
        date = date, now = now, zone = pacific, lat = 37.4166, lon = -122.0889,
        computedHigh = 71f, computedLow = low, computedHighAt = highAt, computedLowAt = lowAt,
        todayHourly = hourly, epochMs = { it.ms }, temperature = { it.temp },
    )

    @Test
    fun `past day gets both extremes and their times`() {
        val r = resolve(oct13.minusDays(1), oct13.atTime(10, 0))
        assertEquals(LocationActuals(71f, 52f, highAt, lowAt), r)
        assertTrue(r.hasAny)
    }

    @Test
    fun `today after nine gets the low only, with its time`() {
        val r = resolve(oct13, oct13.atTime(10, 0))
        assertEquals(LocationActuals(null, 52f, null, lowAt), r)
        assertTrue(r.hasAny)
    }

    @Test
    fun `today before sunrise plus thirty has no line`() {
        assertEquals(LocationActuals.NONE, resolve(oct13, oct13.atTime(6, 30)))
    }

    @Test
    fun `today between cutoff and nine waits while the forecast still falls`() {
        val now = oct13.atTime(8, 0)
        val falling = listOf(hour(now.plusHours(1), 50f))
        val rising = listOf(hour(now.plusHours(1), 56f))
        assertEquals(LocationActuals.NONE, resolve(oct13, now, hourly = falling))
        assertEquals(52f, resolve(oct13, now, hourly = rising).low)
    }

    @Test
    fun `today without an observed low has no line`() {
        val r = resolve(oct13, oct13.atTime(12, 0), low = null)
        assertFalse(r.hasAny)
    }

    @Test
    fun `future day has no line`() {
        assertEquals(LocationActuals.NONE, resolve(oct13.plusDays(1), oct13.atTime(12, 0)))
    }

    @Test
    fun `only today needs hourly`() {
        assertTrue(ForecastHistoryViewLogic.needsTodayHourly(oct13, today = oct13))
        assertFalse(ForecastHistoryViewLogic.needsTodayHourly(oct13.minusDays(1), today = oct13))
    }
}
