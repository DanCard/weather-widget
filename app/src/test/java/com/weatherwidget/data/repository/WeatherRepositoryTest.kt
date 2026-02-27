package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.ClimateNormalDao
import com.weatherwidget.data.local.ClimateNormalEntity
import com.weatherwidget.data.local.CurrentTempDao
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
import kotlin.math.roundToInt

class WeatherRepositoryTest {
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
        currentTempDao = mockk(relaxed = true)

        forecastRepository = ForecastRepository(
            context, weatherDao, forecastSnapshotDao, hourlyForecastDao, appLogDao,
            nwsApi, openMeteoApi, weatherApi, widgetStateManager, climateNormalDao
        )
        currentTempRepository = CurrentTempRepository(
            context, currentTempDao, weatherObservationDao, hourlyForecastDao, appLogDao,
            nwsApi, openMeteoApi, weatherApi, widgetStateManager, temperatureInterpolator
        )

        repository =
            WeatherRepository(
                context,
                forecastRepository,
                currentTempRepository,
                weatherDao,
                forecastSnapshotDao,
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
            coEvery { weatherDao.getWeatherRange(any(), any(), any(), any()) } returns emptyList()
            coEvery { nwsApi.getGridPoint(any(), any()) } throws Exception("Forced failure")
            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)
            assertTrue("SharedPreferences should have been written to", capturedTimes.size >= 1)
        }

    @Test
    fun `getWeatherData preserves history when API returns partial data`() =
        runTest {
            val existingHistory = createWeatherEntity(yesterday, 65, 45, isActual = true, source = "NWS")
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns listOf(existingHistory)
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, any()) } returns emptyList()
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "NWS") } returns listOf(existingHistory)
            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/fcst", "https://example.com/obs")
            coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
            coEvery { nwsApi.getForecast(any()) } returns listOf(NwsApi.ForecastPeriod("Today", "${today}T06:00:00-08:00", 70, "F", "Sunny", true))
            coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
            coEvery { nwsApi.getObservationStations(any()) } returns emptyList()
            coEvery { openMeteoApi.getForecast(any(), any(), any()) } throws Exception("Skipped")
            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)
            coVerify { weatherDao.insertAll(match { list -> list.size >= 2 && list.any { it.date == yesterday && it.isActual } && list.any { it.date == today && !it.isActual } }) }
        }

    @Test
    fun `getWeatherData logs MERGE_CONFLICT when Forecast threatens History`() =
        runTest {
            val existingHistory = createWeatherEntity(today, 68, 44, isActual = true, source = "NWS")
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns listOf(existingHistory)
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "NWS") } returns listOf(existingHistory)
            coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "Generic") } returns emptyList()
            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/fcst", "https://example.com/obs")
            coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
            coEvery { nwsApi.getForecast(gridPoint) } returns listOf(NwsApi.ForecastPeriod("Today", "${today}T06:00:00-08:00", 72, "F", "Sunny", true))
            coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
            coEvery { nwsApi.getObservationStations(any()) } returns emptyList()
            coEvery { openMeteoApi.getForecast(any(), any(), any()) } throws Exception("Skipped")
            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)
            coVerify { appLogDao.insert(match { it.tag == "MERGE_CONFLICT" }) }
        }

    @Test
    fun `getWeatherData returns cached data when not forcing refresh`() =
        runTest {
            val recentFetch = System.currentTimeMillis() - 15 * 60 * 1000
            val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
            val cachedData = listOf(
                createWeatherEntity(today, 70, 50).copy(source = WeatherSource.NWS.id, fetchedAt = recentFetch),
                createWeatherEntity(today, 70, 50).copy(source = WeatherSource.OPEN_METEO.id, fetchedAt = recentFetch),
                createWeatherEntity(today, 70, 50).copy(source = WeatherSource.WEATHER_API.id, fetchedAt = recentFetch),
                createWeatherEntity(tomorrow, 75, 55).copy(source = WeatherSource.NWS.id, fetchedAt = recentFetch),
                createWeatherEntity(tomorrow, 75, 55).copy(source = WeatherSource.OPEN_METEO.id, fetchedAt = recentFetch),
                createWeatherEntity(tomorrow, 75, 55).copy(source = WeatherSource.WEATHER_API.id, fetchedAt = recentFetch),
            )
            coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns cachedData
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

    private fun createWeatherEntity(date: String, high: Int, low: Int, isActual: Boolean = false, source: String = "NWS") =
        WeatherEntity(date, testLat, testLon, testLocationName, high.toFloat(), low.toFloat(), "Sunny", isActual, source = source, fetchedAt = System.currentTimeMillis())
}
