package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.*
import com.weatherwidget.data.repository.*
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Regression guard for the recurring "daily column low disagrees with the hourly graph" bug, at the
 * DISPLAY path that actually feeds the widget: [ObservationRepository.getDailyActualsWithLiveToday].
 *
 * Root cause (2026-07-08 field case): today's live actual was blended from a TODAY-ONLY observation
 * window, so a near station whose feed lapsed before midnight was dropped and a lone cold outlier
 * dominated the low (displayed 52.5 while the graph showed ~54.4). The blend must instead reach back
 * across midnight (ActualsAggregator.DAILY_BLEND_CONTEXT_MS) so the station set — and thus the
 * outlier's dilution — is stable at the day's leading edge.
 *
 * The existing [YesterdayActualHighConsistencyTest] never caught this: it uses a single station and a
 * within-day peak, so no cross-midnight coverage is ever in play.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class DailyLiveTodayWindowConsistencyTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var repository: WeatherRepository
    private lateinit var observationRepository: ObservationRepository
    private val lat = 37.422
    private val lon = -122.084
    private val zone = ZoneId.systemDefault()
    private val source = WeatherSource.NWS

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = TestDatabase.create()

        val widgetStateManager = WidgetStateManager(context)
        val nwsApi = mockk<NwsApi>(relaxed = true)
        val openMeteoApi = mockk<OpenMeteoApi>(relaxed = true)
        val visualCrossingApi = mockk<VisualCrossingApi>(relaxed = true)
        val weatherApi = mockk<WeatherApi>(relaxed = true)
        val silurianApi = mockk<SilurianApi>(relaxed = true)
        val tomorrowIoApi = mockk<TomorrowIoApi>(relaxed = true)
        val openWeatherMapApi = mockk<OpenWeatherMapApi>(relaxed = true)

        observationRepository = ObservationRepository(
            context, db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), nwsApi, db.hourlyForecastDao(),
        )
        val currentTempRepository = CurrentTempRepository(
            context, db.observationDao(), db.hourlyForecastDao(), db.appLogDao(), nwsApi, openMeteoApi,
            visualCrossingApi, weatherApi, silurianApi, widgetStateManager, db.dailyHistoryDao(),
            observationRepository, tomorrowIoApi, openWeatherMapApi,
        )
        val nwsForecastMapper = NwsForecastMapper(nwsApi, db.appLogDao())
        val forecastRepository = ForecastRepository(
            context, db.forecastDao(), db.hourlyForecastDao(), db.hourlyForecastHistoryDao(), db.appLogDao(),
            nwsApi, openMeteoApi, visualCrossingApi, weatherApi, silurianApi, widgetStateManager,
            db.climateNormalDao(), db.observationDao(), db.dailyHistoryDao(), observationRepository,
            tomorrowIoApi, openWeatherMapApi, nwsForecastMapper,
        )
        repository = WeatherRepository(
            context, forecastRepository, currentTempRepository, db.forecastDao(), db.appLogDao(), observationRepository,
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `live today low blends across midnight, matching the wide-window graph, not a today-only blend`() = runTest {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        // NEAR (dominant official) has its last evening reading at 23:30 yesterday and its first
        // in-day reading at 01:00 — so it can only bracket the 00:15 cold candidate ACROSS midnight.
        // EDGE is a moderately distant cold reading at 00:15. In a today-only window NEAR cannot
        // reach 00:15 and EDGE drags the low down; with yesterday's tail it is corroborated/diluted.
        val obs = mutableListOf<com.weatherwidget.data.local.ObservationEntity>()
        obs += observation("NEAR", yesterday.atTime(23, 30), 53f, distanceKm = 2.2f)
        obs += observation("EDGE", today.atTime(0, 15), 44f, distanceKm = 4f)
        val nearTemps = listOf(54f, 54f, 55f, 57f, 61f, 66f, 71f, 74f, 75f, 74f, 70f, 66f, 62f, 60f, 58f, 57f)
        nearTemps.forEachIndexed { i, t -> obs += observation("NEAR", today.atTime(1 + i, 0), t, distanceKm = 2.2f) }
        db.observationDao().insertAll(obs)

        val forecasts = (-30..30).map { h ->
            TestData.hourly(
                dateTime = today.atStartOfDay().plusHours(h.toLong()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")),
                temperature = 60f, source = source.id, lat = lat, lon = lon,
            )
        }
        db.hourlyForecastDao().insertAll(forecasts)

        // The value the widget actually displays for today.
        val displayLow = repository
            .getDailyActualsWithLiveToday(lat, lon, forecasts, listOf(source.id))[source.id]!![today]!!.lowTemp

        // Reference blends over the same observations, computed directly through the shared engine.
        val readings = obs.map { it.toReadingForTest() }
        val fc = forecasts.map { it.toForecastForTest() }
        val wideLow = ActualsAggregator.aggregate(readings, fc, lat, lon, zone).single { it.source == source.id && it.toLocalDate() == today }.lowTemp
        val todayOnly = readings.filter { it.timestamp >= today.atStartOfDay(zone).toInstant().toEpochMilli() }
        val todayOnlyLow = ActualsAggregator.aggregate(todayOnly, fc, lat, lon, zone).single { it.source == source.id && it.toLocalDate() == today }.lowTemp

        assertTrue(
            "scenario must be window-dependent (todayOnly=$todayOnlyLow wide=$wideLow) or the guard proves nothing",
            Math.abs(todayOnlyLow - wideLow) > 0.3f,
        )
        assertEquals("displayed live-today low must equal the cross-midnight (wide) blend", wideLow, displayLow, 0.1f)
        assertTrue(
            "displayed low ($displayLow) must NOT regress to the today-only blend ($todayOnlyLow)",
            Math.abs(displayLow - todayOnlyLow) > 0.3f,
        )
    }

    private fun observation(stationId: String, time: java.time.LocalDateTime, temperature: Float, distanceKm: Float) =
        TestData.observation(
            stationId = stationId,
            timestamp = time.atZone(zone).toInstant().toEpochMilli(),
            temperature = temperature,
            api = source.id,
            lat = lat,
            lon = lon,
            distanceKm = distanceKm,
            stationType = "OFFICIAL",
        )

    private fun com.weatherwidget.data.local.ObservationEntity.toReadingForTest() =
        com.weatherwidget.data.model.ObservationReading(
            stationId = stationId, stationName = stationName, timestamp = timestamp, temperature = temperature,
            condition = condition, locationLat = locationLat, locationLon = locationLon, distanceKm = distanceKm,
            api = api, stationType = stationType,
        )

    private fun com.weatherwidget.data.local.HourlyForecastEntity.toForecastForTest() =
        com.weatherwidget.data.model.HourlyForecast(
            dateTime = dateTime, temperature = temperature, condition = condition, source = source,
        )
}
