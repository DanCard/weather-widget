package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDao
import com.weatherwidget.data.local.WeatherEntity
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class WeatherRepositoryTest {

    private lateinit var weatherDao: WeatherDao
    private lateinit var nwsApi: NwsApi
    private lateinit var openMeteoApi: OpenMeteoApi
    private lateinit var repository: WeatherRepository

    private val testLat = 37.42
    private val testLon = -122.08
    private val testLocationName = "Test Location"
    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        weatherDao = mockk(relaxed = true)
        nwsApi = mockk()
        openMeteoApi = mockk()
        repository = WeatherRepository(weatherDao, nwsApi, openMeteoApi)
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
            NwsApi.ForecastPeriod("Today", 70, "F", "Sunny", true),
            NwsApi.ForecastPeriod("Tonight", 50, "F", "Clear", false)
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

    private fun createWeatherEntity(date: String, high: Int, low: Int) = WeatherEntity(
        date = date,
        locationLat = testLat,
        locationLon = testLon,
        locationName = testLocationName,
        highTemp = high,
        lowTemp = low,
        currentTemp = null,
        condition = "Sunny",
        isActual = false
    )
}
