package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ObservationDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class NwsMiddayOverrideTest {
    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var forecastDao: ForecastDao
    private lateinit var hourlyForecastDao: HourlyForecastDao
    private lateinit var appLogDao: AppLogDao
    private lateinit var nwsApi: NwsApi
    private lateinit var openMeteoApi: OpenMeteoApi
    private lateinit var weatherApi: WeatherApi
    private lateinit var widgetStateManager: WidgetStateManager
    private lateinit var temperatureInterpolator: TemperatureInterpolator
    private lateinit var climateNormalDao: ClimateNormalDao
    private lateinit var observationDao: ObservationDao
    private lateinit var forecastRepository: ForecastRepository
    private lateinit var currentTempRepository: CurrentTempRepository
    private lateinit var repository: WeatherRepository

    private val testLat = 37.42
    private val testLon = -122.08
    private val testLocationName = "Mountain View, CA"
    private val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        sharedPrefs = mockk(relaxed = true)
        every { context.getSharedPreferences(any(), any()) } returns sharedPrefs

        forecastDao = mockk(relaxed = true)
        hourlyForecastDao = mockk(relaxed = true)
        appLogDao = mockk(relaxed = true)
        nwsApi = mockk()
        openMeteoApi = mockk()
        weatherApi = mockk()
        widgetStateManager = mockk(relaxed = true)
        temperatureInterpolator = TemperatureInterpolator()
        climateNormalDao = mockk(relaxed = true)
        observationDao = mockk(relaxed = true)

        forecastRepository = ForecastRepository(context, forecastDao, hourlyForecastDao, appLogDao, nwsApi, openMeteoApi, weatherApi, mockk(relaxed = true), widgetStateManager, climateNormalDao, observationDao, mockk(relaxed = true), mockk(relaxed = true))
        currentTempRepository = CurrentTempRepository(context, mockk(relaxed = true), observationDao, hourlyForecastDao, appLogDao, nwsApi, openMeteoApi, weatherApi, mockk(relaxed = true), widgetStateManager, temperatureInterpolator, mockk(relaxed = true), mockk(relaxed = true))

        repository =
            WeatherRepository(context, forecastRepository, currentTempRepository, forecastDao, appLogDao, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `fetchFromNws prioritizes midday hourly condition for future dates`() = runTest {
        val gridPoint = NwsApi.GridPointInfo("MTR", 93, 87, "https://api.weather.gov/gridpoints/MTR/93,87/forecast", "https://obs.api")
        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(NwsApi.ForecastPeriod("Tomorrow", "${tomorrow}T06:00:00-08:00", "${tomorrow}T18:00:00-08:00", 64, "F", "Patchy Fog then Slight Chance Light Rain", true))
        coEvery { nwsApi.getHourlyForecast(gridPoint) } returns listOf(
            NwsApi.HourlyForecastPeriod("${tomorrow}T08:00:00-08:00", 54f, "Patchy Fog"),
            NwsApi.HourlyForecastPeriod("${tomorrow}T13:00:00-08:00", 64f, "Partly Sunny"),
            NwsApi.HourlyForecastPeriod("${tomorrow}T18:00:00-08:00", 58f, "Slight Chance Light Rain")
        )
        coEvery { nwsApi.getObservationStations(any()) } returns emptyList()
        val result = repository.fetchFromNws(testLat, testLon, testLocationName)
        val tomorrowEntry = result.find { it.targetDate == tomorrow }
        assertEquals("Fog then Partly Sunny", tomorrowEntry?.condition)
    }
}
