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
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category



@Category(ShortDuration::class)
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
    fun `getGridpointsBundle skyCover converts UTC valid times into local hourly keys`() =
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
            val result = api.getGridpointsBundle(gridPoint).skyCoverByHour

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
    fun `getGridpointsBundle qpf parses grid intervals and preserves zeroes`() =
        runTest {
            val gridResponse =
                """
                {
                    "properties": {
                        "quantitativePrecipitation": {
                            "values": [
                                {
                                    "validTime": "2026-03-31T18:00:00+00:00/PT6H",
                                    "unitCode": "wmoUnit:mm",
                                    "value": 0
                                },
                                {
                                    "validTime": "2026-04-01T00:00:00+00:00/PT6H",
                                    "unitCode": "wmoUnit:in",
                                    "value": 0.5
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
            val result = api.getGridpointsBundle(gridPoint).qpfIntervals

            assertEquals(2, result.size)
            assertEquals(0f, result[0].amountMm)
            assertEquals(12.7f, result[1].amountMm, 0.001f)
        }

    @Test
    fun `getDailyTemperaturesFromGridpoints converts Celsius to Fahrenheit`() =
        runTest {
            val gridResponse =
                """
                {
                    "properties": {
                        "maxTemperature": {
                            "uom": "wmoUnit:degC",
                            "values": [
                                {
                                    "validTime": "2026-05-07T15:00:00+00:00/PT13H",
                                    "value": 24.44
                                }
                            ]
                        },
                        "minTemperature": {
                            "uom": "wmoUnit:degC",
                            "values": []
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
            val gridPoint = NwsApi.GridPointInfo("MTR", 93, 87, "https://example.com/forecast")
            val result = api.getGridpointsBundle(gridPoint).dailyTemperatures

            // Date attribution for daytime windows: validTime "2026-05-07T15:00:00Z/PT13H" =
            // 8am-9pm PDT on May 7 (or 7am-8pm PST on May 7 depending on DST). The local date
            // is 2026-05-07 in any US timezone — use that to assert.
            val expectedDate = ZonedDateTime.parse("2026-05-07T15:00:00+00:00")
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
            val temp = result.maxByDate[expectedDate]
            assertNotNull(temp)
            assertEquals(75.992f, temp!!, 0.01f) // (24.44 * 1.8) + 32 = 75.992
        }

    @Test
    fun `getDailyTemperaturesFromGridpoints attributes overnight low to morning date`() =
        runTest {
            // validTime 2026-05-07T03:00:00Z/PT11H = 8pm PDT May 6 → 7am PDT May 7.
            // Per /forecast convention, this low belongs to May 7 (the morning the night ends).
            val gridResponse =
                """
                {
                    "properties": {
                        "maxTemperature": {
                            "uom": "wmoUnit:degC",
                            "values": []
                        },
                        "minTemperature": {
                            "uom": "wmoUnit:degC",
                            "values": [
                                {
                                    "validTime": "2026-05-07T03:00:00+00:00/PT11H",
                                    "value": 11.11
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
            val gridPoint = NwsApi.GridPointInfo("MTR", 93, 87, "https://example.com/forecast")
            val result = api.getGridpointsBundle(gridPoint).dailyTemperatures

            // Compute expected date in local zone — end - 1 minute, then toLocalDate.
            val end = ZonedDateTime.parse("2026-05-07T03:00:00+00:00").plusHours(11)
            val expectedDate = end.minusMinutes(1)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
            val temp = result.minByDate[expectedDate]
            assertNotNull(temp)
            assertEquals(51.998f, temp!!, 0.01f) // (11.11 * 1.8) + 32 = 51.998
        }

    @Test
    fun `getDailyTemperaturesFromGridpoints picks max when multiple intervals share a date`() =
        runTest {
            val gridResponse =
                """
                {
                    "properties": {
                        "maxTemperature": {
                            "uom": "wmoUnit:degC",
                            "values": [
                                { "validTime": "2026-05-07T15:00:00+00:00/PT4H", "value": 20.0 },
                                { "validTime": "2026-05-07T19:00:00+00:00/PT4H", "value": 25.0 }
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
            val gridPoint = NwsApi.GridPointInfo("MTR", 93, 87, "https://example.com/forecast")
            val result = api.getGridpointsBundle(gridPoint).dailyTemperatures

            val expectedDate = ZonedDateTime.parse("2026-05-07T15:00:00+00:00")
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDate()
                .toString()
            // Larger of the two: 25.0°C → 77.0°F
            assertEquals(77.0f, result.maxByDate[expectedDate]!!, 0.01f)
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

    @Test
    fun `getLatestObservationDetailed parses precipitation fields`() =
        runTest {
            val response =
                """
                {
                    "properties": {
                        "stationName": "Moffett Field",
                        "timestamp": "2026-03-19T03:15:00+00:00",
                        "textDescription": "Rain",
                        "temperature": {
                            "unitCode": "wmoUnit:degC",
                            "value": 15.0
                        },
                        "precipitationLastHour": {
                            "unitCode": "wmoUnit:mm",
                            "value": 2.5
                        },
                        "precipitationLast24Hours": {
                            "unitCode": "wmoUnit:mm",
                            "value": 15.3
                        }
                    }
                }
                """.trimIndent()

            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            respond(
                                content = response,
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
            val obs = api.getLatestObservationDetailed("KNUQ")

            assertNotNull(obs)
            assertEquals(15.0f, obs!!.temperatureCelsius)
            assertEquals(2.5f, obs.precipLastHourMm)
            assertEquals(15.3f, obs.precipLast24hMm)
        }

    @Test
    fun `getLatestObservationDetailed handles null precipitation fields`() =
        runTest {
            val response =
                """
                {
                    "properties": {
                        "stationName": "Moffett Field",
                        "timestamp": "2026-03-19T03:15:00+00:00",
                        "textDescription": "Clear",
                        "temperature": {
                            "unitCode": "wmoUnit:degC",
                            "value": 20.0
                        },
                        "precipitationLastHour": {
                            "unitCode": "wmoUnit:mm",
                            "value": null
                        }
                    }
                }
                """.trimIndent()

            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler { request ->
                            respond(
                                content = response,
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
            val obs = api.getLatestObservationDetailed("KNUQ")

            assertNotNull(obs)
            assertEquals(20.0f, obs!!.temperatureCelsius)
            assertNull(obs.precipLastHourMm)
            assertNull(obs.precipLast24hMm)
        }

    /**
     * Regression for the historical-observations precip-drop: getObservations used to use a
     * hand-rolled inline parser that skipped precipitationLastHour / precipitationLast24Hours
     * entirely. After delegating to parseObservationProperties, those fields flow through to
     * ObservationEntity, enabling the NWS measured-precip branch in resolveDailyPrecip during
     * 7-day and hourly backfill.
     */
    @Test
    fun `getObservations parses precipitation fields from historical features`() =
        runTest {
            val response =
                """
                {
                    "features": [
                        {
                            "properties": {
                                "stationName": "Moffett Field",
                                "timestamp": "2026-05-27T22:55:00+00:00",
                                "textDescription": "Rain",
                                "temperature": { "unitCode": "wmoUnit:degC", "value": 14.5 },
                                "precipitationLastHour":  { "unitCode": "wmoUnit:mm", "value": 0.8 },
                                "precipitationLast24Hours": { "unitCode": "wmoUnit:mm", "value": 3.2 }
                            }
                        },
                        {
                            "properties": {
                                "stationName": "Moffett Field",
                                "timestamp": "2026-05-27T23:55:00+00:00",
                                "textDescription": "Rain",
                                "temperature": { "unitCode": "wmoUnit:degC", "value": 14.0 },
                                "precipitationLastHour":  { "unitCode": "wmoUnit:mm", "value": 1.5 },
                                "precipitationLast24Hours": { "unitCode": "wmoUnit:mm", "value": 4.7 }
                            }
                        },
                        {
                            "properties": {
                                "stationName": "Moffett Field",
                                "timestamp": "2026-05-28T00:55:00+00:00",
                                "textDescription": "Cloudy",
                                "temperature": { "unitCode": "wmoUnit:degC", "value": 13.0 },
                                "precipitationLastHour":  { "unitCode": "wmoUnit:mm", "value": null },
                                "precipitationLast24Hours": { "unitCode": "wmoUnit:mm", "value": null }
                            }
                        }
                    ]
                }
                """.trimIndent()

            val client =
                HttpClient(MockEngine) {
                    engine {
                        addHandler {
                            respond(
                                content = response,
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
            val obs = api.getObservations("KNUQ", "2026-05-27T22:00:00Z", "2026-05-28T01:00:00Z")

            assertEquals(3, obs.size)
            assertEquals(0.8f, obs[0].precipLastHourMm)
            assertEquals(3.2f, obs[0].precipLast24hMm)
            assertEquals(1.5f, obs[1].precipLastHourMm)
            assertEquals(4.7f, obs[1].precipLast24hMm)
            assertNull(obs[2].precipLastHourMm)
            assertNull(obs[2].precipLast24hMm)
            // Sanity: pre-existing temp parsing still works.
            assertEquals(14.5f, obs[0].temperatureCelsius)
            assertEquals(14.0f, obs[1].temperatureCelsius)
        }
}
