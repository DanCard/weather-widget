package com.weatherwidget.data.repository

import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.getForecastsInRange
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.data.remote.OpenMeteoApi
import com.weatherwidget.testutil.TestData.dateEpoch
import com.weatherwidget.testutil.TestDatabase
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.shared.config.ForecastHorizon
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
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
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Category(LongDuration::class)
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
        every { widgetStateManager.getPrimarySource() } returns WeatherSource.OPEN_METEO
        every { widgetStateManager.getActiveDisplaySourceIds() } returns setOf(WeatherSource.OPEN_METEO.id)
        
        return ForecastRepository(
            context,
            db.forecastDao(),
            db.hourlyForecastDao(),
            db.hourlyForecastHistoryDao(),
            db.appLogDao(),
            nwsApi,
            openMeteoApi,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            widgetStateManager,
            db.climateNormalDao(),
            db.observationDao(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
    }

    private fun createCapturingRepository(
        captured: MutableList<HttpRequestData>,
        mockResponse: String,
    ): ForecastRepository {
        val context = RuntimeEnvironment.getApplication()
        val mockEngine = MockEngine { request ->
            captured += request
            respond(
                content = mockResponse,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val openMeteoApi = OpenMeteoApi(HttpClient(mockEngine), json)
        val nwsApi = mockk<NwsApi>()
        coEvery { nwsApi.getGridPoint(any(), any()) } throws Exception("NWS unavailable for integration test")
        val widgetStateManager = mockk<WidgetStateManager>(relaxed = true)
        every { widgetStateManager.isSourceVisible(any()) } returns true
        every { widgetStateManager.getVisibleSourcesOrder() } returns listOf(WeatherSource.OPEN_METEO)
        every { widgetStateManager.getPrimarySource() } returns WeatherSource.OPEN_METEO
        every { widgetStateManager.getActiveDisplaySourceIds() } returns setOf(WeatherSource.OPEN_METEO.id)
        return ForecastRepository(
            context, db.forecastDao(), db.hourlyForecastDao(), db.hourlyForecastHistoryDao(),
            db.appLogDao(), nwsApi, openMeteoApi, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), widgetStateManager, db.climateNormalDao(), db.observationDao(),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true),
        )
    }

    private fun List<HttpRequestData>.meteoForecastDays(): List<String?> =
        filter { it.url.host.contains("open-meteo") && it.url.parameters.contains("forecast_days") }
            .map { it.url.parameters["forecast_days"] }

    @Test
    fun `getWeatherData requests the maximum horizon`() = runTest {
        val minimalResponse = """
            {
                "daily": {
                    "time": ["$today", "$tomorrow"],
                    "temperature_2m_max": [72.4, 72.6],
                    "temperature_2m_min": [50.2, 51.8],
                    "weather_code": [1, 1]
                }
            }
        """.trimIndent()
        val captured = mutableListOf<HttpRequestData>()
        repository = createCapturingRepository(captured, minimalResponse)

        repository.getWeatherData(testLat, testLon, "Test", forceRefresh = true)
        assertEquals(
            "every fetch requests the maximum horizon",
            listOf(ForecastHorizon.MAX_DAYS.toString()),
            captured.meteoForecastDays(),
        )
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

    /**
     * Write-path coverage: a real fetch+save must populate hourly_forecast_history with the full
     * predicted hourly curve, tagged with the correct snapshotBucket (4h here, since OPEN_METEO is
     * stubbed as the primary source). Uses dynamically-generated FUTURE UTC hours so they survive
     * the "future only" filter regardless of wall-clock time.
     */
    @Test
    fun `fetch populates hourly_forecast_history with cloud cover and a 4h-aligned bucket`() = runTest {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val baseUtc = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
            .withMinute(0).withSecond(0).withNano(0)
        val h1 = baseUtc.plusHours(1).format(fmt)
        val h2 = baseUtc.plusHours(2).format(fmt)
        val h3 = baseUtc.plusHours(3).format(fmt)

        val mockResponse = """
            {
                "latitude": $testLat,
                "longitude": $testLon,
                "timezone": "UTC",
                "current": { "time": "${baseUtc.format(fmt)}", "temperature_2m": 60.0, "weather_code": 3 },
                "daily": {
                    "time": ["$today", "$tomorrow"],
                    "temperature_2m_max": [72.4, 72.6],
                    "temperature_2m_min": [50.2, 51.8],
                    "weather_code": [3, 3],
                    "precipitation_probability_max": [10, 20]
                },
                "hourly": {
                    "time": ["$h1", "$h2", "$h3"],
                    "temperature_2m": [60.0, 61.0, 62.0],
                    "weather_code": [3, 3, 3],
                    "precipitation_probability": [15, 20, 25],
                    "cloud_cover": [40, 55, 70]
                }
            }
        """.trimIndent()

        repository = createRepository(mockResponse)
        repository.getWeatherData(testLat, testLon, "Test Location", forceRefresh = true)

        data class HistRow(val dateTime: Long, val snapshotBucket: Long, val cloudCover: Int?, val fetchedAt: Long)
        val rows = mutableListOf<HistRow>()
        db.query(
            "SELECT dateTime, snapshotBucket, cloudCover, fetchedAt FROM hourly_forecast_history " +
                "WHERE source = ? ORDER BY dateTime",
            arrayOf<Any?>("OPEN_METEO"),
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(
                    HistRow(
                        dateTime = c.getLong(0),
                        snapshotBucket = c.getLong(1),
                        cloudCover = if (c.isNull(2)) null else c.getInt(2),
                        fetchedAt = c.getLong(3),
                    ),
                )
            }
        }

        // All three future hours captured as a snapshot.
        assertEquals("history should capture all 3 future hours", 3, rows.size)
        // Cloud cover preserved (the whole point of the history record).
        assertEquals(listOf(40, 55, 70), rows.map { it.cloudCover })
        // Each row's bucket = policy applied to its real fetchedAt, and 4h-aligned (primary source).
        rows.forEach { r ->
            assertEquals(
                ForecastHistoryPolicy.snapshotBucket(r.fetchedAt, "OPEN_METEO", setOf("OPEN_METEO")),
                r.snapshotBucket,
            )
            assertEquals("primary bucket must be 4h-aligned", 0L, r.snapshotBucket % ForecastHistoryPolicy.PRIMARY_BUCKET_MS)
        }
    }
}
