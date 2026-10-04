package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * A past day's forecast overlay is the last forecast fetched before each extreme happened.
 * Numbers are the Mountain View Open-Meteo fetches for 2026-10-03 (desktop `forecasts` table); the
 * high was reached ~16:15 and the low ~05:15. See plans/261004-forecast-overlay-frozen-at-extreme-time.md.
 */
@Category(ShortDuration::class)
class ForecastOverlaySettleTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 10, 3)
    private val dateMs = day.toEpochDay() * 86_400_000L
    private val todayMs = day.plusDays(1).toEpochDay() * 86_400_000L
    private val lat = 37.4166
    private val lon = -122.0889

    private fun at(hour: Int, minute: Int = 0, plusDays: Long = 0) =
        day.plusDays(plusDays).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun fc(
        fetchedAt: Long,
        high: Float?,
        low: Float?,
        source: String = "OPEN_METEO",
        climate: Boolean = false,
        siteLat: Double = lat,
        date: Long = dateMs,
    ) = DailyHistoryMaintenance.ForecastHistoryRow(
        dateMs = date, source = source, locationLat = siteLat, locationLon = lon,
        highTemp = high, lowTemp = low, precipAmountMm = null, condition = "Clear",
        fetchedAt = fetchedAt, isClimateNormal = climate,
    )

    private fun history(
        highAt: Long? = at(16, 15),
        lowAt: Long? = at(5, 15),
        frozenHigh: Float? = 89f,
        frozenLow: Float? = 58f,
        source: String = "OPEN_METEO",
        date: Long = dateMs,
    ) = DailyHistory(
        date = date, source = source, locationLat = lat, locationLon = lon,
        computedHighTemp = 91.6f, computedLowTemp = 59.0f, condition = "Clear", updatedAt = 0L,
        forecastHighTemp = frozenHigh, forecastLowTemp = frozenLow,
        computedHighAt = highAt, computedLowAt = lowAt,
    )

    /** The day's real fetch sequence, ending with the 22:25 hindcast that is stored today. */
    private val oct3 = listOf(
        fc(at(2, 48), 90.0f, 59.9f),
        fc(at(4, 48), 90.0f, 58.3f),
        fc(at(6, 21), 90.3f, 59.3f),
        fc(at(10, 35), 91.3f, 58.1f),
        fc(at(15, 47), 88.9f, 58.1f),
        fc(at(16, 40), 90.1f, 58.1f),
        fc(at(22, 25), 89.0f, 58.0f),
    )

    @Test
    fun `oct 3 settles to the last fetch before each extreme, not the evening hindcast`() {
        val settled = ForecastOverlaySettle.settle(history(), oct3, todayMs)!!
        assertEquals(88.9f, settled.row.forecastHighTemp) // 15:47, before the 16:15 high
        assertEquals(58.3f, settled.row.forecastLowTemp) // 04:48, before the 05:15 low
        assertEquals(at(15, 47), settled.highFetchedAt)
        assertEquals(at(4, 48), settled.lowFetchedAt)
    }

    @Test
    fun `a fetch at the very instant the extreme is reached still counts`() {
        val settled = ForecastOverlaySettle.settle(history(highAt = at(16, 40)), oct3, todayMs)!!
        assertEquals(90.1f, settled.row.forecastHighTemp)
    }

    @Test
    fun `validity is per field - a later batch without a low still serves the high`() {
        // NWS drops the low from batches after it has passed.
        val nws = listOf(fc(at(4, 0), 88f, 57f, "NWS"), fc(at(14, 0), 91f, null, "NWS"))
        val settled = ForecastOverlaySettle.settle(history(source = "NWS"), nws, todayMs)!!
        assertEquals(91f, settled.row.forecastHighTemp)
        assertEquals(57f, settled.row.forecastLowTemp)
    }

    @Test
    fun `degenerate, climate-normal and generic-gap rows never qualify`() {
        val rows = listOf(
            fc(at(9, 0), 87f, 57f),
            fc(at(12, 0), 70f, 70f), // degenerate high == low
            fc(at(13, 0), 75f, 55f, climate = true),
            fc(at(14, 0), 99f, 40f, source = "GENERIC_GAP"),
        )
        val settled = ForecastOverlaySettle.settle(history(lowAt = at(23, 0)), rows, todayMs)!!
        assertEquals(87f, settled.row.forecastHighTemp)
        assertEquals(57f, settled.row.forecastLowTemp)
    }

    @Test
    fun `another site's forecast for the same day is ignored`() {
        val lviv = listOf(fc(at(10, 0), 66f, 49f, siteLat = 49.842))
        assertNull(ForecastOverlaySettle.settle(history(), lviv, todayMs))
    }

    @Test
    fun `no fetch before the extreme keeps the existing value for that side`() {
        // First fetched at this site after the high: the high keeps its frozen value.
        val late = listOf(fc(at(3, 0), 92f, 60f), fc(at(17, 0), 90f, 61f))
        val settled = ForecastOverlaySettle.settle(history(), late, todayMs)!!
        assertEquals(92f, settled.row.forecastHighTemp) // 03:00 is before 16:15
        assertEquals(60f, settled.row.forecastLowTemp)

        val onlyAfter = listOf(fc(at(17, 0), 90f, 61f))
        assertNull(ForecastOverlaySettle.settle(history(), onlyAfter, todayMs))
    }

    @Test
    fun `unknown extreme times and today are left alone`() {
        assertNull(ForecastOverlaySettle.settle(history(highAt = null, lowAt = null), oct3, todayMs))
        val todayRow = history(date = todayMs)
        assertNull(ForecastOverlaySettle.settle(todayRow, oct3.map { it.copy(dateMs = todayMs) }, todayMs))
    }

    @Test
    fun `already settled rows produce no write`() {
        val settled = ForecastOverlaySettle.settle(history(), oct3, todayMs)!!.row
        assertNull(ForecastOverlaySettle.settle(settled, oct3, todayMs))
    }

    @Test
    fun `extreme reached is the first point within half a degree`() {
        data class P(val t: Long, val v: Float)
        val series = listOf(P(1, 59.8f), P(2, 90.8f), P(3, 91.2f), P(4, 91.4f), P(5, 85f), P(6, 59.6f), P(7, 59.4f))
        fun reached(target: Float, high: Boolean) =
            ForecastOverlaySettle.firstReachedAt(series, { it.t }, { it.v }, target, isHigh = high)
        assertEquals(3L, reached(91.4f, true)) // 91.2 is within 0.5; 90.8 is not
        assertEquals(1L, reached(59.4f, false)) // 59.8 at the start is already within 0.5 of the low
        assertEquals(null, ForecastOverlaySettle.firstReachedAt(emptyList<P>(), { it.t }, { it.v }, 1f, true))
    }

    @Test
    fun `plan logs one line per settled row`() {
        val plan = DailyHistoryMaintenance.planSettledForecastOverlays(oct3, listOf(history()), todayMs)
        assertEquals(1, plan.rows.size)
        assertEquals(1, plan.logs.size)
        assert(plan.logs.single().contains("high=89.0->88.9")) { plan.logs.single() }
    }
}
