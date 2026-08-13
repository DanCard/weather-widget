package com.weatherwidget.data.repository

import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.HourlyForecastHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetConstants
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId

/**
 * The one-time backfill (post-upgrade) that fills forecastDayPrecipChance/forecastNightPrecipChance
 * for daily_history rows written before this feature existed, from the as-predicted
 * hourly_forecast_history archive (never the live, REPLACE-overwritten hourly_forecasts table).
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ForecastRepositoryBackfillChanceSnapshotTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: ForecastRepository

    private val lat = TestData.LAT
    private val lon = TestData.LON
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now()

    @Before
    fun setup() {
        db = TestDatabase.create()
        val context = RuntimeEnvironment.getApplication()
        repository = ForecastRepository(
            context = context,
            forecastDao = db.forecastDao(),
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            nwsApi = mockk(relaxed = true),
            openMeteoApi = mockk(relaxed = true),
            visualCrossingApi = mockk(relaxed = true),
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = mockk(relaxed = true),
            climateNormalDao = mockk(relaxed = true),
            observationDao = db.observationDao(),
            dailyHistoryDao = db.dailyHistoryDao(),
            observationRepository = mockk(relaxed = true),
            nwsForecastMapper = mockk(relaxed = true),
            openMeteoPastDayActualsWriter = OpenMeteoPastDayActualsWriter(db.dailyHistoryDao(), db.appLogDao()),
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `backfill fills null chance columns from hourly_forecast_history window max`() = runTest {
        val yesterday = today.minusDays(5)
        val yesterdayStart = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY

        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = yesterdayStart, source = WeatherSource.NWS.id,
                    locationLat = lat, locationLon = lon,
                    computedHighTemp = 70f, computedLowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
        db.hourlyForecastHistoryDao().insertAll(
            listOf(
                HourlyForecastHistoryEntity(
                    dateTime = yesterday.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
                    locationLat = lat, locationLon = lon, temperature = 52f, condition = "Rain",
                    source = WeatherSource.NWS.id, timestampToGroupPredictions = 0L, precipProbability = 14,
                    fetchedAt = System.currentTimeMillis(),
                ),
            ),
        )

        repository.backfillForecastChanceSnapshotsIfNeeded(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(yesterdayStart, yesterdayStart, lat, lon)
            .first { it.source == WeatherSource.NWS.id }
        assertEquals(14, stored.forecastNightPrecipChance)
    }

    @Test
    fun `backfill leaves chances null when no matching history rows exist`() = runTest {
        val yesterday = today.minusDays(5)
        val yesterdayStart = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY

        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = yesterdayStart, source = WeatherSource.NWS.id,
                    locationLat = lat, locationLon = lon,
                    computedHighTemp = 70f, computedLowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
        // No hourly_forecast_history rows seeded — best-effort must leave chances null, not crash.

        repository.backfillForecastChanceSnapshotsIfNeeded(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(yesterdayStart, yesterdayStart, lat, lon)
            .first { it.source == WeatherSource.NWS.id }
        assertNull(stored.forecastDayPrecipChance)
        assertNull(stored.forecastNightPrecipChance)
    }

    @Test
    fun `backfill only scans once, guarded by the one-time flag`() = runTest {
        // First run finds no matching history (nothing backfilled, chances stay null) and still
        // marks the one-time flag done — a permanently-null row must not be re-scanned forever.
        val yesterday = today.minusDays(5)
        val yesterdayStart = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY

        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = yesterdayStart, source = WeatherSource.NWS.id,
                    locationLat = lat, locationLon = lon,
                    computedHighTemp = 70f, computedLowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )
        repository.backfillForecastChanceSnapshotsIfNeeded(lat, lon)

        // History data shows up AFTER the guarded scan already ran once — the flag must prevent a
        // second scan from picking it up (proving the guard is a real scan-level gate, not just
        // incidental idempotency from the null-column filter).
        db.hourlyForecastHistoryDao().insertAll(
            listOf(
                HourlyForecastHistoryEntity(
                    dateTime = yesterday.plusDays(1).atTime(7, 0).atZone(zone).toInstant().toEpochMilli(),
                    locationLat = lat, locationLon = lon, temperature = 52f, condition = "Rain",
                    source = WeatherSource.NWS.id, timestampToGroupPredictions = 0L, precipProbability = 14,
                    fetchedAt = System.currentTimeMillis(),
                ),
            ),
        )
        repository.backfillForecastChanceSnapshotsIfNeeded(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(yesterdayStart, yesterdayStart, lat, lon)
            .first { it.source == WeatherSource.NWS.id }
        assertNull("Second call must be a no-op even though matching history now exists", stored.forecastNightPrecipChance)
    }
}
