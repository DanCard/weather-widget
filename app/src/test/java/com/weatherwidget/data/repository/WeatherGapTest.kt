package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.local.WeatherObservationDao
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WeatherGapTest {
    private lateinit var context: Context
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
    private lateinit var repository: WeatherRepository

    private val testLat = 37.42
    private val testLon = -122.08
    private val testLocationName = "Test Location"
    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        val sharedPrefs = mockk<SharedPreferences>(relaxed = true)
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
        weatherObservationDao = mockk(relaxed = true)

        val forecastRepo = ForecastRepository(context, weatherDao, forecastSnapshotDao, hourlyForecastDao, appLogDao, nwsApi, openMeteoApi, weatherApi, widgetStateManager, climateNormalDao)
        val currentRepo = CurrentTempRepository(context, mockk(relaxed = true), weatherObservationDao, hourlyForecastDao, appLogDao, nwsApi, openMeteoApi, weatherApi, widgetStateManager, temperatureInterpolator)

        repository =
            WeatherRepository(
                context,
                forecastRepo,
                currentRepo,
                weatherDao,
                forecastSnapshotDao,
                appLogDao,
                mockk(relaxed = true)
            )

        coEvery { weatherApi.getForecast(any(), any(), any()) } throws Exception("WeatherAPI unavailable")
    }

    @Test
    fun `getCachedDataBySource merges provider data with generic gap data`() =
        runTest {
            val nwsData = listOf(createWeatherEntity(today, 70, 50, "NWS"))
            val gapData = listOf(
                createWeatherEntity(today, 65, 45, WeatherSource.GENERIC_GAP.id, isClimateNormal = true),
                createWeatherEntity(tomorrow, 66, 46, WeatherSource.GENERIC_GAP.id, isClimateNormal = true),
            )
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "NWS") } returns nwsData
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, WeatherSource.GENERIC_GAP.id) } returns gapData
            val result = repository.getCachedDataBySource(testLat, testLon, WeatherSource.NWS)
            assertEquals(2, result.size)
            assertEquals("NWS", result.find { it.date == today }?.source)
            assertEquals(WeatherSource.GENERIC_GAP.id, result.find { it.date == tomorrow }?.source)
        }

    private fun createWeatherEntity(date: String, high: Int, low: Int, source: String, isClimateNormal: Boolean = false) =
        WeatherEntity(date, testLat, testLon, testLocationName, high.toFloat(), low.toFloat(), if (isClimateNormal) "Climate Avg" else "Sunny", false, source = source, isClimateNormal = isClimateNormal, fetchedAt = System.currentTimeMillis())
}
