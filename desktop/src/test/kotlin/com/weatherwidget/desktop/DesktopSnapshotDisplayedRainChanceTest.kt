package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopObservationEntity
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/**
 * Regression for the night rain chance bug (2026-07-04): NWS's raw "Tonight" period chance
 * excludes 6-8am rain that the app's 8pm-8am night window counts as part of tonight.
 * [DesktopWeatherRepository.snapshotDisplayedRainChance] must persist the resolved (window-max)
 * value into daily_history, and [DesktopWeatherRepository.recomputeDailyExtremes] must never
 * clobber that snapshot on a later actuals recompute (full-row REPLACE in upsertDailyHistory).
 */
class DesktopSnapshotDisplayedRainChanceTest {
    private lateinit var tempDbPath: Path
    private lateinit var db: DesktopWeatherDatabase
    private lateinit var dao: DesktopWeatherDao
    private lateinit var repository: DesktopWeatherRepository

    private val lat = 37.42
    private val lon = -122.08
    private val source = "NWS"
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()

    @Before
    fun setup() {
        tempDbPath = Files.createTempFile("weather-snapshot-test", ".db")
        db = DesktopWeatherDatabase(tempDbPath).apply { initialize() }
        dao = DesktopWeatherDao(db)
        val dummyService = DesktopWeatherService(lat, lon, source)
        repository = DesktopWeatherRepository(dummyService, dao, lat, lon, source)
    }

    @After
    fun teardown() {
        db.getConnection().close()
        Files.deleteIfExists(tempDbPath)
    }

