package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.*
import com.weatherwidget.data.repository.*
import com.weatherwidget.testutil.TestData
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.handlers.DailyViewLogic
import com.weatherwidget.widget.handlers.buildHourDataList
import com.weatherwidget.shared.util.TemperatureInterpolator
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import com.weatherwidget.data.model.DailyHistory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class YesterdayActualHighConsistencyTest {

    private lateinit var context: Context
    private lateinit var db: WeatherDatabase
    private lateinit var repository: WeatherRepository
    private val lat = 37.422
    private val lon = -122.084
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
        
        val observationRepository = ObservationRepository(
            context,
            db.observationDao(),
            db.dailyHistoryDao(),
            db.appLogDao(),
            nwsApi,
            db.hourlyForecastDao()
        )
        
        val currentTempRepository = CurrentTempRepository(
            context,
            db.observationDao(),
            db.hourlyForecastDao(),
            db.appLogDao(),
            nwsApi,
            openMeteoApi,
            visualCrossingApi,
            weatherApi,
            silurianApi,
            widgetStateManager,
            db.dailyHistoryDao(),
            observationRepository,
            tomorrowIoApi,
            openWeatherMapApi
        )
        
        val dailyActualsStore = DailyActualsStore(db.observationDao(), db.dailyHistoryDao(), db.appLogDao(), db.hourlyForecastDao(), mockk(relaxed = true))
        val nwsForecastMapper = NwsForecastMapper(nwsApi, db.appLogDao(), dailyActualsStore)
        
        val forecastRepository = ForecastRepository(
            context,
            db.forecastDao(),
            db.hourlyForecastDao(),
            db.hourlyForecastHistoryDao(),
            db.appLogDao(),
            nwsApi,
            openMeteoApi,
            visualCrossingApi,
            weatherApi,
            silurianApi,
            widgetStateManager,
            db.climateNormalDao(),
            db.observationDao(),
            db.dailyHistoryDao(),
            observationRepository,
            tomorrowIoApi,
            openWeatherMapApi,
            nwsForecastMapper,
            dailyActualsStore,
        )
        
        repository = WeatherRepository(
            context,
            forecastRepository,
            currentTempRepository,
            db.forecastDao(),
            db.appLogDao(),
            observationRepository
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `yesterday actual high in daily view matches hourly graph peak`() = runTest {
        val now = LocalDate.now().atTime(10, 0)
        val today = now.toLocalDate()
        val yesterday = today.minusDays(1)
        val yesterdayStart = yesterday.atStartOfDay(ZoneId.systemDefault())

        // 1. Prepare Observations for yesterday
        // We'll create a peak of 78.5 at 3 PM
        val observations = mutableListOf<com.weatherwidget.data.local.ObservationEntity>()
        for (hour in 0..23) {
            val temp = if (hour == 15) 78.5f else 60f + (hour % 10)
            observations.add(
                TestData.observation(
                    timestamp = yesterdayStart.plusHours(hour.toLong()).toInstant().toEpochMilli(),
                    temperature = temp,
                    api = source.id,
                    lat = lat,
                    lon = lon,
                    stationId = "KTEST"
                )
            )
        }
        db.observationDao().insertAll(observations)

        // 2. Prepare Hourly Forecasts (needed for blending)
        val forecasts = mutableListOf<com.weatherwidget.data.local.HourlyForecastEntity>()
        for (hour in -48..48) {
            val dt = now.plusHours(hour.toLong())
            forecasts.add(
                TestData.hourly(
                    dateTime = dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")),
                    temperature = 65f,
                    source = source.id,
                    lat = lat,
                    lon = lon
                )
            )
        }
        db.hourlyForecastDao().insertAll(forecasts)

        // 3. Recompute daily extremes
        repository.recomputeDailyExtremesFromStoredObservations(
            lat, lon, yesterday, yesterday, forecasts
        )

        // 4. Get Daily View Data
        val dailyActuals = repository.getDailyActualsWithLiveToday(
            lat, lon, forecasts, listOf(source.id)
        )
        
        val days = DailyViewLogic.prepareGraphDays(
            todayLabel = "Today",
            now = now,
            centerDate = today,
            today = today,
            weatherByDate = emptyMap(),
            forecastSnapshots = emptyMap(),
            numColumns = 7,
            displaySource = source,
            skipYesterday = false,
            skipHistory = false,
            hourlyForecasts = forecasts,
            dailyActuals = dailyActuals[source.id] ?: emptyMap(),
            currentTemp = 65f
        )

        val yesterdayData = days.find { it.date == yesterday }
        requireNotNull(yesterdayData) { "Yesterday data should be present in Daily view" }
        // For past days, DailyViewLogic.prepareGraphDays uses solidLineHigh for the actual high
        val dailyActualHigh = yesterdayData.solidLineHigh
        requireNotNull(dailyActualHigh) { "Daily actual high should not be null" }

        // 5. Get Hourly Graph Data
        // Center the hourly graph at a time that includes yesterday's peak.
        val graphCenter = yesterday.atTime(15, 0)
        val hourlyData = buildHourDataList(
            hourlyForecasts = forecasts,
            centerTime = graphCenter,
            numColumns = 5,
            displaySource = source,
            zoom = ZoomLevel.WIDE,
            actuals = observations
        )

        val hourlyActualHigh = hourlyData
            .filter { it.dateTime.toLocalDate() == yesterday }
            .mapNotNull { it.actualTemperature }
            .maxOrNull()

        requireNotNull(hourlyActualHigh) { "Hourly actual high should not be null" }

        // 6. Verify consistency
        assertEquals(
            "Daily actual high should match hourly graph actual high for yesterday",
            hourlyActualHigh.toDouble(),
            dailyActualHigh.toDouble(),
            0.1
        )
        
        assertEquals("The high should be our injected 78.5", 78.5, dailyActualHigh.toDouble(), 0.1)
    }
}
