package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.*
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.data.remote.WeatherApi
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.OffsetDateTime

class WeatherRepositoryNwsParallelTest {
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
    private lateinit var weatherObservationDao: WeatherObservationDao
    private lateinit var currentTempDao: CurrentTempDao
    private lateinit var repository: WeatherRepository

    private val testLat = 37.42
    private val testLon = -122.08

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
        openMeteoApi = mockk(relaxed = true)
        weatherApi = mockk(relaxed = true)
        widgetStateManager = mockk(relaxed = true)
        temperatureInterpolator = TemperatureInterpolator()
        climateNormalDao = mockk(relaxed = true)
        weatherObservationDao = mockk(relaxed = true)
        currentTempDao = mockk(relaxed = true)

        repository = WeatherRepository(
            context, weatherDao, forecastSnapshotDao, hourlyForecastDao,
            appLogDao, nwsApi, openMeteoApi, weatherApi,
            widgetStateManager, temperatureInterpolator, climateNormalDao,
            weatherObservationDao, currentTempDao
        )

        every { widgetStateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.NWS)
        every { widgetStateManager.isSourceVisible(WeatherSource.NWS) } returns true
    }

    @Test
    fun `refreshCurrentTemperature uses the first station even if it's personal, but saves all observations`() = runTest {
        // Setup: NWS returns a list with a closer personal station first, then an official one
        val stationsUrl = "https://api.weather.gov/gridpoints/MTR/93,87/stations"
        val gridPoint = NwsApi.GridPointInfo("MTR", 93, 87, "https://api.weather.gov/forecast", stationsUrl)
        
        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        // Original NWS order: AW020 is closer than KNUQ.
        coEvery { nwsApi.getObservationStations(stationsUrl) } returns listOf(
            NwsApi.StationInfo("AW020", "AE6EO", 37.42, -122.08, NwsApi.StationType.PERSONAL),
            NwsApi.StationInfo("KNUQ", "Moffett Field", 37.41, -122.05, NwsApi.StationType.OFFICIAL)
        )
        
        // Mock observations:
        // AW020 (Personal) - 73F
        // KNUQ (Official) - 66F
        val now = OffsetDateTime.now().toString()
        coEvery { nwsApi.getLatestObservationDetailed("AW020") } returns NwsApi.Observation(now, 22.78f, "Sunny", "AW020")
        coEvery { nwsApi.getLatestObservationDetailed("KNUQ") } returns NwsApi.Observation(now, 18.89f, "Clear", "Moffett Field")

        // Mock existing row
        val existing = WeatherEntity("2026-02-26", testLat, testLon, "Test", 70f, 50f, "Clear", true, false, "NWS", null, null, 0L)
        coEvery { weatherDao.getWeatherForDateBySource(any(), testLat, testLon, "NWS") } returns existing

        // Act
        repository.refreshCurrentTemperature(testLat, testLon, "Test", source = WeatherSource.NWS, force = true)

        // Assert:
        // 1. AW020 (Personal) SHOULD be used for main currentTemp because it's first in NWS's list.
        //    73F = 22.78C * 1.8 + 32.
        coVerify {
            currentTempDao.insert(match { it.temperature > 72f })
        }
        
        // 2. Both observations should still be saved for discrepancy analysis
        coVerify {
            weatherObservationDao.insertAll(match { list ->
                list.size == 2 && 
                list.any { it.stationId == "AW020" && it.temperature > 72f && it.stationType == "PERSONAL" } &&
                list.any { it.stationId == "KNUQ" && it.temperature < 67f && it.stationType == "OFFICIAL" }
            })
        }
    }
}