    @Test
    fun `snapshot stores hourly window max night chance over NWS raw period field`() {
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = today.toString(), highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    source = source, daytimePrecipProbability = 0, nighttimePrecipProbability = 9,
                ),
            ),
        )
        dao.upsertHourlyForecasts(
            lat, lon, source,
            listOf(
                HourlyForecast(today.plusDays(1).atTime(5, 0).atZone(zone).toInstant().toEpochMilli(), 50f, "Rain", precipProbability = 9),
                HourlyForecast(today.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(), 52f, "Rain", precipProbability = 14),
            ),
        )
        // The actuals path must already have written a daily_history row for the snapshot to attach to.
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = today.toEpochDay() * 86_400_000L, source = source,
                    locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(today.toEpochDay() * 86_400_000L, today.toEpochDay() * 86_400_000L, lat, lon)
            .first { it.source == source }
        assertEquals(14, stored.forecastNightPrecipChance)
    }

    @Test
    fun `snapshot does nothing when no daily_history row exists yet`() {
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = today.toString(), highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    source = source, daytimePrecipProbability = 5, nighttimePrecipProbability = 9,
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(today.toEpochDay() * 86_400_000L, today.toEpochDay() * 86_400_000L, lat, lon)
        assertNull(stored.firstOrNull { it.source == source })
    }

    @Test
    fun `closed day window is never overwritten even when live hourly data has since drifted`() {
        val yesterday = today.minusDays(1)
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = yesterday.toString(), highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    source = source, daytimePrecipProbability = 40, nighttimePrecipProbability = 0,
                ),
            ),
        )
        dao.upsertHourlyForecasts(
            lat, lon, source,
            listOf(
                HourlyForecast(yesterday.atTime(14, 0).atZone(zone).toInstant().toEpochMilli(), 60f, "Rain", precipProbability = 40),
            ),
        )
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = yesterday.toEpochDay() * 86_400_000L, source = source,
                    locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    forecastDayPrecipChance = 5, // archived while yesterday was still live
                    forecastNightPrecipChance = 0,
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(yesterday.toEpochDay() * 86_400_000L, yesterday.toEpochDay() * 86_400_000L, lat, lon)
            .first { it.source == source }
        assertEquals("Closed day window must not be overwritten by drifted hourly data", 5, stored.forecastDayPrecipChance)
    }

    @Test
    fun `snapshot freezes forecast overlay and noon cloud for today`() {
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = today.toString(), highTemp = 80f, lowTemp = 55f, condition = "Clear",
                    source = source, precipAmountMm = 1.5f,
                ),
            ),
        )
        dao.upsertHourlyForecasts(
            lat, lon, source,
            listOf(
                HourlyForecast(today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), 70f, "Clear", cloudCover = 60),
            ),
        )
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = today.toEpochDay() * 86_400_000L, source = source,
                    locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(today.toEpochDay() * 86_400_000L, today.toEpochDay() * 86_400_000L, lat, lon)
            .first { it.source == source }
        assertEquals(80f, stored.forecastHighTemp!!, 0.01f)
        assertEquals(55f, stored.forecastLowTemp!!, 0.01f)
        assertEquals(1.5f, stored.forecastPrecipAmountMm!!, 0.01f)
        assertEquals(60, stored.noonCloudPercent)
    }

    @Test
    fun `degenerate forecast row does not clobber frozen overlay`() {
        // A collapsed high==low row (NWS evening degeneration) must not replace the values a
        // complete batch froze earlier in the day; missing hourly noon data must not erase the
        // frozen noon cloud either.
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = today.toString(), highTemp = 62f, lowTemp = 62f, condition = "Clear",
                    source = source,
                ),
            ),
        )
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = today.toEpochDay() * 86_400_000L, source = source,
                    locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    forecastHighTemp = 75f, forecastLowTemp = 50f,
                    forecastPrecipAmountMm = 2f, noonCloudPercent = 30,
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(today.toEpochDay() * 86_400_000L, today.toEpochDay() * 86_400_000L, lat, lon)
            .first { it.source == source }
        assertEquals(75f, stored.forecastHighTemp!!, 0.01f)
        assertEquals(50f, stored.forecastLowTemp!!, 0.01f)
        assertEquals(2f, stored.forecastPrecipAmountMm!!, 0.01f)
        assertEquals(30, stored.noonCloudPercent)
    }

    @Test
    fun `closed overlay window is never overwritten by a later forecast`() {
        val yesterday = today.minusDays(1)
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = yesterday.toString(), highTemp = 80f, lowTemp = 60f, condition = "Clear",
                    source = source,
                ),
            ),
        )
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = yesterday.toEpochDay() * 86_400_000L, source = source,
                    locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    forecastHighTemp = 75f, forecastLowTemp = 50f, // archived while yesterday was live
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(yesterday.toEpochDay() * 86_400_000L, yesterday.toEpochDay() * 86_400_000L, lat, lon)
            .first { it.source == source }
        assertEquals("Closed overlay window must keep the archived high", 75f, stored.forecastHighTemp!!, 0.01f)
        assertEquals("Closed overlay window must keep the archived low", 50f, stored.forecastLowTemp!!, 0.01f)
    }

    @Test
    fun `backfill fills frozen overlay and noon cloud from retained tables`() {
        val past = today.minusDays(3)
        val pastStart = past.toEpochDay() * 86_400_000L
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = past.toString(), highTemp = 71f, lowTemp = 53f, condition = "Clear",
                    source = source, precipAmountMm = 0.5f,
                ),
            ),
        )
        // getDailyForecastSnapshots excludes the newest batch (it's the live forecast, not a
        // snapshot). In production a past date's batches are never the newest overall; recreate
        // that by adding a fresher today-batch.
        Thread.sleep(5)
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = today.toString(), highTemp = 80f, lowTemp = 60f, condition = "Clear",
                    source = source,
                ),
            ),
        )
        dao.upsertHourlyForecastHistory(
            lat, lon, source, timestampToGroupPredictions = 0L,
            listOf(
                HourlyForecast(past.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), 70f, "Cloudy", cloudCover = 45),
            ),
        )
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = pastStart, source = source,
                    locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )

        repository.backfillFrozenDisplayColumnsIfNeeded(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(pastStart, pastStart, lat, lon).first { it.source == source }
        assertEquals(71f, stored.forecastHighTemp!!, 0.01f)
        assertEquals(53f, stored.forecastLowTemp!!, 0.01f)
        assertEquals(0.5f, stored.forecastPrecipAmountMm!!, 0.01f)
        assertEquals(45, stored.noonCloudPercent)

        // Second run is a no-op (one-shot marker), even for still-null rows.
        repository.backfillFrozenDisplayColumnsIfNeeded(System.currentTimeMillis())
    }

    @Test
    fun `backfill fills missing overlay without touching an already-frozen noon cloud`() {
        // First post-migration fetch order: the live writer freezes yesterday's noon cloud (its
        // window is still open) before the backfill scans — the backfill must still fill the
        // overlay for that row instead of skipping it, and must not clobber the frozen noon cloud.
        val past = today.minusDays(2)
        val pastStart = past.toEpochDay() * 86_400_000L
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = past.toString(), highTemp = 79f, lowTemp = 57f, condition = "Clear",
                    source = source,
                ),
            ),
        )
        Thread.sleep(5)
        dao.upsertForecasts(
            lat, lon, source,
            listOf(
                DailyForecast(
                    date = today.toString(), highTemp = 80f, lowTemp = 60f, condition = "Clear",
                    source = source,
                ),
            ),
        )
        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = pastStart, source = source,
                    locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    noonCloudPercent = 19, // frozen live; overlay window had already closed
                ),
            ),
        )

        repository.backfillFrozenDisplayColumnsIfNeeded(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(pastStart, pastStart, lat, lon).first { it.source == source }
        assertEquals(79f, stored.forecastHighTemp!!, 0.01f)
        assertEquals(57f, stored.forecastLowTemp!!, 0.01f)
        assertEquals("Frozen noon cloud must survive the backfill", 19, stored.noonCloudPercent)
    }

    @Test
    fun `recompute preserves existing forecast chance snapshot when temps change`() {
        val t10 = today.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        val todayStart = today.toEpochDay() * 86_400_000L

        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = todayStart, source = source, locationLat = lat, locationLon = lon,
                    highTemp = 999f, lowTemp = 999f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    forecastDayPrecipChance = 2, forecastNightPrecipChance = 14,
                    forecastHighTemp = 75f, forecastLowTemp = 50f,
                    forecastPrecipAmountMm = 2.5f, noonCloudPercent = 60,
                ),
            ),
        )
        dao.upsertObservations(
            listOf(
                DesktopObservationEntity(
                    stationId = "KNEAR", stationName = "Near", timestamp = t10,
                    temperature = 70f, condition = "Clear",
                    locationLat = lat, locationLon = lon, distanceKm = 1f,
                    stationType = "OFFICIAL", fetchedAt = t10, api = source,
                ),
            ),
        )

        repository.recomputeDailyExtremes(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(todayStart, todayStart, lat, lon).first { it.source == source }
        assertEquals("Recompute should have changed the high temp", 70f, stored.highTemp, 0.1f)
        assertEquals("Chance snapshot must survive the actuals REPLACE", 2, stored.forecastDayPrecipChance)
        assertEquals("Chance snapshot must survive the actuals REPLACE", 14, stored.forecastNightPrecipChance)
        assertEquals("Frozen overlay must survive the actuals REPLACE", 75f, stored.forecastHighTemp!!, 0.01f)
        assertEquals("Frozen overlay must survive the actuals REPLACE", 50f, stored.forecastLowTemp!!, 0.01f)
        assertEquals("Frozen amount must survive the actuals REPLACE", 2.5f, stored.forecastPrecipAmountMm!!, 0.01f)
        assertEquals("Frozen noon cloud must survive the actuals REPLACE", 60, stored.noonCloudPercent)
    }
}
