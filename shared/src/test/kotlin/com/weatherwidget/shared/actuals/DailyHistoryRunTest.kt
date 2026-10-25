package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId

/**
 * [DailyHistoryRun] against an in-memory Host. Each case pins a behaviour the desktop glue had
 * diverged on before the run was shared (plans/261021-android-desktop-shared-code-audit.md §2);
 * the platform hosts are covered end to end by `:app` `ForecastOnlyHistoryRowsTest` /
 * `SettlePastForecastOverlaysTest` and `:desktop` `DesktopForecastOnlyHistoryRowsTest` /
 * `DesktopSettleForecastOverlayTest` / `DesktopSnapshotDisplayedRainChanceTest`.
 */
@Category(ShortDuration::class)
class DailyHistoryRunTest {
    private val lat = 37.42
    private val lon = -122.08
    private val pacific: ZoneId = ZoneId.of("America/Los_Angeles")
    private val today: LocalDate = LocalDate.of(2026, 10, 21)
    private val yesterday: LocalDate = today.minusDays(1)

    private fun utc(date: LocalDate) = DailyHistoryRun.utcMs(date)
    private fun local(date: LocalDate, hour: Int) = date.atTime(hour, 0).atZone(pacific).toInstant().toEpochMilli()

    private class FakeHost : DailyHistoryRun.Host {
        val history = mutableListOf<DailyHistory>()
        val forecasts = mutableListOf<DailyHistoryMaintenance.ForecastHistoryRow>()
        val hourly = mutableListOf<HourlyForecast>()
        val inserted = mutableListOf<DailyHistory>()
        val overlayUpdates = mutableListOf<DailyHistory>()
        val priorUpdates = mutableListOf<DailyHistory>()
        val done = mutableSetOf<DailyHistoryRun.Marker>()

