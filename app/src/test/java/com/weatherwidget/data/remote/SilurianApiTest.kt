package com.weatherwidget.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class SilurianApiTest {
    private lateinit var json: Json

    @Before
    fun setup() {
        json = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    }

    @Test
    fun `getForecast parses silurian daily and hourly responses correctly`() = runTest {
        val dailyMockResponse = """
            {
              "daily": [
                {
                  "timestamp": "2026-03-02",
                  "max_temperature": 75.0,
                  "min_temperature": 50.0,
                  "weather_code": "rain",
                  "precipitation_probability": 45,
                  "precipitation_accumulation": 0.25
                }
              ]
            }
        """.trimIndent()

        val hourlyMockResponse = """
            {
              "hourly": [
                {
                  "timestamp": "2026-03-02T14:00:00",
                  "temperature": 74.0,
                  "weather_code": "rain",
                  "precipitation_probability": 60,
                  "precipitation_accumulation": 0.05
                }
              ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/forecast/daily") -> {
                    respond(
                        content = dailyMockResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                request.url.encodedPath.endsWith("/forecast/hourly") -> {
                    respond(
                        content = hourlyMockResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val silurianApi = SilurianApi(httpClient, json)
        silurianApi.setApiKeyForTesting("test-api-key")
        val result = silurianApi.getForecast(37.7749, -122.4194)

        assertNotNull(result)
        assertEquals(74.0f, result.currentTemp) 
        assertEquals("rain", result.currentCondition)

        assertEquals(1, result.daily.size)
        assertEquals("2026-03-02", result.daily[0].date)
        assertEquals(75.0f, result.daily[0].highTemp)
        assertEquals(50.0f, result.daily[0].lowTemp)
        assertEquals("rain", result.daily[0].condition)
        assertEquals(45, result.daily[0].precipProbability)
        assertEquals(6.35f, result.daily[0].precipAmountMm!!, 0.001f)

        assertEquals(1, result.hourly.size)
        assertEquals(com.weatherwidget.testutil.TestData.toEpoch("2026-03-02T14:00"), result.hourly[0].dateTime)
        assertEquals(74.0f, result.hourly[0].temperature)
        assertEquals("rain", result.hourly[0].condition)
        assertEquals(60, result.hourly[0].precipProbability)
        assertEquals(1.27f, result.hourly[0].precipAmountMm!!, 0.001f)
    }

    @Test
    fun `getCurrent makes a single hourly request and returns the nearest-to-now reading`() = runTest {
        val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val nowHour = java.time.LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        // Series spans before/at/after now; the entry at the current hour must win.
        val hourlyMockResponse = """
            {
              "hourly": [
                { "timestamp": "${nowHour.minusHours(3).format(fmt)}", "temperature": 50.0, "weather_code": "clear" },
                { "timestamp": "${nowHour.format(fmt)}", "temperature": 68.0, "weather_code": "cloudy" },
                { "timestamp": "${nowHour.plusHours(3).format(fmt)}", "temperature": 72.0, "weather_code": "clear" }
              ]
            }
        """.trimIndent()

        var hourlyCalls = 0
        var historyCalls = 0
        val mockEngine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/forecast/hourly") -> {
                    hourlyCalls += 1
                    respond(
                        content = hourlyMockResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                request.url.encodedPath.endsWith("/history/hourly") -> {
                    historyCalls += 1
                    respond("", HttpStatusCode.OK)
                }
                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val silurianApi = SilurianApi(httpClient, json)
        silurianApi.setApiKeyForTesting("test-api-key")
        val reading = silurianApi.getCurrent(37.7749, -122.4194)

        assertNotNull(reading)
        assertEquals(68.0f, reading!!.temperature)
        assertEquals("cloudy", reading.condition)
        assertNotNull(reading.observedAt)
        // Lightweight: exactly one hourly request, and no 3-day history loop.
        assertEquals(1, hourlyCalls)
        assertEquals(0, historyCalls)
    }
}
