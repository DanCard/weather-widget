package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneId
import com.weatherwidget.test.category.LongDuration

@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class NwsPrecipAmountIntegrationTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: ForecastRepository
    private lateinit var nwsApi: NwsApi
    private val testLat = 37.7749
    private val testLon = -122.4194

    @Before
    fun setup() {
        db = TestDatabase.create()
        nwsApi = mockk()

        val context = RuntimeEnvironment.getApplication()
        val widgetStateManager = mockk<WidgetStateManager>(relaxed = true)
        every { widgetStateManager.isSourceVisible(any()) } answers { firstArg<WeatherSource>() == WeatherSource.NWS }
        every { widgetStateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.NWS)

        repository = ForecastRepository(
            context = context,
            forecastDao = db.forecastDao(),
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            nwsApi = nwsApi,
            openMeteoApi = mockk(relaxed = true),
            visualCrossingApi = mockk(relaxed = true),
            weatherApi = mockk(relaxed = true),
            silurianApi = mockk(relaxed = true),
            widgetStateManager = widgetStateManager,
            climateNormalDao = db.climateNormalDao(),
            observationDao = db.observationDao(),
            dailyExtremeDao = mockk(relaxed = true),
            observationRepository = mockk(relaxed = true),
            tomorrowIoApi = mockk(relaxed = true),
            openWeatherMapApi = mockk(relaxed = true),
            nwsForecastMapper = NwsForecastMapper(nwsApi, db.appLogDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getWeatherData stores hourly NWS precip amounts and rolls them into daily forecast`() = runTest {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/forecast")
        val zoneId = ZoneId.systemDefault()

        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        coEvery { nwsApi.getGridpointsBundle(gridPoint) } returns NwsApi.GridpointsBundle(
            skyCoverByHour = emptyMap(),
            qpfIntervals = listOf(
                NwsApi.QuantitativePrecipitationInterval(
                    startTime = tomorrow.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                    endTime = tomorrow.atStartOfDay(zoneId).plusHours(6).toInstant().toEpochMilli(),
                    amountMm = 6f,
                ),
                NwsApi.QuantitativePrecipitationInterval(
                    startTime = tomorrow.atStartOfDay(zoneId).plusHours(6).toInstant().toEpochMilli(),
                    endTime = tomorrow.atStartOfDay(zoneId).plusHours(12).toInstant().toEpochMilli(),
                    amountMm = 12f,
                ),
            ),
            dailyTemperatures = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap()),
        )
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod(
                name = "Today",
                startTime = "${today}T06:00:00-07:00",
                endTime = "${today}T18:00:00-07:00",
                temperature = 68,
                temperatureUnit = "F",
                shortForecast = "Sunny",
                isDaytime = true,
                precipProbability = 10,
                precipAmountMm = 0.5f,
            ),
            NwsApi.ForecastPeriod(
                name = "Tonight",
                startTime = "${today}T18:00:00-07:00",
                endTime = "${tomorrow}T06:00:00-07:00",
                temperature = 52,
                temperatureUnit = "F",
                shortForecast = "Rain",
                isDaytime = false,
                precipProbability = 100,
                precipAmountMm = 9f,
            ),
            NwsApi.ForecastPeriod(
                name = "Tomorrow",
                startTime = "${tomorrow}T06:00:00-07:00",
                endTime = "${tomorrow}T18:00:00-07:00",
                temperature = 64,
                temperatureUnit = "F",
                shortForecast = "Rain",
                isDaytime = true,
                precipProbability = 100,
                precipAmountMm = 1f,
            ),
        )
        coEvery { nwsApi.getHourlyForecast(gridPoint) } returns listOf(
            NwsApi.HourlyForecastPeriod(
                startTime = tomorrow.atTime(1, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                localDate = tomorrow.toString(),
                localHour = 1,
                temperature = 55f,
                shortForecast = "Rain",
                precipProbability = 80,
                precipAmountMm = 3f,
            ),
            NwsApi.HourlyForecastPeriod(
                startTime = tomorrow.atTime(7, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                localDate = tomorrow.toString(),
                localHour = 7,
                temperature = 57f,
                shortForecast = "Rain",
                precipProbability = 100,
                precipAmountMm = 4f,
            ),
        )

        repository.getWeatherData(testLat, testLon, "Test Location", forceRefresh = true)

        val allForecasts = db.forecastDao()
            .getForecastsInRangeBySource(
                startDate = today.toEpochDay() * com.weatherwidget.widget.WidgetConstants.MS_IN_A_DAY,
                endDate = tomorrow.plusDays(1).toEpochDay() * com.weatherwidget.widget.WidgetConstants.MS_IN_A_DAY,
                lat = testLat,
                lon = testLon,
                source = WeatherSource.NWS.id,
            )
        val latestTomorrow = allForecasts
            .filter { LocalDate.ofEpochDay(it.targetDate / com.weatherwidget.widget.WidgetConstants.MS_IN_A_DAY) == tomorrow }
            .maxByOrNull { it.fetchedAt }

        assertNotNull(latestTomorrow)
        assertEquals(100, latestTomorrow!!.precipProbability)
        assertEquals(3f, latestTomorrow.precipAmountMm!!, 0.001f)

        val hourlyForecasts = db.hourlyForecastDao()
            .getHourlyForecastsBySource(
                tomorrow.atStartOfDay(zoneId).toInstant().toEpochMilli(),
                tomorrow.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli(),
                testLat,
                testLon,
                WeatherSource.NWS.id,
            )
            .sortedBy { it.dateTime }

        assertEquals(2, hourlyForecasts.size)
        assertEquals(1f, hourlyForecasts[0].precipAmountMm!!, 0.001f)
        assertEquals(2f, hourlyForecasts[1].precipAmountMm!!, 0.001f)
    }

    @Test
    fun `getWeatherData falls back to period precip amounts when grid data is absent`() = runTest {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/forecast")

        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        coEvery { nwsApi.getGridpointsBundle(gridPoint) } returns NwsApi.GridpointsBundle(
            skyCoverByHour = emptyMap(),
            qpfIntervals = emptyList(),
            dailyTemperatures = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap()),
        )
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod(
                name = "Tonight",
                startTime = "${today}T18:00:00-07:00",
                endTime = "${tomorrow}T06:00:00-07:00",
                temperature = 52,
                temperatureUnit = "F",
                shortForecast = "Rain",
                isDaytime = false,
                precipProbability = 100,
                precipAmountMm = 9f,
            ),
            NwsApi.ForecastPeriod(
                name = "Tomorrow",
                startTime = "${tomorrow}T06:00:00-07:00",
                endTime = "${tomorrow}T18:00:00-07:00",
                temperature = 64,
                temperatureUnit = "F",
                shortForecast = "Rain",
                isDaytime = true,
                precipProbability = 100,
                precipAmountMm = 1f,
            ),
        )
        coEvery { nwsApi.getHourlyForecast(gridPoint) } returns listOf(
            NwsApi.HourlyForecastPeriod(
                startTime = tomorrow.atTime(1, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                localDate = tomorrow.toString(),
                localHour = 1,
                temperature = 55f,
                shortForecast = "Rain",
                precipProbability = 80,
                precipAmountMm = 3f,
            ),
            NwsApi.HourlyForecastPeriod(
                startTime = tomorrow.atTime(7, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                localDate = tomorrow.toString(),
                localHour = 7,
                temperature = 57f,
                shortForecast = "Rain",
                precipProbability = 100,
                precipAmountMm = 4f,
            ),
        )

        repository.getWeatherData(testLat, testLon, "Test Location", forceRefresh = true)

        val latestTomorrow = db.forecastDao()
            .getForecastsInRangeBySource(
                startDate = today.toEpochDay() * com.weatherwidget.widget.WidgetConstants.MS_IN_A_DAY,
                endDate = tomorrow.plusDays(1).toEpochDay() * com.weatherwidget.widget.WidgetConstants.MS_IN_A_DAY,
                lat = testLat,
                lon = testLon,
                source = WeatherSource.NWS.id,
            )
            .filter { LocalDate.ofEpochDay(it.targetDate / com.weatherwidget.widget.WidgetConstants.MS_IN_A_DAY) == tomorrow }
            .maxByOrNull { it.fetchedAt }

        assertNotNull(latestTomorrow)
        assertEquals(7f, latestTomorrow!!.precipAmountMm!!, 0.001f)
    }

    @Test
    fun `getWeatherData stores future NWS day and night precip directly from forecast periods`() = runTest {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val dayAfterTomorrow = today.plusDays(2)
        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/forecast")
        val zoneId = ZoneId.systemDefault()

        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        coEvery { nwsApi.getGridpointsBundle(gridPoint) } returns NwsApi.GridpointsBundle(
            skyCoverByHour = emptyMap(),
            qpfIntervals = emptyList(),
            dailyTemperatures = NwsApi.DailyTemperatureExtremes(emptyMap(), emptyMap()),
        )
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod(
                name = "Tomorrow",
                startTime = "${tomorrow}T06:00:00-07:00",
                endTime = "${tomorrow}T18:00:00-07:00",
                temperature = 64,
                temperatureUnit = "F",
                shortForecast = "Rain",
                isDaytime = true,
                precipProbability = 30,
            ),
            NwsApi.ForecastPeriod(
                name = "Tomorrow Night",
                startTime = "${tomorrow}T18:00:00-07:00",
                endTime = "${dayAfterTomorrow}T06:00:00-07:00",
                temperature = 52,
                temperatureUnit = "F",
                shortForecast = "Rain",
                isDaytime = false,
                precipProbability = 70,
            ),
        )
        coEvery { nwsApi.getHourlyForecast(gridPoint) } returns listOf(
            NwsApi.HourlyForecastPeriod(
                startTime = tomorrow.atTime(14, 0).atZone(zoneId).toInstant().toEpochMilli(),
                localDate = tomorrow.toString(),
                localHour = 14,
                temperature = 61f,
                shortForecast = "Rain",
                precipProbability = 95,
            ),
        )

        repository.getWeatherData(testLat, testLon, "Test Location", forceRefresh = true)

        val latestTomorrow = db.forecastDao()
            .getForecastsInRangeBySource(
                startDate = tomorrow.toEpochDay() * com.weatherwidget.widget.WidgetConstants.MS_IN_A_DAY,
                endDate = tomorrow.toEpochDay() * com.weatherwidget.widget.WidgetConstants.MS_IN_A_DAY,
                lat = testLat,
                lon = testLon,
                source = WeatherSource.NWS.id,
            )
            .maxByOrNull { it.fetchedAt }

        assertNotNull(latestTomorrow)
        assertEquals(30, latestTomorrow!!.precipProbability)
        assertEquals(30, latestTomorrow.daytimePrecipProbability)
        assertEquals(70, latestTomorrow.nighttimePrecipProbability)
    }
}
