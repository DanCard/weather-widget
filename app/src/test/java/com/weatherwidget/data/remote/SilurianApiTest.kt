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
                  "precipitation_probability": 45
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
                  "precipitation_probability": 60
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
        assertEquals(75, result.daily[0].highTemp)
        assertEquals(50, result.daily[0].lowTemp)
        assertEquals("rain", result.daily[0].condition)
        assertEquals(45, result.daily[0].precipProbability)

        assertEquals(1, result.hourly.size)
        assertEquals("2026-03-02T14:00", result.hourly[0].dateTimeString)
        assertEquals(74.0f, result.hourly[0].temperature)
        assertEquals("rain", result.hourly[0].condition)
        assertEquals(60, result.hourly[0].precipProbability)
    }
}
