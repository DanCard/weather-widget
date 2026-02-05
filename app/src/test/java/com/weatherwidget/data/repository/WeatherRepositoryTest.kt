package com.weatherwidget.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.weatherwidget.data.ApiLogger
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.HourlyForecastDao
import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.util.TemperatureInterpolator
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WeatherRepositoryTest {

    private lateinit var context: Context
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var weatherDao: WeatherDao
    private lateinit var forecastSnapshotDao: ForecastSnapshotDao
    private lateinit var hourlyForecastDao: HourlyForecastDao
    private lateinit var appLogDao: AppLogDao
    private lateinit var nwsApi: NwsApi
    private lateinit var openMeteoApi: OpenMeteoApi
    private lateinit var widgetStateManager: WidgetStateManager
    private lateinit var apiLogger: ApiLogger
    private lateinit var temperatureInterpolator: TemperatureInterpolator
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
        widgetStateManager = mockk(relaxed = true)
        apiLogger = mockk(relaxed = true)
        temperatureInterpolator = TemperatureInterpolator()

        repository = WeatherRepository(
            context,
            weatherDao,
            forecastSnapshotDao,
            hourlyForecastDao,
            appLogDao,
            nwsApi,
            openMeteoApi,
            widgetStateManager,
            apiLogger,
            temperatureInterpolator
        )
    }

    @Test
    fun `lastNetworkFetchTime is persisted in SharedPreferences`() = runTest {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { sharedPrefs.edit() } returns editor
        
        // Mock getLong to return a value we can verify later
        var capturedTime = 0L
        every { editor.putLong("last_network_fetch_time", any()) } answers {
            capturedTime = secondArg()
            editor
        }
        every { sharedPrefs.getLong("last_network_fetch_time", 0L) } answers { capturedTime }

        // Mock cache to be empty to force network fetch
        coEvery { weatherDao.getWeatherRange(any(), any(), any(), any()) } returns emptyList()
        coEvery { nwsApi.getGridPoint(any(), any()) } throws Exception("Forced failure to stop execution")

        try {
            repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)
        } catch (e: Exception) {
            // Expected
        }

        // Verify the internal setter was called by checking if the mock now returns a non-zero time
        assertTrue("lastNetworkFetchTime should be greater than 0", capturedTime > 0)
    }

    @Test
    fun `getWeatherData preserves history when API returns partial data`() = runTest {
        // 1. Setup: DB has yesterday's actual history
        val existingHistory = createWeatherEntity(yesterday, 65, 45, isActual = true, source = "NWS")
        
        // Mock getCachedData (internal calls)
        coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns listOf(existingHistory)
        coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "NWS") } returns listOf(existingHistory)
        coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "Generic") } returns emptyList()

        // 2. Setup: API only returns today's forecast
        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com")
        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod("Today", "2026-02-04T06:00:00-08:00", 70, "F", "Sunny", true)
        )
        // Disable other APIs for simplicity
        coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
        coEvery { openMeteoApi.getForecast(any(), any(), any()) } throws Exception("Skipped")

        // 3. Act
        repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

        // 4. Assert: verify insertAll contains both yesterday (history) and today (forecast)
        coVerify { 
            weatherDao.insertAll(match { list ->
                list.size >= 2 && 
                list.any { it.date == yesterday && it.isActual } &&
                list.any { it.date == today && !it.isActual }
            })
        }
    }

    @Test
    fun `getWeatherData logs MERGE_CONFLICT when Forecast threatens History`() = runTest {
        // 1. Setup: DB has today's actual data
        val existingHistory = createWeatherEntity(today, 68, 44, isActual = true, source = "NWS")
        coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns listOf(existingHistory)
        coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "NWS") } returns listOf(existingHistory)
        coEvery { weatherDao.getWeatherRangeBySource(any(), any(), testLat, testLon, "Generic") } returns emptyList()

        // 2. Setup: API returns a new forecast for today
        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com")
        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod("Today", "2026-02-04T06:00:00-08:00", 72, "F", "Sunny", true)
        )
        coEvery { nwsApi.getHourlyForecast(any()) } returns emptyList()
        coEvery { openMeteoApi.getForecast(any(), any(), any()) } throws Exception("Skipped")

        // 3. Act
        repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = true)

        // 4. Assert: Verify audit log entry
        coVerify { appLogDao.insert(match { it.tag == "MERGE_CONFLICT" }) }
    }

    @Test
    fun `getWeatherData returns cached data when not forcing refresh`() = runTest {
        val cachedData = listOf(createWeatherEntity(today, 70, 50))
        coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns cachedData

        val result = repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        coVerify(exactly = 0) { nwsApi.getGridPoint(any(), any()) }
        coVerify(exactly = 0) { openMeteoApi.getForecast(any(), any(), any()) }
    }

    @Test
    fun `getWeatherData fetches from NWS when cache is empty`() = runTest {
        // First call returns empty cache, second returns data after insert
        coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList() andThen listOf(
            createWeatherEntity(today, 70, 50)
        )

        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com")
        coEvery { nwsApi.getGridPoint(testLat, testLon) } returns gridPoint
        coEvery { nwsApi.getForecast(gridPoint) } returns listOf(
            NwsApi.ForecastPeriod("Today", "2026-02-02T06:00:00-08:00", 70, "F", "Sunny", true),
            NwsApi.ForecastPeriod("Tonight", "2026-02-02T18:00:00-08:00", 50, "F", "Clear", false)
        )

        val result = repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = false)

        assertTrue(result.isSuccess)
        coVerify { nwsApi.getGridPoint(testLat, testLon) }
        coVerify { weatherDao.insertAll(any()) }
    }

    @Test
    fun `getWeatherData falls back to OpenMeteo when NWS fails`() = runTest {
        coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList() andThen listOf(
            createWeatherEntity(today, 72, 48)
        )

        // NWS fails
        coEvery { nwsApi.getGridPoint(testLat, testLon) } throws Exception("NWS unavailable")

        // OpenMeteo succeeds
        coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } returns OpenMeteoApi.WeatherForecast(
            currentTemp = 65,
            currentWeatherCode = 0,
            daily = listOf(OpenMeteoApi.DailyForecast(today, 72, 48, 0))
        )
        coEvery { openMeteoApi.weatherCodeToCondition(any()) } returns "Clear"

        val result = repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = false)

        assertTrue(result.isSuccess)
        coVerify { openMeteoApi.getForecast(testLat, testLon, any()) }
    }

    @Test
    fun `getWeatherData returns failure when both APIs fail and no cache`() = runTest {
        coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList()
        coEvery { nwsApi.getGridPoint(testLat, testLon) } throws Exception("NWS unavailable")
        coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } throws Exception("OpenMeteo unavailable")

        val result = repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = false)

        assertTrue(result.isFailure)
    }

    @Test
    fun `getWeatherData returns cached data when both APIs fail`() = runTest {
        val cachedData = listOf(createWeatherEntity(today, 70, 50))

        // First call empty to trigger API, second call has cached data for fallback
        coEvery { weatherDao.getWeatherRange(any(), any(), testLat, testLon) } returns emptyList() andThen cachedData

        coEvery { nwsApi.getGridPoint(testLat, testLon) } throws Exception("NWS unavailable")
        coEvery { openMeteoApi.getForecast(testLat, testLon, any()) } throws Exception("OpenMeteo unavailable")

        val result = repository.getWeatherData(testLat, testLon, testLocationName, forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
    }

    @Test
    fun `getLatestLocation returns location from latest weather entry`() = runTest {
        val entity = createWeatherEntity(today, 70, 50)
        coEvery { weatherDao.getLatestWeather() } returns entity

        val location = repository.getLatestLocation()

        assertNotNull(location)
        assertEquals(testLat, location?.first)
        assertEquals(testLon, location?.second)
    }

    @Test
    fun `getLatestLocation returns null when no weather data`() = runTest {
        coEvery { weatherDao.getLatestWeather() } returns null

        val location = repository.getLatestLocation()

        assertNull(location)
    }

    private fun createWeatherEntity(
        date: String, 
        high: Int, 
        low: Int, 
        isActual: Boolean = false,
        source: String = "NWS"
    ) = WeatherEntity(
        date = date,
        locationLat = testLat,
        locationLon = testLon,
        locationName = testLocationName,
        highTemp = high,
        lowTemp = low,
        currentTemp = null,
        condition = "Sunny",
        isActual = isActual,
        source = source,
        stationId = null,
        fetchedAt = System.currentTimeMillis()
    )
}
