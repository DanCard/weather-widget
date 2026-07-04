package com.weatherwidget.data.repository

import com.weatherwidget.data.local.DailyHistoryEntity
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
 * Regression for the night rain chance bug (2026-07-04): NWS's raw "Tonight" period chance (9%)
 * excludes 6-8am rain that the app's 8pm-8am night window counts as part of tonight (a 14% chance
 * at 7am). [ForecastRepository.snapshotDisplayedRainChance] must persist the resolved (window-max)
 * value into daily_history so history later replays 14%, not the raw period field.
 */
@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class ForecastRepositorySnapshotDisplayedRainChanceTest {
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
            hourlyForecastHistoryDao = mockk(relaxed = true),
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
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `snapshot stores hourly window max night chance over NWS raw period field`() = runTest {
        // NWS's raw "Tonight" period chance is 9% (excludes 6-8am rain per NWS's 6am/6pm periods).
        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = today.toString(),
                    forecastDate = today.toString(),
                    source = WeatherSource.NWS.id,
                    lat = lat,
                    lon = lon,
                ).copy(daytimePrecipProbability = 0, nighttimePrecipProbability = 9, precipProbability = 9),
            ),
        )
        // Hourly rows show the true peak (14%) inside the app's 8pm-8am night window, at 7am.
        db.hourlyForecastDao().insertAll(
            listOf(
                TestData.hourly(
                    dateTime = today.plusDays(1).atTime(5, 0).toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                ).copy(precipProbability = 9),
                TestData.hourly(
                    dateTime = today.plusDays(1).atTime(7, 0).toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                ).copy(precipProbability = 14),
            ),
        )
        // The actuals path must already have written a daily_history row for the snapshot to attach to.
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    source = WeatherSource.NWS.id,
                    locationLat = lat,
                    locationLon = lon,
                    highTemp = 70f,
                    lowTemp = 55f,
                    condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(
            today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            lat, lon,
        ).first { it.source == WeatherSource.NWS.id }

        assertEquals(14, stored.forecastNightPrecipChance)
    }

    @Test
    fun `snapshot does nothing when no daily_history row exists yet`() = runTest {
        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = today.toString(),
                    forecastDate = today.toString(),
                    source = WeatherSource.NWS.id,
                    lat = lat,
                    lon = lon,
                ).copy(daytimePrecipProbability = 5, nighttimePrecipProbability = 9),
            ),
        )

        // No daily_history row seeded — nothing to attach the snapshot to yet.
        repository.snapshotDisplayedRainChance(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(
            today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            lat, lon,
        )
        assertNull(stored.firstOrNull { it.source == WeatherSource.NWS.id })
    }

    @Test
    fun `closed day window is never overwritten even when live hourly data has since drifted`() = runTest {
        // Yesterday's DAY window (8am-8pm) always closed before "now" (today), regardless of what
        // time this test runs. The live hourly_forecasts table has since been REPLACE'd with a
        // drifted re-forecast (40%) for that closed window; the archived 5% must survive untouched.
        val yesterday = today.minusDays(1)
        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = yesterday.toString(),
                    forecastDate = yesterday.toString(),
                    source = WeatherSource.NWS.id,
                    lat = lat,
                    lon = lon,
                ).copy(daytimePrecipProbability = 40, nighttimePrecipProbability = 0),
            ),
        )
        db.hourlyForecastDao().insertAll(
            listOf(
                TestData.hourly(
                    dateTime = yesterday.atTime(14, 0).toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                ).copy(precipProbability = 40), // drifted hindcast value, must NOT win
            ),
        )
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    source = WeatherSource.NWS.id,
                    locationLat = lat,
                    locationLon = lon,
                    highTemp = 70f,
                    lowTemp = 55f,
                    condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    forecastDayPrecipChance = 5, // archived while yesterday was still live
                    forecastNightPrecipChance = 0,
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(
            yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            lat, lon,
        ).first { it.source == WeatherSource.NWS.id }

        assertEquals("Closed day window must not be overwritten by drifted hourly data", 5, stored.forecastDayPrecipChance)
    }
}
