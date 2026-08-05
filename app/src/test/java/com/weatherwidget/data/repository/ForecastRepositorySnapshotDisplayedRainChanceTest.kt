package com.weatherwidget.data.repository

import com.weatherwidget.data.local.DailyHistoryEntity
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.DailyNoonCloudCover
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
            dailyActualsStore = DailyActualsStore(db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), db.hourlyForecastDao(), mockk(relaxed = true)),
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
                    dateOfPrediction = today.toString(),
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
                    computedHighTemp = 70f,
                    computedLowTemp = 55f,
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
                    dateOfPrediction = today.toString(),
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
                    dateOfPrediction = yesterday.toString(),
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
                    computedHighTemp = 70f,
                    computedLowTemp = 55f,
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

    @Test
    fun `freezes forecast overlay and noon cloud for today`() = runTest {
        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = today.toString(), dateOfPrediction = today.toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                    highTemp = 80f, lowTemp = 55f,
                ).copy(precipAmountMm = 1.5f),
            ),
        )
        db.hourlyForecastDao().insertAll(
            listOf(
                TestData.hourly(
                    dateTime = today.atTime(12, 0).toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                ).copy(cloudCover = 60),
            ),
        )
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    source = WeatherSource.NWS.id, locationLat = lat, locationLon = lon,
                    computedHighTemp = 70f, computedLowTemp = 55f, condition = "Clear",
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

        assertEquals(80f, stored.forecastHighTemp)
        assertEquals(55f, stored.forecastLowTemp)
        assertEquals(1.5f, stored.forecastPrecipAmountMm)
        assertEquals(60, stored.noonCloudPercent)
    }

    @Test
    fun `freezes noon cloud from display site instead of stale neighboring fragment`() = runTest {
        val staleLat = lat - 0.05
        val staleLon = lon + 0.02
        val noon = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = today.toString(),
                    dateOfPrediction = today.toString(),
                    source = WeatherSource.NWS.id,
                    lat = lat,
                    lon = lon,
                    highTemp = 80f,
                    lowTemp = 55f,
                ),
            ),
        )
        // Insert the stale site first. With ORDER BY dateTime only, this recreates the observed raw
        // proximity ordering where DailyNoonCloudCover.firstOrNull chose the stale fragment.
        db.hourlyForecastDao().insertAll(
            listOf(
                TestData.hourly(
                    dateTime = today.atTime(12, 0).toString(),
                    source = WeatherSource.NWS.id,
                    lat = staleLat,
                    lon = staleLon,
                    fetchedAt = 1_000L,
                ).copy(cloudCover = 25),
                TestData.hourly(
                    dateTime = today.atTime(12, 0).toString(),
                    source = WeatherSource.NWS.id,
                    lat = lat,
                    lon = lon,
                    fetchedAt = 2_000L,
                ).copy(cloudCover = 65),
            ),
        )
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    source = WeatherSource.NWS.id,
                    locationLat = lat,
                    locationLon = lon,
                    computedHighTemp = 70f,
                    computedLowTemp = 55f,
                    condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )

        val rawRows = db.hourlyForecastDao().getHourlyForecasts(noon, noon, lat, lon)
        assertEquals(
            "test setup must reproduce stale first-row selection before repository site filtering",
            25,
            DailyNoonCloudCover.resolveMeasuredNoonCloudCoverPercent(
                hourly = rawRows.map { it.toHourlyForecast() },
                date = today,
                displaySourceId = WeatherSource.NWS.id,
                zone = zone,
            ),
        )

        repository.snapshotDisplayedRainChance(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(
            today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            lat,
            lon,
        ).first { it.source == WeatherSource.NWS.id }
        assertEquals("frozen noon cloud must come from the display site", 65, stored.noonCloudPercent)
    }

    @Test
    fun `incomplete evening batch does not clobber frozen overlay`() = runTest {
        // NWS evening batches drop lowTemp once the day's low has passed. The values a complete
        // batch froze earlier today must survive; a missing hourly noon reading must not erase
        // the frozen noon cloud either.
        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = today.toString(), dateOfPrediction = today.toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                    highTemp = 80f, lowTemp = null,
                ),
            ),
        )
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    source = WeatherSource.NWS.id, locationLat = lat, locationLon = lon,
                    computedHighTemp = 70f, computedLowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    forecastHighTemp = 75f, forecastLowTemp = 50f,
                    forecastPrecipAmountMm = 2f, noonCloudPercent = 30,
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(
            today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            today.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            lat, lon,
        ).first { it.source == WeatherSource.NWS.id }

        assertEquals(75f, stored.forecastHighTemp)
        assertEquals(50f, stored.forecastLowTemp)
        assertEquals(2f, stored.forecastPrecipAmountMm)
        assertEquals(30, stored.noonCloudPercent)
    }

    @Test
    fun `closed overlay window is never overwritten by a later forecast`() = runTest {
        val yesterday = today.minusDays(1)
        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = yesterday.toString(), dateOfPrediction = yesterday.toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                    highTemp = 80f, lowTemp = 60f,
                ),
            ),
        )
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
                    source = WeatherSource.NWS.id, locationLat = lat, locationLon = lon,
                    computedHighTemp = 70f, computedLowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    forecastHighTemp = 75f, forecastLowTemp = 50f, // archived while yesterday was live
                ),
            ),
        )

        repository.snapshotDisplayedRainChance(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(
            yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            yesterday.toEpochDay() * WidgetConstants.MS_IN_A_DAY,
            lat, lon,
        ).first { it.source == WeatherSource.NWS.id }

        assertEquals("Closed overlay window must keep the archived high", 75f, stored.forecastHighTemp)
        assertEquals("Closed overlay window must keep the archived low", 50f, stored.forecastLowTemp)
    }

    @Test
    fun `backfill fills frozen overlay from the most recent complete retained batch`() = runTest {
        val past = today.minusDays(3)
        val pastStart = past.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        // Latest batch is incomplete (null low) — the backfill must use the older complete one,
        // matching the past-day reader's selection.
        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = past.toString(), dateOfPrediction = past.toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                    highTemp = 72f, lowTemp = null, fetchedAt = 2000L,
                ),
                TestData.forecast(
                    targetDate = past.toString(), dateOfPrediction = past.toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                    highTemp = 71f, lowTemp = 53f, fetchedAt = 1000L,
                ).copy(precipAmountMm = 0.5f),
            ),
        )
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = pastStart,
                    source = WeatherSource.NWS.id, locationLat = lat, locationLon = lon,
                    computedHighTemp = 70f, computedLowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                ),
            ),
        )

        repository.backfillFrozenDisplayColumnsIfNeeded(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(pastStart, pastStart, lat, lon)
            .first { it.source == WeatherSource.NWS.id }
        assertEquals(71f, stored.forecastHighTemp)
        assertEquals(53f, stored.forecastLowTemp)
        assertEquals(0.5f, stored.forecastPrecipAmountMm)
        // hourly_forecast_history is empty in this harness — noon cloud stays null, best-effort.
        assertNull(stored.noonCloudPercent)
    }

    @Test
    fun `backfill fills missing overlay without touching an already-frozen noon cloud`() = runTest {
        // First post-migration fetch order: the live writer freezes yesterday's noon cloud (its
        // window is still open) before the backfill scans — the backfill must still fill the
        // overlay for that row instead of skipping it, and must not clobber the frozen noon cloud.
        val past = today.minusDays(2)
        val pastStart = past.toEpochDay() * WidgetConstants.MS_IN_A_DAY
        db.forecastDao().insertAll(
            listOf(
                TestData.forecast(
                    targetDate = past.toString(), dateOfPrediction = past.toString(),
                    source = WeatherSource.NWS.id, lat = lat, lon = lon,
                    highTemp = 79f, lowTemp = 57f,
                ),
            ),
        )
        db.dailyHistoryDao().insertAll(
            listOf(
                DailyHistoryEntity(
                    date = pastStart,
                    source = WeatherSource.NWS.id, locationLat = lat, locationLon = lon,
                    computedHighTemp = 70f, computedLowTemp = 55f, condition = "Clear",
                    updatedAt = System.currentTimeMillis(),
                    noonCloudPercent = 19, // frozen live; overlay window had already closed
                ),
            ),
        )

        repository.backfillFrozenDisplayColumnsIfNeeded(lat, lon)

        val stored = db.dailyHistoryDao().getExtremesInRange(pastStart, pastStart, lat, lon)
            .first { it.source == WeatherSource.NWS.id }
        assertEquals(79f, stored.forecastHighTemp)
        assertEquals(57f, stored.forecastLowTemp)
        assertEquals("Frozen noon cloud must survive the backfill", 19, stored.noonCloudPercent)
    }
}
