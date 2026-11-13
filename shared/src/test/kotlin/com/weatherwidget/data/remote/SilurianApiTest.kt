package com.weatherwidget.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.Collections
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

/**
 * Tests the Silurian "API²" client (beta.weather.silurian.ai/api/v1): split hourly/daily endpoints,
 * X-API-KEY auth, imperial units (°F, precip in inches -> mm), and weather_code -> condition mapping.
 */
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

    private val hourlyResponse = """
        {
          "latitude": 37.77, "longitude": -122.42,
          "forecast_time": "2026-03-02T13:00:00Z",
          "timezone": "UTC", "utc_offset": 0,
          "units": { "temperature": "°F", "precipitation_accumulation": "in" },
          "hourly": [
            {
              "timestamp": "2026-03-02T14:00:00Z",
              "temperature": 74.0,
              "weather_code": "rain",
              "precipitation_probability": 60,
              "precipitation_accumulation": 0.05,
              "cloud_cover": 88
            }
          ]
        }
    """.trimIndent()

    private val dailyResponse = """
        {
          "latitude": 37.77, "longitude": -122.42,
          "forecast_time": "2026-03-02T13:00:00Z",
          "timezone": "UTC", "utc_offset": 0,
          "units": { "temperature": "°F", "precipitation_accumulation": "in" },
          "daily": [
            {
              "timestamp": "2026-03-02",
              "max_temperature": 75.0,
              "min_temperature": 50.0,
              "weather_code": "partly-cloudy-day",
              "precipitation_probability": 45,
              "precipitation_accumulation": 0.25
            }
          ]
        }
    """.trimIndent()

    private fun client(captured: MutableList<HttpRequestData>): HttpClient {
        val mockEngine = MockEngine { request ->
            captured += request
            val body = if (request.url.encodedPath.contains("/forecast/daily")) dailyResponse else hourlyResponse
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(mockEngine) { install(ContentNegotiation) { json(json) } }
    }

    @Test
    fun `getForecast parses API2 hourly and daily responses`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        val result = SilurianApi(client(requests), json) { "test-api-key" }.getForecast(37.7749, -122.4194)

        // Hourly: temperature stays °F, precip converts inches -> mm (0.05in * 25.4), code -> condition.
        assertEquals(1, result.hourly.size)
        assertEquals(1772460000000L, result.hourly[0].dateTime)
        assertEquals(74.0f, result.hourly[0].temperature)
        assertEquals("Rain", result.hourly[0].condition)
        assertEquals(60, result.hourly[0].precipProbability)
        assertEquals(1.27f, result.hourly[0].precipAmountMm!!, 0.001f)
        assertEquals(88, result.hourly[0].cloudCover)
        assertEquals("SILURIAN", result.hourly[0].source)

        // Daily: max/min map to high/low; precip 0.25in -> 6.35mm; code -> condition.
        assertEquals(1, result.daily.size)
        assertEquals("2026-03-02", result.daily[0].date)
        assertEquals(75.0f, result.daily[0].highTemp)
        assertEquals(50.0f, result.daily[0].lowTemp)
        assertEquals("Partly Cloudy", result.daily[0].condition)
        assertEquals(45, result.daily[0].precipProbability)
        assertEquals(6.35f, result.daily[0].precipAmountMm!!, 0.001f)

        // API² has no current-observation endpoint.
        assertNull(result.providerCurrentTemp)
        assertNull(result.providerCurrentObservedAt)
    }

    @Test
    fun `getForecast hits both endpoints with X-API-KEY, imperial units, and include_past`() = runTest {
        val requests = Collections.synchronizedList(mutableListOf<HttpRequestData>())
        SilurianApi(client(requests), json) { "test-api-key" }.getForecast(37.7749, -122.4194)

        val hourly = requests.single { it.url.encodedPath.endsWith("/forecast/hourly") }
        val daily = requests.single { it.url.encodedPath.endsWith("/forecast/daily") }

        // Auth header on every call.
        listOf(hourly, daily).forEach { req ->
            assertEquals("test-api-key", req.headers["X-API-KEY"])
            assertEquals("imperial", req.url.parameters["units"])
            assertEquals("37.7749", req.url.parameters["latitude"])
            assertEquals("-122.4194", req.url.parameters["longitude"])
        }
        // Past hours requested only on the hourly endpoint (drives the actual line).
        assertEquals("true", hourly.url.parameters["include_past"])
        assertTrue(hourly.url.host == "earth.weather.silurian.ai")
    }
}
