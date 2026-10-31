package com.weatherwidget.shared.graph

import com.weatherwidget.shared.graph.ForecastHistoryViewLogic.LocationActualRow
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

/** Which "Location actual" lines Forecast History draws: past both, today each once settled. */
@Category(ShortDuration::class)
class ForecastHistoryLocationActualsTest {

    private val pacific = ZoneId.of("America/Los_Angeles")
    private val oct13 = LocalDate.of(2026, 10, 13) // Mountain View sunrise ≈ 07:17 → cutoff ≈ 07:47
    private val highAt = 1_000L
    private val lowAt = 2_000L

    private val siteLat = 37.417
    private val siteLon = -122.089
    private val NWS = "NWS"

    private fun row(
        source: String,
        high: Float? = 71f,
        low: Float? = 52f,
        highAtMs: Long? = highAt,
        lat: Double = siteLat,
        lon: Double = siteLon,
    ) = LocationActualRow(source, lat, lon, high, low, highAtMs, lowAt)

    private data class Hour(val ms: Long, val temp: Float)

    private fun hour(t: LocalDateTime, temp: Float) = Hour(t.atZone(pacific).toInstant().toEpochMilli(), temp)

    private fun resolve(
        date: LocalDate,
        now: LocalDateTime,
        low: Float? = 52f,
        hourly: List<Hour> = emptyList(),
        rows: List<LocationActualRow> = listOf(row(NWS, high = 71f, low = low)),
    ): LocationActuals = ForecastHistoryViewLogic.locationActuals(
        date = date, now = now, zone = pacific, lat = siteLat, lon = siteLon,
        sourceId = NWS, rows = rows,
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
    fun `today high line starts at five pm with its time`() {
        assertEquals(null, resolve(oct13, oct13.atTime(16, 59)).high)
        assertEquals(LocationActuals(71f, 52f, highAt, lowAt), resolve(oct13, oct13.atTime(17, 0)))
    }

    @Test
    fun `today high ignores a hotter row from another source`() {
        val rows = listOf(row(NWS, high = 64.3f, highAtMs = 10L), row("TOMORROW_IO", high = 90f, highAtMs = 20L))
        val r = resolve(oct13, oct13.atTime(17, 0), rows = rows)
        assertEquals(64.3f, r.high)
        assertEquals(10L, r.highReachedAt)
    }

    @Test
    fun `today has no high line when only another source row has one`() {
        val rows = listOf(row(NWS, high = null), row("SILURIAN", high = 64.4f))
        assertEquals(null, resolve(oct13, oct13.atTime(17, 0), rows = rows).high)
    }

    @Test
    fun `today high never comes from another site`() {
        val rows = listOf(row(NWS, high = 64.4f), row(NWS, high = 90f, lat = 38.5, lon = -121.5))
        assertEquals(64.4f, resolve(oct13, oct13.atTime(17, 0), rows = rows).high)
    }

    @Test
    fun `today low stays on the source row at five pm`() {
        val rows = listOf(row(NWS, low = 47.9f), row("SILURIAN", low = 47.6f))
        assertEquals(47.9f, resolve(oct13, oct13.atTime(17, 0), rows = rows).low)
    }

    @Test
    fun `past day uses the nearest fragment of the source whatever the row order`() {
        val far = row(NWS, high = 60f, lat = 37.418)
        val near = row(NWS, high = 71f)
        val other = row("SILURIAN", high = 90f)
        val yesterday = oct13.minusDays(1)
        assertEquals(71f, resolve(yesterday, oct13.atTime(10, 0), rows = listOf(far, other, near)).high)
        assertEquals(71f, resolve(yesterday, oct13.atTime(10, 0), rows = listOf(near, other, far)).high)
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
