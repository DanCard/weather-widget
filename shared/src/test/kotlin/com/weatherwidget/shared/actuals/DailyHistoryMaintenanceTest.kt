package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * Locks the shared daily-history maintenance planning rules that Android and desktop both consume.
 *
 * The integration paths are covered by `:app` `ForecastOnlyHistoryRowsTest` and `:desktop`
 * `DesktopSnapshotDisplayedRainChanceTest` / `DesktopBackfillChanceSnapshotTest` /
 * `DesktopForecastOnlyHistoryRowsTest`; these tests pin the planner decisions that previously
 * diverged between the two platforms (degenerate high==low overlay, `lastWriter` stamping).
 */
@Category(ShortDuration::class)
class DailyHistoryMaintenanceTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val today: LocalDate = LocalDate.of(2026, 9, 9)
    private val todayMs: Long = today.toEpochDay() * 86_400_000L
    private val yesterday: LocalDate = today.minusDays(1)
    private val yesterdayMs: Long = yesterday.toEpochDay() * 86_400_000L
    private val nowMs: Long = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val lat = 37.42
    private val lon = -122.08

    private fun forecastRow(
        dateMs: Long = yesterdayMs,
        source: String = "NWS",
        high: Float? = 73f,
        low: Float? = 58f,
        isClimateNormal: Boolean = false,
        fetchedAt: Long = 1_000L,
    ) = DailyHistoryMaintenance.ForecastHistoryRow(
        dateMs = dateMs,
        source = source,
        locationLat = lat,
        locationLon = lon,
        highTemp = high,
        lowTemp = low,
        precipAmountMm = 1.5f,
        condition = "Clear",
        fetchedAt = fetchedAt,
        isClimateNormal = isClimateNormal,
        precipProbability = 20,
        daytimePrecipProbability = 20,
        nighttimePrecipProbability = 10,
    )

    private fun historyRow(
        dateMs: Long = yesterdayMs,
        source: String = "NWS",
        forecastHigh: Float? = null,
        forecastLow: Float? = null,
        noonCloud: Int? = null,
    ) = DailyHistory(
        date = dateMs,
        source = source,
        locationLat = lat,
        locationLon = lon,
        computedHighTemp = 70f,
        computedLowTemp = 55f,
        condition = "Clear",
        updatedAt = nowMs,
        forecastHighTemp = forecastHigh,
        forecastLowTemp = forecastLow,
        noonCloudPercent = noonCloud,
    )

    private fun hourly(date: LocalDate, hour: Int, precip: Int?, temp: Float = 60f) = HourlyForecast(
        dateTime = date.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(),
        temperature = temp,
        condition = "Rain",
        precipProbability = precip,
        source = "NWS",
    )

    @Test
    fun `forecast-only rows keep computed actuals null and stamp the writer`() {
        val rows = DailyHistoryMaintenance.planForecastOnlyRows(
            forecastRows = listOf(forecastRow()),
            existingKeys = emptySet(),
            todayMs = todayMs,
            nowMs = nowMs,
        )
        assertEquals(1, rows.size)
        assertNull(rows[0].computedHighTemp)
        assertNull(rows[0].computedLowTemp)
        assertEquals(DailyHistoryWriter.FORECAST_ONLY_ROW.storedValue, rows[0].lastWriter)
        assertEquals(73f, rows[0].forecastHighTemp)
    }

    @Test
    fun `forecast-only rows skip days already present`() {
        val rows = DailyHistoryMaintenance.planForecastOnlyRows(
            forecastRows = listOf(forecastRow()),
            existingKeys = setOf(yesterdayMs to "NWS"),
            todayMs = todayMs,
            nowMs = nowMs,
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `snapshot stamps forecast-freeze writer and reports the chance change`() {
        // Yesterday noon: yesterday's day window is still open, so the planner processes the row.
        val snapshotNowMs = yesterday.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val plan = DailyHistoryMaintenance.planSnapshotDisplayedRainChance(
            dailyRows = listOf(forecastRow()),
            hourly = listOf(hourly(yesterday, 13, 42)),
            existing = listOf(historyRow()),
            centerLat = lat,
            centerLon = lon,
            nowMs = snapshotNowMs,
            zoneId = zone,
        )
        assertEquals(1, plan.rows.size)
        assertEquals(DailyHistoryWriter.FORECAST_FREEZE.storedValue, plan.rows[0].lastWriter)
        assertEquals(42, plan.rows[0].forecastDayPrecipChance)
        assertEquals(1, plan.traces.size)
        assertEquals(1, plan.chanceChangeLogs.size)
    }

    @Test
    fun `snapshot leaves closed windows untouched`() {
        // Noon the day after the forecast day: both of its windows are closed.
        val lateMs = yesterday.plusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val plan = DailyHistoryMaintenance.planSnapshotDisplayedRainChance(
            dailyRows = listOf(forecastRow()),
            hourly = listOf(hourly(yesterday, 13, 42)),
            existing = listOf(historyRow()),
            centerLat = lat,
            centerLon = lon,
            nowMs = lateMs,
            zoneId = zone,
        )
        assertTrue(plan.rows.isEmpty())
    }

    @Test
    fun `frozen-display backfill rejects degenerate and climate-normal overlays`() {
        val rows = runBlockingPlan {
            DailyHistoryMaintenance.planFrozenDisplayBackfill(
                rowsNeedingBackfill = listOf(historyRow()),
                snapshots = listOf(
                    // climate-normal and degenerate rows must never become the frozen overlay
                    forecastRow(high = 90f, low = 90f, fetchedAt = 9_000L),
                    forecastRow(high = 80f, low = 60f, isClimateNormal = true, fetchedAt = 9_500L),
                    forecastRow(high = 73f, low = 58f, fetchedAt = 1_000L),
                ),
                historyFor = { _, _ -> emptyList() },
                centerLat = lat,
                centerLon = lon,
                nowMs = nowMs,
                zoneId = zone,
            )
        }
        assertEquals(1, rows.size)
        assertEquals(73f, rows[0].forecastHighTemp)
        assertEquals(58f, rows[0].forecastLowTemp)
    }

    @Test
    fun `frozen-display backfill fills only missing columns`() {
        val rows = runBlockingPlan {
            DailyHistoryMaintenance.planFrozenDisplayBackfill(
                rowsNeedingBackfill = listOf(historyRow(forecastHigh = 70f, forecastLow = 50f)),
                snapshots = listOf(forecastRow(high = 73f, low = 58f)),
                historyFor = { _, _ -> emptyList() },
                centerLat = lat,
                centerLon = lon,
                nowMs = nowMs,
                zoneId = zone,
            )
        }
        assertEquals(1, rows.size)
        // Existing overlay preserved; noon cloud still null and not invented.
        assertEquals(70f, rows[0].forecastHighTemp)
        assertEquals(50f, rows[0].forecastLowTemp)
        assertNull(rows[0].noonCloudPercent)
    }

    @Test
    fun `chance backfill stitches history and stamps the writer`() {
        val rows = runBlockingPlan {
            DailyHistoryMaintenance.planChanceBackfill(
                rowsNeedingBackfill = listOf(historyRow()),
                historyFor = { _, _ -> listOf(hourly(yesterday, 13, 33), hourly(yesterday, 21, 55)) },
                centerLat = lat,
                centerLon = lon,
                nowMs = nowMs,
                zoneId = zone,
            )
        }
        assertEquals(1, rows.size)
        assertEquals(33, rows[0].forecastDayPrecipChance)
        assertEquals(55, rows[0].forecastNightPrecipChance)
        assertEquals(DailyHistoryWriter.FORECAST_FREEZE.storedValue, rows[0].lastWriter)
    }

    /** Runs a suspend planner synchronously; the planners only suspend to call the DAO lambda. */
    private fun <T> runBlockingPlan(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }
}
