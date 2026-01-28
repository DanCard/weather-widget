package com.weatherwidget.data.remote

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class OpenMeteoApiTest {

    private lateinit var json: Json

    @Before
    fun setup() {
        json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    private fun createMockClient(responseJson: String): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    @Test
    fun `getForecast parses daily temperatures correctly`() = runTest {
        val responseJson = """
            {
                "current": {
                    "temperature_2m": 65.5,
                    "weather_code": 0
                },
                "daily": {
                    "time": ["2026-01-27", "2026-01-28", "2026-01-29"],
                    "temperature_2m_max": [70.0, 72.5, 68.0],
                    "temperature_2m_min": [45.0, 48.5, 42.0],
                    "weather_code": [0, 1, 3]
                }
            }
        """.trimIndent()

        val client = createMockClient(responseJson)
        val api = OpenMeteoApi(client, json)

        val forecast = api.getForecast(37.42, -122.08)

        assertEquals(3, forecast.daily.size)
        assertEquals(65, forecast.currentTemp)

        assertEquals("2026-01-27", forecast.daily[0].date)
        assertEquals(70, forecast.daily[0].highTemp)
        assertEquals(45, forecast.daily[0].lowTemp)

        assertEquals("2026-01-28", forecast.daily[1].date)
        assertEquals(72, forecast.daily[1].highTemp)
        assertEquals(48, forecast.daily[1].lowTemp)
    }

    @Test
    fun `getForecast handles missing current temperature`() = runTest {
        val responseJson = """
            {
                "daily": {
                    "time": ["2026-01-28"],
                    "temperature_2m_max": [70.0],
                    "temperature_2m_min": [45.0],
                    "weather_code": [0]
                }
            }
        """.trimIndent()

        val client = createMockClient(responseJson)
        val api = OpenMeteoApi(client, json)

        val forecast = api.getForecast(37.42, -122.08)

        assertNull(forecast.currentTemp)
        assertEquals(1, forecast.daily.size)
    }

    @Test
    fun `weatherCodeToCondition returns correct conditions`() {
        val api = OpenMeteoApi(HttpClient(MockEngine) { engine { addHandler { error("unused") } } }, json)

        assertEquals("Clear", api.weatherCodeToCondition(0))
        assertEquals("Partly Cloudy", api.weatherCodeToCondition(1))
        assertEquals("Partly Cloudy", api.weatherCodeToCondition(2))
        assertEquals("Rain", api.weatherCodeToCondition(61))
        assertEquals("Snow", api.weatherCodeToCondition(71))
        assertEquals("Thunderstorm", api.weatherCodeToCondition(95))
        assertEquals("Unknown", api.weatherCodeToCondition(999))
    }
}
