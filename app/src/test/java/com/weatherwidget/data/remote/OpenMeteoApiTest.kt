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
            assertNotNull(request.url.parameters["current"])
            assertTrue(request.url.parameters["hourly"]!!.contains("cloud_cover_mid"))
            assertTrue(request.url.parameters["hourly"]!!.contains("cloud_cover_high"))
            assertTrue(request.url.parameters["minutely_15"]!!.contains("cloud_cover_mid"))
            assertTrue(request.url.parameters["minutely_15"]!!.contains("cloud_cover_high"))
        }

    @Test
    fun `getForecast parses and clamps every cloud layer`() =
        runTest {
            val responseJson =
                """
                {
                    "timezone": "America/Los_Angeles",
                    "current": {"time":"2026-08-26T12:00", "temperature_2m":65.0, "weather_code":1},
                    "hourly": {
                        "time":["2026-08-26T12:00"],
                        "temperature_2m":[70.0],
                        "weather_code":[1],
                        "cloud_cover":[-5],
                        "cloud_cover_low":[10],
                        "cloud_cover_mid":[120],
                        "cloud_cover_high":[80]
                    },
                    "minutely_15": {
                        "time":["2026-08-26T11:45", "2026-08-26T12:00"],
                        "temperature_2m":[64.0, 65.0],
                        "weather_code":[1, 1],
                        "precipitation":[0.0, 0.0],
                        "cloud_cover":[101, 90],
                        "cloud_cover_low":[-1, 20],
                        "cloud_cover_mid":[45, 55],
                        "cloud_cover_high":[120, 75]
                    },
                    "daily": {
                        "time":["2026-08-26"],
                        "temperature_2m_max":[75.0],
                        "temperature_2m_min":[55.0],
                        "weather_code":[1]
                    }
                }
                """.trimIndent()

            val forecast = OpenMeteoApi(createMockClient(responseJson), json).getForecast(37.42, -122.08)

            with(forecast.hourly.single()) {
                assertEquals(0, cloudCover)
                assertEquals(10, cloudCoverLow)
                assertEquals(100, cloudCoverMid)
                assertEquals(80, cloudCoverHigh)
            }
            assertEquals(
                listOf(100 to 0, 90 to 20),
                forecast.subHourly.map { it.cloudCover to it.cloudCoverLow },
            )
            assertEquals(listOf(45, 55), forecast.subHourly.map { it.cloudCoverMid })
            assertEquals(listOf(100, 75), forecast.subHourly.map { it.cloudCoverHigh })
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
            assertEquals(65.5f, forecast.providerCurrentTemp!!, 0.001f)

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
        assertEquals("Freezing Drizzle", api.weatherCodeToCondition(56))
        assertEquals("Freezing Rain", api.weatherCodeToCondition(66))
        assertEquals("Snow Grains", api.weatherCodeToCondition(77))
        assertEquals("Rain Showers", api.weatherCodeToCondition(80))
        assertEquals("Snow Showers", api.weatherCodeToCondition(85))
        assertEquals("Rain", api.weatherCodeToCondition(61))
        assertEquals("Snow", api.weatherCodeToCondition(71))
        assertEquals("Thunderstorm", api.weatherCodeToCondition(95))
        assertEquals("Unknown", api.weatherCodeToCondition(999))
    }
}
