package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.CurrentTempDao
import com.weatherwidget.data.local.ForecastEntity
import com.weatherwidget.data.local.ObservationDao
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
import kotlin.math.roundToInt

class WeatherRepositoryTest {
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
    private lateinit var currentTempDao: CurrentTempDao

    private lateinit var forecastRepository: ForecastRepository
    private lateinit var currentTempRepository: CurrentTempRepository
    private lateinit var repository: WeatherRepository

    private val testLat = 37.42
    private val testLon = -122.08
    private val testLocationName = "Test Location"
    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

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
        currentTempDao = mockk(relaxed = true)

        forecastRepository = ForecastRepository(
            context, forecastDao, hourlyForecastDao, appLogDao,
            nwsApi, openMeteoApi, weatherApi, widgetStateManager, climateNormalDao, observationDao
        )
        currentTempRepository = CurrentTempRepository(
            context, currentTempDao, observationDao, hourlyForecastDao, appLogDao,
            nwsApi, openMeteoApi, weatherApi, widgetStateManager, temperatureInterpolator
        )

        repository =
            WeatherRepository(
                context,
                forecastRepository,
                currentTempRepository,
                forecastDao,
                appLogDao,
                currentTempDao
            )

        coEvery { weatherApi.getForecast(any(), any(), any()) } throws Exception("WeatherAPI unavailable")
        every { widgetStateManager.isSourceVisible(any()) } returns true
    }

    @Test
    fun `lastFullFetchTime is persisted via SharedPreferences`() =
        runTest {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPrefs.edit() } returns editor
            val capturedTimes = mutableListOf<Long>()
            every { editor.putLong("last_full_fetch_time", any()) } answers {
                capturedTimes.add(secondArg())
                editor
            }
            every { sharedPrefs.getLong("last_full_fetch_time", 0L) } answers {
                capturedTimes.lastOrNull() ?: 0L
            }
            coEvery { forecastDao.getForecastsInRange(any(), any(), any(), any()) } returns emptyList()
            coEvery { nwsApi.getGridPoint(any(), any()) } throws Exception("Forced failure")
            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)
            assertTrue("SharedPreferences should have been written to", capturedTimes.size >= 1)
        }

    @Test
    fun `getWeatherData returns cached data when not forcing refresh`() =
        runTest {
            val recentFetch = System.currentTimeMillis() - 15 * 60 * 1000
            val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val cachedData = listOf(
                createForecastEntity(today, 70, 50).copy(source = WeatherSource.NWS.id, fetchedAt = recentFetch),
                createForecastEntity(today, 70, 50).copy(source = WeatherSource.OPEN_METEO.id, fetchedAt = recentFetch),
                createForecastEntity(today, 70, 50).copy(source = WeatherSource.WEATHER_API.id, fetchedAt = recentFetch),
                createForecastEntity(tomorrow, 75, 55).copy(source = WeatherSource.NWS.id, fetchedAt = recentFetch),
                createForecastEntity(tomorrow, 75, 55).copy(source = WeatherSource.OPEN_METEO.id, fetchedAt = recentFetch),
                createForecastEntity(tomorrow, 75, 55).copy(source = WeatherSource.WEATHER_API.id, fetchedAt = recentFetch),
            )
            coEvery { forecastDao.getLatestForecastsInRange(any(), any(), testLat, testLon) } returns cachedData
            val result = repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = false)
            assertTrue(result.isSuccess)
            assertEquals(6, result.getOrNull()?.size)
            coVerify(exactly = 0) { nwsApi.getGridPoint(any(), any()) }
        }

    @Test
    fun `refreshCurrentTemperature updates only current temp on existing today row`() =
        runTest {
            val editor = mockk<SharedPreferences.Editor>(relaxed = true)
            every { sharedPrefs.edit() } returns editor
            every { sharedPrefs.getLong("last_current_temp_fetch_time", 0L) } returns 0L
            coEvery { openMeteoApi.getCurrent(any(), any()) } returns OpenMeteoApi.CurrentReading(61.2f, 1)
            every { openMeteoApi.weatherCodeToCondition(any()) } returns "Mostly Clear"
            val result = repository.refreshCurrentTemperature(testLat, testLon, testLocationName, source = WeatherSource.OPEN_METEO)
            assertTrue(result.isSuccess)
            coVerify { currentTempDao.insert(match { it.source == WeatherSource.OPEN_METEO.id && it.temperature == 61.2f }) }
        }

    private fun createForecastEntity(date: String, high: Int, low: Int, source: String = "NWS") =
        ForecastEntity(
            targetDate = date,
            forecastDate = date,
            locationLat = testLat,
            locationLon = testLon,
            locationName = testLocationName,
            highTemp = high.toFloat(),
            lowTemp = low.toFloat(),
            condition = "Sunny",
            source = source,
            fetchedAt = System.currentTimeMillis()
        )
}
