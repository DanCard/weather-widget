package com.weatherwidget.data.remote

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.weatherwidget.shared.config.ForecastHorizon
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
class OpenMeteoApiTest {
    private lateinit var json: Json

    @Before
    fun setup() {
        json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
    }

    private fun createMockClient(responseJson: String): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    respond(
                        content = responseJson,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    // Captures the outgoing request so we can assert on the URL parameters Ktor actually sent — the
    // `forecast_days` value is the exact seam a horizon regression slips through (parsing tests can't
    // see it). Returns a minimal valid daily body so getForecast completes.
    private fun createCapturingClient(captured: MutableList<HttpRequestData>): HttpClient {
        val body =
            """
            {"daily":{"time":["2026-06-20"],"temperature_2m_max":[70.0],"temperature_2m_min":[50.0],"weather_code":[0]}}
            """.trimIndent()
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    captured += request
                    respond(
                        content = body,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) { json(json) }
        }
    }

    @Test
    fun `getForecast requests the maximum forecast_days by default`() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api = OpenMeteoApi(createCapturingClient(captured), json)

            api.getForecast(37.42, -122.08)

            val request = captured.single { it.url.host.contains("open-meteo") }
            // Literal "16" (not just the constant) so an accidental MAX_DAYS change trips this too.
            assertEquals("16", request.url.parameters["forecast_days"])
            assertEquals(ForecastHorizon.MAX_DAYS.toString(), request.url.parameters["forecast_days"])
            assertNull("Forecast-only Open-Meteo must not request model-current", request.url.parameters["current"])
            assertNull("Unused 15-minute model rows must not be requested", request.url.parameters["minutely_15"])
        }

    @Test
    fun `getForecast parses daily temperatures correctly`() =
        runTest {
            val responseJson =
                """
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
            assertNull(forecast.providerCurrentTemp)

            assertEquals("2026-01-27", forecast.daily[0].date)
            assertEquals(70f, forecast.daily[0].highTemp, 0.001f)
            assertEquals(45f, forecast.daily[0].lowTemp, 0.001f)

            assertEquals("2026-01-28", forecast.daily[1].date)
            assertEquals(72.5f, forecast.daily[1].highTemp, 0.001f)
            assertEquals(48.5f, forecast.daily[1].lowTemp, 0.001f)
        }

    @Test
    fun `getForecast skips days with null daily temps instead of emitting NaN`() =
        runTest {
            // Open-Meteo returns null at window edges. A null max/min must not become Float.NaN,
            // which previously poisoned roundToInt() in snapshot saving and aborted the fetch.
            val responseJson =
                """
                {
                    "daily": {
                        "time": ["2026-01-27", "2026-01-28", "2026-01-29"],
                        "temperature_2m_max": [70.0, null, 68.0],
                        "temperature_2m_min": [45.0, 48.0, null],
                        "weather_code": [0, 1, 3]
                    }
                }
                """.trimIndent()

            val client = createMockClient(responseJson)
            val api = OpenMeteoApi(client, json)

            val forecast = api.getForecast(37.42, -122.08)

            // Only the fully-populated first day survives; the two partial days are dropped.
            assertEquals(1, forecast.daily.size)
            assertEquals("2026-01-27", forecast.daily[0].date)
            assertTrue(forecast.daily.all { it.highTemp.isFinite() && it.lowTemp.isFinite() })
        }

    @Test
    fun `getForecast handles missing current temperature`() =
        runTest {
            val responseJson =
                """
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

            assertNull(forecast.providerCurrentTemp)
            assertEquals(1, forecast.daily.size)
        }

    @Test
    fun `weatherCodeToCondition returns correct conditions`() {
        val api = OpenMeteoApi(HttpClient(MockEngine) { engine { addHandler { error("unused") } } }, json)

        assertEquals("Clear", api.weatherCodeToCondition(0))
        assertEquals("Mostly Clear", api.weatherCodeToCondition(1))
        assertEquals("Partly Cloudy", api.weatherCodeToCondition(2))
        assertEquals("Overcast", api.weatherCodeToCondition(3))
        assertEquals("Light Fog", api.weatherCodeToCondition(45))
        assertEquals("Dense Fog", api.weatherCodeToCondition(48))
        assertEquals("Rain", api.weatherCodeToCondition(61))
        assertEquals("Snow", api.weatherCodeToCondition(71))
        assertEquals("Thunderstorm", api.weatherCodeToCondition(95))
        assertEquals("Unknown", api.weatherCodeToCondition(999))
    }
}