        override suspend fun dailyHistory(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
            history.filter { it.date in startMs..endMs }
        override suspend fun forecastFetches(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
            forecasts.filter { it.dateMs in startMs..endMs }
        override suspend fun hourlyForecasts(startMs: Long, endMs: Long, latitude: Double, longitude: Double) =
            hourly.filter { it.dateTime in startMs..endMs }
        override suspend fun hourlyHistory(startMs: Long, endMs: Long, latitude: Double, longitude: Double, source: String) =
            emptyList<HourlyForecast>()
        override suspend fun insertDailyHistory(rows: List<DailyHistory>) { inserted += rows }
        override suspend fun updateForecastOverlay(row: DailyHistory) { overlayUpdates += row }
        override suspend fun updatePriorForecast(row: DailyHistory) { priorUpdates += row }
        override suspend fun isDone(marker: DailyHistoryRun.Marker) = marker in done
        override suspend fun markDone(marker: DailyHistoryRun.Marker, summary: String) { done += marker }
        var lastUpkeep = 0L
        override suspend fun lastUpkeepMs() = lastUpkeep
        override suspend fun markUpkeep(nowMs: Long) { lastUpkeep = nowMs }
        override suspend fun log(tag: String, message: String, level: String) {}
    }

    private fun forecast(
        date: LocalDate,
        source: String,
        high: Float = 70f,
        low: Float = 55f,
        fetchedAt: Long,
        lastConfirmedAt: Long = fetchedAt,
        nightChance: Int? = null,
    ) = DailyHistoryMaintenance.ForecastHistoryRow(
        dateMs = utc(date), source = source, locationLat = lat, locationLon = lon,
        highTemp = high, lowTemp = low, precipAmountMm = null, condition = "Cloudy",
        fetchedAt = fetchedAt, isClimateNormal = false,
        nighttimePrecipProbability = nightChance, lastConfirmedAt = lastConfirmedAt,
    )

    private fun history(date: LocalDate, source: String) = DailyHistory(
        date = utc(date), source = source, locationLat = lat, locationLon = lon,
        computedHighTemp = 71f, computedLowTemp = 54f, condition = "Cloudy", updatedAt = 0L,
    )

    @Test
    fun `forecast-only rows cover every source at the site, not one`() = runBlocking {
        // Desktop read only its repository's source; a second source got no rows there.
        val host = FakeHost()
        val day = today.minusDays(3)
        host.forecasts += forecast(day, "NWS", fetchedAt = local(day.minusDays(1), 9))
        host.forecasts += forecast(day, "OPEN_METEO", fetchedAt = local(day.minusDays(1), 9))

        DailyHistoryRun(host) { pacific }.ensureForecastOnlyHistoryRows(lat, lon, local(today, 12))

        assertEquals(setOf("NWS", "OPEN_METEO"), host.inserted.map { it.source }.toSet())
    }

    @Test
    fun `just after midnight west of UTC the freeze still sees yesterday's UTC-keyed row`() = runBlocking {
        // Desktop bounded this read by local midnight: in PDT that starts at 07:00Z and missed
        // yesterday's row (keyed 00:00Z) while its night window (until 08:00 today) was still open.
        val host = FakeHost()
        host.history += history(yesterday, "NWS")
        host.forecasts += forecast(yesterday, "NWS", fetchedAt = local(yesterday, 6), nightChance = 60)
        (20..23).map { local(yesterday, it) }.plus((0..7).map { local(today, it) }).forEach {
            host.hourly += HourlyForecast(
                dateTime = it, temperature = 55f, condition = "Rain", source = "NWS",
                precipProbability = 60, fetchedAt = local(yesterday, 6), locationLat = lat, locationLon = lon,
            )
        }

        DailyHistoryRun(host) { pacific }.snapshotDisplayedRainChance(lat, lon, local(today, 1))

        val frozen = host.inserted.single { it.date == utc(yesterday) }
        assertEquals(60, frozen.forecastNightPrecipChance)
    }

    @Test
    fun `settle and prior-forecast writes are field-limited, never a full-row replace`() = runBlocking {
        // Desktop upserted whole rows here, which could drop a blend write made in between.
        val host = FakeHost()
        val day = today.minusDays(2)
        host.history += history(day, "NWS").copy(computedHighAt = local(day, 15), computedLowAt = local(day, 6))
        host.forecasts += forecast(day, "NWS", high = 72f, low = 53f, fetchedAt = local(day.minusDays(1), 5))

        DailyHistoryRun(host) { pacific }.settlePastForecastOverlays(lat, lon, local(today, 12))

        assertTrue(host.overlayUpdates.isNotEmpty() || host.priorUpdates.isNotEmpty())
        assertTrue(host.inserted.isEmpty())
    }

    @Test
    fun `newest per day and source is the freshest batch`() {
        val day = today.minusDays(1)
        val older = forecast(day, "NWS", high = 60f, fetchedAt = 2_000L, lastConfirmedAt = 1_000L)
        val newer = forecast(day, "NWS", high = 65f, fetchedAt = 1_500L, lastConfirmedAt = 1_500L)

        val picked = DailyHistoryRun.newestPerDaySource(listOf(older, newer), lat, lon)

        assertEquals(listOf(65f), picked.map { it.highTemp })
    }

    @Test
    fun `month-wide upkeep runs only on a charger, once a day`() = runBlocking {
        // User, 2026-10-21: desktop ran it on every source's refresh; it reads a month of fetches.
        val host = FakeHost()
        val day = today.minusDays(3)
        host.forecasts += forecast(day, "NWS", fetchedAt = local(day.minusDays(1), 9))
        val run = DailyHistoryRun(host) { pacific }

        assertEquals(false, run.upkeepIfDue(lat, lon, local(today, 12), isCharging = false))
        assertTrue(host.inserted.isEmpty())
        assertEquals(true, run.upkeepIfDue(lat, lon, local(today, 12), isCharging = true))
        assertEquals(listOf(utc(day)), host.inserted.map { it.date })
        assertEquals(false, run.upkeepIfDue(lat, lon, local(today, 23), isCharging = true))
        assertEquals(true, run.upkeepIfDue(lat, lon, local(today.plusDays(1), 12), isCharging = true))
    }

    @Test
    fun `after a fetch only yesterday's forecast-only row is made, for the freeze to update`() = runBlocking {
        val host = FakeHost()
        host.forecasts += forecast(yesterday, "OPEN_METEO", fetchedAt = local(yesterday, 6))
        host.forecasts += forecast(today.minusDays(3), "OPEN_METEO", fetchedAt = local(today.minusDays(4), 6))

        DailyHistoryRun(host) { pacific }.afterFetch(lat, lon, local(today, 1))

        assertEquals(listOf(utc(yesterday)), host.inserted.filter { it.lastWriter == DailyHistoryWriter.FORECAST_ONLY_ROW.storedValue }.map { it.date })
    }

    @Test
    fun `one-time backfills mark done even when nothing needs them`() = runBlocking {
        val host = FakeHost()
        val run = DailyHistoryRun(host) { pacific }
        run.backfillForecastChanceSnapshotsIfNeeded(lat, lon, local(today, 12))
        run.backfillFrozenDisplayColumnsIfNeeded(lat, lon, local(today, 12))
        assertEquals(DailyHistoryRun.Marker.entries.toSet(), host.done)
    }
}
