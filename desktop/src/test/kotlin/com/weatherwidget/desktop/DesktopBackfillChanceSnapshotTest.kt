package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.local.desktop.DesktopWeatherDatabase
import com.weatherwidget.data.model.DailyHistory
import com.weatherwidget.data.model.HourlyForecast
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one-time backfill (post-upgrade) that fills forecastDayPrecipChance/forecastNightPrecipChance
 * for daily_history rows written before this feature existed, from the as-predicted
 * hourly_forecast_history archive (DesktopWeatherDao.getHourlyHistory already returns the freshest
 * snapshot per hour — never the live, REPLACE-overwritten hourly_forecasts table).
 */
class DesktopBackfillChanceSnapshotTest {
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
        tempDbPath = Files.createTempFile("weather-backfill-test", ".db")
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
    fun `backfill fills null chance columns from hourly_forecast_history window max`() {
        val yesterday = today.minusDays(5)
        val yesterdayStart = yesterday.toEpochDay() * 86_400_000L

        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = yesterdayStart, source = source, locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
        val bucket = 0L
        dao.upsertHourlyForecastHistory(
            lat, lon, source, bucket,
            listOf(
                HourlyForecast(
                    yesterday.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
                    52f, "Rain", precipProbability = 14,
                ),
            ),
        )

        repository.backfillForecastChanceSnapshotsIfNeeded(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(yesterdayStart, yesterdayStart, lat, lon).first { it.source == source }
        assertEquals(14, stored.forecastNightPrecipChance)
    }

    @Test
    fun `backfill leaves chances null when no matching history rows exist`() {
        val yesterday = today.minusDays(5)
        val yesterdayStart = yesterday.toEpochDay() * 86_400_000L

        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = yesterdayStart, source = source, locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
        // No hourly_forecast_history rows seeded — best-effort must leave chances null, not crash.

        repository.backfillForecastChanceSnapshotsIfNeeded(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(yesterdayStart, yesterdayStart, lat, lon).first { it.source == source }
        assertNull(stored.forecastDayPrecipChance)
        assertNull(stored.forecastNightPrecipChance)
    }

    @Test
    fun `backfill only scans once, guarded by the one-time app_logs marker`() {
        val yesterday = today.minusDays(5)
        val yesterdayStart = yesterday.toEpochDay() * 86_400_000L

        dao.upsertDailyHistory(
            listOf(
                DailyHistory(
                    date = yesterdayStart, source = source, locationLat = lat, locationLon = lon,
                    highTemp = 70f, lowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
        repository.backfillForecastChanceSnapshotsIfNeeded(System.currentTimeMillis())
        assertTrue(dao.getRecentLogsByTags(listOf("CHANCE_BACKFILL_DONE")).isNotEmpty())

        // History data shows up AFTER the guarded scan already ran once — the marker must prevent a
        // second scan from picking it up.
        dao.upsertHourlyForecastHistory(
            lat, lon, source, 0L,
            listOf(
                HourlyForecast(
                    yesterday.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
                    52f, "Rain", precipProbability = 14,
                ),
            ),
        )
        repository.backfillForecastChanceSnapshotsIfNeeded(System.currentTimeMillis())

        val stored = dao.getExtremesInRange(yesterdayStart, yesterdayStart, lat, lon).first { it.source == source }
        assertNull("Second call must be a no-op even though matching history now exists", stored.forecastNightPrecipChance)
    }
}
