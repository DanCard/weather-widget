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
        val response = """
            {
              "current": {
                "time": "2026-03-02T14:00:00Z",
                "temp": 74.0,
                "condition": "rain"
              },
              "daily": [
                {
                  "date": "2026-03-02",
                  "temp_max": 75.0,
                  "temp_min": 50.0,
                  "condition": "rain",
                  "precip_prob": 45,
                  "precip_amount": 6.35
                }
              ],
              "hourly": [
                {
                  "time": "2026-03-02T14:00:00Z",
                  "temp": 74.0,
                  "condition": "rain",
                  "precip_prob": 60,
                  "precip_amount": 1.27
                }
              ]
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            respond(
                content = response,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val silurianApi = SilurianApi(httpClient, json) { "test-api-key" }
        val result = silurianApi.getForecast(37.7749, -122.4194)

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
        assertEquals(1772460000000L, result.hourly[0].dateTime)
        assertEquals(74.0f, result.hourly[0].temperature)
        assertEquals("rain", result.hourly[0].condition)
        assertEquals(60, result.hourly[0].precipProbability)
        assertEquals(1.27f, result.hourly[0].precipAmountMm!!, 0.001f)
    }

    @Test
    fun `getForecast exposes current reading`() = runTest {
        val response = """
            {
              "current": {
                "time": "2026-03-02T14:00:00Z",
                "temp": 68.0,
                "condition": "cloudy"
              },
              "hourly": [],
              "daily": []
            }
        """.trimIndent()

        val mockEngine = MockEngine { request ->
            respond(
                content = response,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }

        val httpClient = HttpClient(mockEngine) {
            install(ContentNegotiation) {
                json(json)
            }
        }

        val silurianApi = SilurianApi(httpClient, json) { "test-api-key" }
        val result = silurianApi.getForecast(37.7749, -122.4194)

        assertEquals(68.0f, result.currentTemp)
        assertEquals("cloudy", result.currentCondition)
        assertEquals(1772460000000L, result.currentObservedAt)
    }
}
