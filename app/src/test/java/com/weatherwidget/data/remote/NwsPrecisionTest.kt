package com.weatherwidget.data.remote

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import kotlin.math.roundToInt

class NwsPrecisionTest {

    private lateinit var json: Json

    @Before
    fun setup() {
        json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    @Test
    fun `getHourlyForecast parses float temperatures correctly`() = runTest {
        val forecastResponse = """
            {
                "properties": {
                    "periods": [
                        {
                            "startTime": "2026-02-01T10:00:00-08:00",
                            "temperature": 72.6,
                            "temperatureUnit": "F",
                            "shortForecast": "Sunny"
                        }
                    ]
                }
            }
        """.trimIndent()

        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = forecastResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
        }

        val api = NwsApi(client, json)
        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/forecast")
        val periods = api.getHourlyForecast(gridPoint)

        assertEquals(1, periods.size)
        // Verify float precision is maintained
        assertEquals(72.6f, periods[0].temperature)
        // Verify rounding logic works as expected for UI display (if it were rounded)
        assertEquals(73, periods[0].temperature.roundToInt())
    }

    @Test
    fun `getObservations parses high precision Celsius correctly`() = runTest {
        val observationResponse = """
            {
                "features": [
                    {
                        "properties": {
                            "timestamp": "2026-02-05T15:00:00+00:00",
                            "temperature": {
                                "value": 22.8
                            },
                            "textDescription": "Clear"
                        }
                    }
                ]
            }
        """.trimIndent()

        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = observationResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
        }

        val api = NwsApi(client, json)
        val observations = api.getObservations("KSFO", "start", "end")

        assertEquals(1, observations.size)
        assertEquals(22.8f, observations[0].temperatureCelsius)
        
        // Test our conversion logic (22.8 * 1.8 + 32 = 73.04)
        val tempF = observations[0].temperatureCelsius * 1.8f + 32f
        assertEquals(73, tempF.roundToInt())
    }

    @Test
    fun `getForecast handles potential float strings for daily high`() = runTest {
        val forecastResponse = """
            {
                "properties": {
                    "periods": [
                        {
                            "name": "Today",
                            "temperature": 72.6,
                            "temperatureUnit": "F",
                            "shortForecast": "Sunny",
                            "isDaytime": true
                        }
                    ]
                }
            }
        """.trimIndent()

        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = forecastResponse,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
        }

        val api = NwsApi(client, json)
        val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/forecast")
        val periods = api.getForecast(gridPoint)

        assertEquals(1, periods.size)
        // Daily forecast is stored as Int, should round correctly (72.6 -> 73)
        assertEquals(73, periods[0].temperature)
    }
}
