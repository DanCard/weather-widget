package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Android's half of the shared today-row rule (`PartialForecastDays.todayRow`, also desktop's
 * `DesktopWeatherDao.getDailyForecasts`). The rule is tested in :shared; this pins the Android
 * filtering: `forecastSnapshots[today]` holds every source's stored rows (and GENERIC_GAP
 * filler), so only the display source at the batch row's site may stand in.
 * plans/261004-desktop-today-column-climate-normal-when-batch-lacks-today.md.
 */
@Category(ShortDuration::class)
class DailyTodayResolverTodayRowTest {
    private val today = 1_791_072_000_000L // 2026-10-04 UTC midnight

    private fun row(
        source: String,
        high: Float?,
        low: Float?,
        fetchedAt: Long,
        lat: Double = 37.417,
        lon: Double = -122.089,
    ) = ForecastEntity(
        targetDate = today,
        dateOfPrediction = today,
        locationLat = lat,
        locationLon = lon,
        highTemp = high,
        lowTemp = low,
        condition = "Sunny",
        source = source,
        fetchedAt = fetchedAt,
        batchFetchedAt = fetchedAt,
    )

    @Test
    fun `missing batch row - newest complete row of the display source stands in`() {
        val silurianMorning = row("SILURIAN", 90.1f, 67.4f, fetchedAt = 4)
        val stored = listOf(
            silurianMorning,
            row("SILURIAN", 89.6f, null, fetchedAt = 14),
            row("NWS", 92f, 68f, fetchedAt = 20), // newer and complete, but another source
            row("GENERIC_GAP", 76.2f, 56.8f, fetchedAt = 30), // the climate normal
        )
        assertSame(silurianMorning, DailyTodayResolver.resolveTodayRow(null, stored, "SILURIAN"))
    }

    @Test
    fun `partial batch row - only rows at its site may replace it`() {
        val batch = row("NWS", 92f, null, fetchedAt = 20)
        val sameSite = row("NWS", 91f, 62f, fetchedAt = 5)
        val otherSite = row("NWS", 70f, 50f, fetchedAt = 10, lat = 52.233, lon = 21.071) // Warsaw
        assertSame(sameSite, DailyTodayResolver.resolveTodayRow(batch, listOf(sameSite, otherSite), "NWS"))
    }

    @Test
    fun `complete batch row stands`() {
        val batch = row("NWS", 92f, 68f, fetchedAt = 20)
        assertSame(batch, DailyTodayResolver.resolveTodayRow(batch, listOf(row("NWS", 91f, 62f, 30)), "NWS"))
    }

    @Test
    fun `nothing of the display source - no row, so the column renders without a forecast`() {
        assertNull(DailyTodayResolver.resolveTodayRow(null, listOf(row("NWS", 92f, 68f, 20)), "SILURIAN"))
    }
}
