package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.WeatherDao
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
    private lateinit var weatherDao: WeatherDao
    private lateinit var forecastSnapshotDao: ForecastSnapshotDao
    private lateinit var hourlyForecastDao: HourlyForecastDao
    private lateinit var appLogDao: AppLogDao
    private lateinit var nwsApi: NwsApi
    private lateinit var openMeteoApi: OpenMeteoApi
    private lateinit var weatherApi: WeatherApi
    private lateinit var widgetStateManager: WidgetStateManager
    private lateinit var temperatureInterpolator: TemperatureInterpolator
    private lateinit var climateNormalDao: ClimateNormalDao
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

        weatherDao = mockk(relaxed = true)
        forecastSnapshotDao = mockk(relaxed = true)
        hourlyForecastDao = mockk(relaxed = true)
        appLogDao = mockk(relaxed = true)
        nwsApi = mockk()
        openMeteoApi = mockk()
        weatherApi = mockk()
        widgetStateManager = mockk(relaxed = true)
        temperatureInterpolator = TemperatureInterpolator()
        climateNormalDao = mockk(relaxed = true)

        repository =
            WeatherRepository(
                context,
                weatherDao,
                forecastSnapshotDao,
                hourlyForecastDao,
                appLogDao,
                nwsApi,
                openMeteoApi,
                weatherApi,
                widgetStateManager,
                temperatureInterpolator,
                climateNormalDao,
            )
    }

    @Test
    fun `fetchFromNws prioritizes midday hourly condition for future dates`() = runTest {
        // Setup
        val gridPoint = NwsApi.GridPointInfo("MTR", 93, 87, "https://api.weather.gov/gridpoints/MTR/93,87/forecast")
        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        
        // Daily forecast has fog and slight rain for tomorrow
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod(
                name = "Tomorrow",
                startTime = "${tomorrow}T06:00:00-08:00",
                temperature = 64,
                temperatureUnit = "F",
                shortForecast = "Patchy Fog then Slight Chance Light Rain",
                isDaytime = true
            )
        )
        
        // Hourly forecast for the same day has "Partly Sunny" at midday
        coEvery { nwsApi.getHourlyForecast(gridPoint) } returns listOf(
            NwsApi.HourlyForecastPeriod("${tomorrow}T08:00:00-08:00", 54f, "Patchy Fog"),
            NwsApi.HourlyForecastPeriod("${tomorrow}T13:00:00-08:00", 64f, "Partly Sunny"),
            NwsApi.HourlyForecastPeriod("${tomorrow}T18:00:00-08:00", 58f, "Slight Chance Light Rain")
        )

        // Act
        val result = repository.fetchFromNws(testLat, testLon, testLocationName)

        // Assert
        val tomorrowEntry = result.find { it.date == tomorrow }
        assertNotNull("Should have entry for tomorrow", tomorrowEntry)
        assertEquals("Fog then Partly Sunny", tomorrowEntry?.condition)
    }

    @Test
    fun `fetchFromNws uses sun priority if midday is foggy but day is sunny`() = runTest {
        // Setup
        val gridPoint = NwsApi.GridPointInfo("MTR", 93, 87, "https://api.weather.gov/gridpoints/MTR/93,87/forecast")
        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod(
                name = "Tomorrow",
                startTime = "${tomorrow}T06:00:00-08:00",
                temperature = 64,
                temperatureUnit = "F",
                shortForecast = "Areas of Fog",
                isDaytime = true
            )
        )
        
        // Hourly midday is foggy, but afternoon is sunny
        coEvery { nwsApi.getHourlyForecast(gridPoint) } returns listOf(
            NwsApi.HourlyForecastPeriod("${tomorrow}T13:00:00-08:00", 64f, "Patchy Fog"),
            NwsApi.HourlyForecastPeriod("${tomorrow}T15:00:00-08:00", 66f, "Sunny")
        )

        // Act
        val result = repository.fetchFromNws(testLat, testLon, testLocationName)

        // Assert
        val tomorrowEntry = result.find { it.date == tomorrow }
        assertNotNull("Should have entry for tomorrow", tomorrowEntry)
        assertEquals("Sunny", tomorrowEntry?.condition)
    }

    @Test
    fun `fetchFromNws synthesizes fog transition for future dates with morning fog`() = runTest {
        // Setup
        val gridPoint = NwsApi.GridPointInfo("MTR", 93, 87, "https://api.weather.gov/gridpoints/MTR/93,87/forecast")
        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod(
                name = "Tomorrow",
                startTime = "${tomorrow}T06:00:00-08:00",
                temperature = 74,
                temperatureUnit = "F",
                shortForecast = "Patchy Fog then Partly Sunny",
                isDaytime = true
            )
        )
        
        // Hourly has fog in morning, partly sunny at midday
        coEvery { nwsApi.getHourlyForecast(gridPoint) } returns listOf(
            NwsApi.HourlyForecastPeriod("${tomorrow}T06:00:00-08:00", 52f, "Patchy Fog"),
            NwsApi.HourlyForecastPeriod("${tomorrow}T09:00:00-08:00", 60f, "Patchy Fog"),
            NwsApi.HourlyForecastPeriod("${tomorrow}T13:00:00-08:00", 73f, "Partly Sunny")
        )

        // Act
        val result = repository.fetchFromNws(testLat, testLon, testLocationName)

        // Assert
        val tomorrowEntry = result.find { it.date == tomorrow }
        assertNotNull("Should have entry for tomorrow", tomorrowEntry)
        // Note: The synthesized string includes the midday condition
        assertEquals("Fog then Partly Sunny", tomorrowEntry?.condition)
    }
}
