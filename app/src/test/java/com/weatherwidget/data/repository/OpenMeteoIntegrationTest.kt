package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@RunWith(RobolectricTestRunner::class)
class OpenMeteoIntegrationTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: ForecastRepository
    private val json = Json { ignoreUnknownKeys = true }
    private val testLat = 51.5074
    private val testLon = -0.1278

    private val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)

    @Before
    fun setup() {
        db = TestDatabase.create()
    }

    @After
    fun tearDown() = db.close()

    private fun createRepository(mockResponse: String): ForecastRepository {
        val context = RuntimeEnvironment.getApplication()
        val mockEngine = MockEngine { request ->
            respond(
                content = mockResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)
        val openMeteoApi = OpenMeteoApi(httpClient, json)
        val nwsApi = mockk<NwsApi>()
        coEvery { nwsApi.getGridPoint(any(), any()) } throws Exception("NWS unavailable for integration test")
        
        val widgetStateManager = mockk<WidgetStateManager>(relaxed = true)
        every { widgetStateManager.isSourceVisible(any()) } returns true
        every { widgetStateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.OPEN_METEO)
        
        return ForecastRepository(context, db.forecastDao(), db.hourlyForecastDao(), db.appLogDao(), nwsApi, openMeteoApi, mockk(relaxed = true), mockk(relaxed = true), // WeatherApi
            widgetStateManager, db.climateNormalDao(), db.observationDao(), mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `fetching from Open-Meteo preserves today decimals and rounds tomorrow in DB`() = runTest {
        // Mock response with 72.4 for today and 72.6 for tomorrow
        val mockResponse = """
            {
                "latitude": $testLat,
                "longitude": $testLon,
                "timezone": "UTC",
                "current": {
                    "time": "${today}T12:00",
                    "temperature_2m": 72.4,
                    "weather_code": 1
                },
                "daily": {
                    "time": ["$today", "$tomorrow"],
                    "temperature_2m_max": [72.4, 72.6],
                    "temperature_2m_min": [50.2, 51.8],
                    "weather_code": [1, 1],
                    "precipitation_probability_max": [10, 20]
                },
                "hourly": {
                    "time": ["${today}T12:00"],
                    "temperature_2m": [72.4],
                    "weather_code": [1],
                    "precipitation_probability": [10]
                }
            }
        """.trimIndent()

        repository = createRepository(mockResponse)

        // Trigger full network fetch
        repository.getWeatherData(testLat, testLon, "Test Location", forceRefresh = true)

        // Query the 'forecasts' table (snapshots)
        val snapshots = db.forecastDao().getForecastsInRange(dateEpoch(today), dateEpoch(tomorrow), testLat, testLon)
            .filter { it.source == "OPEN_METEO" }
            .sortedBy { it.targetDate }

        assertEquals("Should have 2 snapshots (today and tomorrow)", 2, snapshots.size)

        // 1. Verify Today's high (Should be EXACT 72.4)
        val todaySnap = snapshots.find { it.targetDate == dateEpoch(today) }!!
        assertEquals("Today high should preserve decimal", 72.4f, todaySnap.highTemp!!, 0.001f)
        assertEquals("Today low should preserve decimal", 50.2f, todaySnap.lowTemp!!, 0.001f)

        // 2. Verify Tomorrow's high (Should be ROUNDED to 73.0)
        val tomorrowSnap = snapshots.find { it.targetDate == dateEpoch(tomorrow) }!!
        assertEquals("Tomorrow high should be rounded", 73.0f, tomorrowSnap.highTemp!!, 0.001f)
        assertEquals("Tomorrow low should be rounded", 52.0f, tomorrowSnap.lowTemp!!, 0.001f)
    }
}
