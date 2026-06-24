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
import java.time.ZoneId
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
class OpenMeteoDayNightPrecipIntegrationTest {
    private lateinit var db: WeatherDatabase
    private lateinit var repository: ForecastRepository
    private val json = Json { ignoreUnknownKeys = true }
    private val testLat = 51.5074
    private val testLon = -0.1278

    private val today = LocalDate.now()
    private val tomorrow = today.plusDays(1)
    private val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val tomorrowStr = tomorrow.format(DateTimeFormatter.ISO_LOCAL_DATE)
    private val zone = ZoneId.systemDefault()

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
        every { widgetStateManager.getPrimarySource() } returns WeatherSource.OPEN_METEO
        every { widgetStateManager.getActiveDisplaySourceIds() } returns setOf(WeatherSource.OPEN_METEO.id)

        return ForecastRepository(
            context = context,
            forecastDao = db.forecastDao(),
            hourlyForecastDao = db.hourlyForecastDao(),
            hourlyForecastHistoryDao = db.hourlyForecastHistoryDao(),
            appLogDao = db.appLogDao(),
            nwsApi = nwsApi,
            openMeteoApi = openMeteoApi,
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
            nwsForecastMapper = mockk(relaxed = true)
        )
    }

    @Test
    fun `fetching from Open-Meteo extracts day and night precip probability from hourly`() = runTest {
        // Construct times that fall into the 8AM-8PM and 8PM-8AM windows (Local Time)
        // Convert local times to UTC ISO strings for the Open-Meteo mock response
        
        // Daytime: 10:00 AM
        val tDay = today.atTime(10, 0).atZone(zone).withZoneSameInstant(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        // Nighttime (same day evening): 10:00 PM
        val tNight1 = today.atTime(22, 0).atZone(zone).withZoneSameInstant(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
        // Nighttime (next day early morning): 4:00 AM
        val tNight2 = tomorrow.atTime(4, 0).atZone(zone).withZoneSameInstant(ZoneId.of("UTC")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))

        val mockResponse = """
            {
                "latitude": $testLat,
                "longitude": $testLon,
                "timezone": "${zone.id}",
                "current": {
                    "time": "${todayStr}T12:00",
                    "temperature_2m": 70.0,
                    "weather_code": 1
                },
                "daily": {
                    "time": ["$todayStr"],
                    "temperature_2m_max": [75.0],
                    "temperature_2m_min": [55.0],
                    "weather_code": [1],
                    "precipitation_probability_max": [80]
                },
                "hourly": {
                    "time": ["$tDay", "$tNight1", "$tNight2"],
                    "temperature_2m": [70.0, 60.0, 55.0],
                    "weather_code": [1, 1, 1],
                    "precipitation_probability": [20, 80, 50]
                }
            }
        """.trimIndent()

        repository = createRepository(mockResponse)

        // Trigger full network fetch
        repository.getWeatherData(testLat, testLon, "Test Location", forceRefresh = true)

        // Query the 'forecasts' table
        val snapshots = db.forecastDao().getForecastsInRange(dateEpoch(todayStr), dateEpoch(tomorrowStr), testLat, testLon)
            .filter { it.source == "OPEN_METEO" }
        
        assertEquals(1, snapshots.size)
        val todaySnap = snapshots.first()

        assertEquals("Daytime probability should be max of 8AM-8PM (20)", 20, todaySnap.daytimePrecipProbability)
        assertEquals("Nighttime probability should be max of 8PM-8AM (80, 50)", 80, todaySnap.nighttimePrecipProbability)
        assertEquals("Daily probability should be preserved", 80, todaySnap.precipProbability)
    }
}
