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
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class NwsApiTest {
    private lateinit var json: Json

    @Before
    fun setup() {
        json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
    }

    @Test
    fun `getGridPoint parses response correctly`() =
        runTest {
            val pointsResponse =
                """
                {
                    "properties": {
                        "gridId": "MTR",
                        "gridX": 85,
                        "gridY": 105,
                        "forecast": "https://api.weather.gov/gridpoints/MTR/85,105/forecast"
                    }
                }
                """.trimIndent()

            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            respond(
                                content = pointsResponse,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                    install(ContentNegotiation) {
                        json(json)
                    }
                }

            val api = NwsApi(client, json)
            val gridPoint = api.getGridPoint(37.42, -122.08)

            assertEquals("MTR", gridPoint.gridId)
            assertEquals(85, gridPoint.gridX)
            assertEquals(105, gridPoint.gridY)
            assertEquals("https://api.weather.gov/gridpoints/MTR/85,105/forecast", gridPoint.forecastUrl)
        }

    @Test
    fun `getForecast parses periods correctly`() =
        runTest {
            val forecastResponse =
                """
                {
                    "properties": {
                        "periods": [
                            {
                                "name": "Today",
                                "temperature": 65,
                                "temperatureUnit": "F",
                                "shortForecast": "Sunny",
                                "isDaytime": true
                            },
                            {
                                "name": "Tonight",
                                "temperature": 45,
                                "temperatureUnit": "F",
                                "shortForecast": "Clear",
                                "isDaytime": false
                            },
                            {
                                "name": "Tomorrow",
                                "temperature": 68,
                                "temperatureUnit": "F",
                                "shortForecast": "Partly Cloudy",
                                "isDaytime": true
                            }
                        ]
                    }
                }
                """.trimIndent()

            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            respond(
                                content = forecastResponse,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
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

            assertEquals(3, periods.size)

            assertEquals("Today", periods[0].name)
            assertEquals(65, periods[0].temperature)
            assertTrue(periods[0].isDaytime)

            assertEquals("Tonight", periods[1].name)
            assertEquals(45, periods[1].temperature)
            assertFalse(periods[1].isDaytime)

            assertEquals("Tomorrow", periods[2].name)
            assertEquals(68, periods[2].temperature)
        }

    @Test
    fun `getForecast handles empty periods`() =
        runTest {
            val forecastResponse =
                """
                {
                    "properties": {
                        "periods": []
                    }
                }
                """.trimIndent()

            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            respond(
                                content = forecastResponse,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
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

            assertTrue(periods.isEmpty())
        }

    @Test
    fun `getSkyCover converts UTC valid times into local hourly keys`() =
        runTest {
            val gridResponse =
                """
                {
                    "properties": {
                        "skyCover": {
                            "values": [
                                {
                                    "validTime": "2026-03-14T14:00:00+00:00/PT2H",
                                    "value": 22
                                }
                            ]
                        }
                    }
                }
                """.trimIndent()

            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            respond(
                                content = gridResponse,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        }
                    }
                    install(ContentNegotiation) {
                        json(json)
                    }
                }

            val api = NwsApi(client, json)
            val gridPoint = NwsApi.GridPointInfo("MTR", 85, 105, "https://example.com/forecast")
            val result = api.getSkyCover(gridPoint)

            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")
            val firstLocalHour = ZonedDateTime.parse("2026-03-14T14:00:00+00:00")
                .withZoneSameInstant(ZoneId.systemDefault())
                .format(formatter)
            val secondLocalHour = ZonedDateTime.parse("2026-03-14T15:00:00+00:00")
                .withZoneSameInstant(ZoneId.systemDefault())
                .format(formatter)

            assertEquals(22, result[firstLocalHour])
            assertEquals(22, result[secondLocalHour])
        }

    @Test
    fun `getLatestObservationDetailed falls back when latest temp is null`() =
        runTest {
            val latestResponse =
                """
                {
                    "properties": {
                        "stationName": "Moffett Field",
                        "timestamp": "2026-03-19T03:15:00+00:00",
                        "textDescription": "Unknown",
                        "temperature": {
                            "unitCode": "wmoUnit:degC",
                            "value": null
                        }
                    }
                }
                """.trimIndent()

            val fallbackResponse =
                """
                {
                    "features": [
                        {
                            "properties": {
                                "stationName": "Moffett Field",
                                "timestamp": "2026-03-19T02:35:00+00:00",
                                "textDescription": "Unknown",
                                "temperature": {
                                    "value": null
                                }
                            }
                        },
                        {
                            "properties": {
                                "stationName": "Moffett Field",
                                "timestamp": "2026-03-19T02:55:00+00:00",
                                "textDescription": "Clear",
                                "temperature": {
                                    "value": 22.0
                                }
                            }
                        }
                    ]
                }
                """.trimIndent()

            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            if (request.url.encodedPath.endsWith("/latest")) {
                                respond(
                                    content = latestResponse,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            } else if (request.url.encodedPath.contains("/observations") && request.url.parameters["limit"] == "10") {
                                respond(
                                    content = fallbackResponse,
                                    status = HttpStatusCode.OK,
                                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                                )
                            } else {
                                respond("Not Found", HttpStatusCode.NotFound)
                            }
                        }
                    }
                    install(ContentNegotiation) {
                        json(json)
                    }
                }

            val api = NwsApi(client, json)
            val obs = api.getLatestObservationDetailed("KNUQ")

            assertNotNull(obs)
            assertEquals(22.0f, obs!!.temperatureCelsius)
            assertEquals("2026-03-19T02:55:00+00:00", obs.timestamp)
        }
}
