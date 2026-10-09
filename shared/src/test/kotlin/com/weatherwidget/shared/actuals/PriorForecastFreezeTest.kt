package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DailyHistoryMaintenance.planPriorForecasts] freezes each day's "yesterday's forecast" — low from
 * the newest fetch before 06:00 the previous day, high from the newest before 16:00.
 */
@Category(ShortDuration::class)
class PriorForecastFreezeTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val day = LocalDate.of(2026, 10, 3)
    private val dateMs = day.toEpochDay() * 86_400_000L
    private val todayMs = day.plusDays(2).toEpochDay() * 86_400_000L
    private val lat = 37.4166
    private val lon = -122.0889

    private fun prev(h: Int, m: Int = 0) =
        day.minusDays(1).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

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
        priorHigh: Float? = null,
        priorLow: Float? = null,
        date: Long = dateMs,
        source: String = "OPEN_METEO",
    ) = DailyHistory(
        date = date, source = source, locationLat = lat, locationLon = lon,
        computedHighTemp = 91.6f, computedLowTemp = 59.0f, condition = "Clear", updatedAt = 7L,
        forecastHighTemp = 89f, forecastLowTemp = 58f,
        priorForecastHighTemp = priorHigh, priorForecastLowTemp = priorLow,
    )

    private val fetches = listOf(
        fc(prev(3), 88f, 57f),
        fc(prev(5, 30), 89f, 56f), // low anchor (before 06:00)
        fc(prev(12), 90f, 58f),
        fc(prev(15, 45), 92f, 59f), // high anchor (before 16:00)
        fc(prev(20), 95f, 61f), // after both anchors
    )

    private fun plan(rows: List<DailyHistoryMaintenance.ForecastHistoryRow>, existing: List<DailyHistory>) =
        DailyHistoryMaintenance.planPriorForecasts(rows, existing, todayMs, zone)

    @Test
    fun `freezes low and high from their own anchors and keeps every other column`() {
        val result = plan(fetches, listOf(history()))
        val row = result.rows.single()
        assertEquals(56f, row.priorForecastLowTemp)
        assertEquals(92f, row.priorForecastHighTemp)
        // Untouched columns survive (the platform writes may be full-row REPLACE).
        assertEquals(89f, row.forecastHighTemp)
        assertEquals(91.6f, row.computedHighTemp)
        assertEquals(7L, row.updatedAt)
        assertEquals(DailyHistoryWriter.FORECAST_FREEZE.storedValue, row.lastWriter)
        assertTrue(result.logs.single(), result.logs.single().contains("high=null->92.0"))
    }

    @Test
    fun `idempotent once frozen`() {
        val frozen = plan(fetches, listOf(history())).rows.single()
        assertTrue(plan(fetches, listOf(frozen)).rows.isEmpty())
    }

    @Test
    fun `a week-old fetch is not frozen, and a value frozen from one is cleared`() {
        // Pixel 2026-10-09: the site's only pre-anchor Open-Meteo fetch for Oct 9 was from Oct 2.
        val weekOld = fc(prev(11) - 6 * 86_400_000L, 90f, 74f)
        val sameDay = fc(prev(20), 69.5f, null)
        assertTrue(plan(listOf(weekOld, sameDay), listOf(history())).rows.isEmpty())

        val row = plan(listOf(weekOld, sameDay), listOf(history(priorHigh = 90f, priorLow = 74f))).rows.single()
        assertEquals(null, row.priorForecastHighTemp)
        assertEquals(null, row.priorForecastLowTemp)
    }

    @Test
    fun `a frozen value that matches no too-old fetch is kept`() {
        val weekOld = fc(prev(11) - 6 * 86_400_000L, 90f, 74f)
        val result = plan(listOf(weekOld), listOf(history(priorHigh = 92f, priorLow = 56f)))
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `never erases a frozen value when no fetch precedes the anchor`() {
        // Only post-anchor fetches remain (e.g. the earlier rows aged out).
        val result = plan(listOf(fc(prev(20), 95f, 61f)), listOf(history(priorHigh = 92f, priorLow = 56f)))
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `no fallback - a site first fetched after the anchors gets nothing`() {
        assertTrue(plan(listOf(fc(prev(20), 95f, 61f)), listOf(history())).rows.isEmpty())
    }

    @Test
    fun `scoped to date, source and site, excluding normals and gap fill`() {
        val noise = listOf(
            fc(prev(5), 70f, 40f, source = "NWS"),
            fc(prev(5), 71f, 41f, siteLat = lat + 0.5),
            fc(prev(5), 72f, 42f, date = dateMs - 86_400_000L),
            fc(prev(5, 50), 73f, 43f, climate = true),
            fc(prev(5, 55), 74f, 44f, source = WeatherSource.GENERIC_GAP.id),
        )
        val row = plan(fetches + noise, listOf(history())).rows.single()
        assertEquals(56f, row.priorForecastLowTemp)
        assertEquals(92f, row.priorForecastHighTemp)
    }

    @Test
    fun `today's row is frozen too, tomorrow's is not`() {
        val todayRow = history(date = todayMs)
        val todayDay = LocalDate.ofEpochDay(todayMs / 86_400_000L)
        val anchorFetch = todayDay.minusDays(1).atTime(5, 0).atZone(zone).toInstant().toEpochMilli()
        val rows = listOf(fc(anchorFetch, 80f, 50f, date = todayMs), fc(anchorFetch, 81f, 51f, date = todayMs + 86_400_000L))
        val result = plan(rows, listOf(todayRow, history(date = todayMs + 86_400_000L)))
        assertEquals(listOf(todayMs), result.rows.map { it.date })
    }
}
