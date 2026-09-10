package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TomorrowIoApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createMockClient(hourlyJson: String, dailyJson: String): HttpClient {
        val hourlyTimeline = json.parseToJsonElement(hourlyJson).jsonObject["data"]!!
            .jsonObject["timelines"]!!.jsonArray.single()
        val dailyTimeline = json.parseToJsonElement(dailyJson).jsonObject["data"]!!
            .jsonObject["timelines"]!!.jsonArray.single()
        // Daily first on purpose: production parsing must select by timestep, not array position.
        val combinedJson = buildJsonObject {
            put("data", buildJsonObject {
                put("timelines", JsonArray(listOf(dailyTimeline, hourlyTimeline)))
            })
        }.toString()
        val engine = MockEngine { request ->
            respond(
                content = combinedJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        return HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
    }

    @Test
    fun `getForecast parses hourly and daily data correctly`() = runBlocking {
        val hourlyResponse = """
            {
              "data": {
                "timelines": [
                  {
                    "timestep": "1h",
                    "intervals": [
                      {
                        "startTime": "2026-04-14T18:00:00Z",
                        "values": {
                          "cloudCover": 88.34,
                          "temperature": 65.5,
                          "weatherCode": 1001,
                          "precipitationProbability": 10,
                          "precipitationAccumulation": 0.01
                        }
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val dailyResponse = """
            {
              "data": {
                "timelines": [
                  {
                    "timestep": "1d",
                    "intervals": [
                      {
                        "startTime": "2026-04-14T00:00:00Z",
                        "values": {
                          "temperatureMax": 70.0,
                          "temperatureMin": 55.0,
                          "weatherCode": 1101,
                          "precipitationProbability": 5,
                          "precipitationAccumulation": 0.2
                        }
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val mockClient = createMockClient(hourlyResponse, dailyResponse)
        val api = TomorrowIoApi(mockClient, json) { "test-key" }

        val result = api.getForecast(37.4220, -122.0841)

        assertNotNull(result)
        assertNull(result.providerCurrentTemp)
        assertNull(result.providerCurrentCondition)
        assertNull(result.providerCurrentObservedAt)
        
        assertEquals(1, result.hourly.size)
        assertEquals(65.5f, result.hourly[0].temperature, 0.1f)
        assertEquals(88, result.hourly[0].cloudCover!!) // Int conversion
        assertEquals(10, result.hourly[0].precipProbability)
        // precipAmountMm comes from precipitationAccumulation (inches) × 25.4, NOT intensity.
        assertEquals(0.01f * 25.4f, result.hourly[0].precipAmountMm!!, 0.001f)

        assertEquals(1, result.daily.size)
        assertEquals(70.0f, result.daily[0].highTemp, 0.1f)
        assertEquals(55.0f, result.daily[0].lowTemp, 0.1f)
        assertEquals("2026-04-14", result.daily[0].date)
        assertEquals(5, result.daily[0].precipProbability)
        assertEquals(0.2f * 25.4f, result.daily[0].precipAmountMm!!, 0.001f)
    }

    /**
     * Every `precipitationProbability` Tomorrow.io has ever sent is an integer multiple of 5, so
     * this guards a provider change rather than a live defect. It matters because the old
     * `jsonPrimitive.intOrNull` read returned null for a fractional value, and a null rain chance
     * renders exactly like a confident 0% — the failure would look like a dry forecast, not a bug.
     */
    @Test
    fun `fractional percentages are rounded rather than dropped`() = runBlocking {
        val hourlyResponse = """
            {
              "data": {
                "timelines": [
                  {
                    "timestep": "1h",
                    "intervals": [
                      {
                        "startTime": "2026-04-14T18:00:00Z",
                        "values": {
                          "cloudCover": 88.34,
                          "temperature": 65.5,
                          "weatherCode": 1001,
                          "precipitationProbability": 4.9,
                          "precipitationAccumulation": 0.01
                        }
                      },
                      {
                        "startTime": "2026-04-14T19:00:00Z",
                        "values": {
                          "temperature": 66.0,
                          "weatherCode": 1001,
                          "precipitationProbability": 0.4,
                          "precipitationAccumulation": 0.0
                        }
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val dailyResponse = """
            {
              "data": {
                "timelines": [
                  {
                    "timestep": "1d",
                    "intervals": [
                      {
                        "startTime": "2026-04-14T00:00:00Z",
                        "values": {
                          "temperatureMax": 70.0,
                          "temperatureMin": 55.0,
                          "weatherCode": 1101,
                          "precipitationProbability": 32.5,
                          "precipitationAccumulation": 0.2
                        }
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val api = TomorrowIoApi(createMockClient(hourlyResponse, dailyResponse), json) { "test-key" }

        val result = api.getForecast(37.4220, -122.0841)

        // Rounded, not null. Under the old parse all three of these were null.
        assertEquals(5, result.hourly[0].precipProbability)
        assertEquals(0, result.hourly[1].precipProbability)
        assertEquals(33, result.daily[0].precipProbability)
    }

    @Test
    fun `getForecast throws ApiAccessException when remote returns 429`() = runBlocking {
        val engine = MockEngine { request ->
            respond(
                content = """{"code":429001,"type":"Too Many Calls","message":"The request limit... has been reached..."}""",
                status = HttpStatusCode.TooManyRequests,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val mockClient = HttpClient(engine) {
            install(ContentNegotiation) {
                json(json)
            }
        }
        val api = TomorrowIoApi(mockClient, json) { "test-key" }

        try {
            api.getForecast(37.4220, -122.0841)
            org.junit.Assert.fail("Expected ApiAccessException to be thrown")
        } catch (e: ApiAccessException) {
            assertEquals(HttpStatusCode.TooManyRequests.value, e.statusCode)
            assertEquals(com.weatherwidget.data.model.WeatherSource.TOMORROW_IO, e.source)
        }
    }

    @Test
    fun `hourly request asks for a lookback covering the whole elapsed local day`() = runBlocking {
        var capturedStartTime: String? = null
        var requestCount = 0
        var capturedTimesteps = emptyList<String>()
        val emptyTimeline = """{"data":{"timelines":[{"timestep":"1d","intervals":[]},{"timestep":"1h","intervals":[]}]}}"""
        val engine = MockEngine { request ->
            requestCount++
            capturedStartTime = request.url.parameters["startTime"]
            capturedTimesteps = request.url.parameters.getAll("timesteps").orEmpty()
            respond(
                content = emptyTimeline,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val mockClient = HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
        }
        val api = TomorrowIoApi(mockClient, json) { "test-key" }

        api.getForecast(37.4220, -122.0841)

        // 23 h, not 6: a site fetched for the FIRST time must still receive today's overnight
        // minimum, otherwise its "daily low" is just the earliest hour since it was promoted
        // (Samsung 2026-08-22 — a noon-onward window reported the noon reading as the day's low).
        // 23 rather than 24 because the plan rejects startTime more than 24 h back (403/403003).
        assertEquals(1, requestCount)
        assertEquals(listOf("1h", "1d"), capturedTimesteps)
        assertEquals("nowMinus23h", capturedStartTime)
    }

    @Test
    fun `getFiveMinuteHistory requests elapsed window and preserves native timestamps`() = runBlocking {
        var capturedPath: String? = null
        var capturedStart: String? = null
        var capturedEnd: String? = null
        var capturedUnits: String? = null
        var capturedFields: String? = null
        var capturedTimesteps = emptyList<String>()
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedStart = request.url.parameters["startTime"]
            capturedEnd = request.url.parameters["endTime"]
            capturedUnits = request.url.parameters["units"]
            capturedFields = request.url.parameters["fields"]
            capturedTimesteps = request.url.parameters.getAll("timesteps").orEmpty()
            respond(
                content = """
                    {
                      "data": {
                        "timelines": [{
                          "timestep": "5m",
                          "intervals": [
                            {"startTime":"2026-08-21T21:10:00Z","values":{"temperature":68.8,"weatherCode":1101,"cloudCover":55.1}},
                            {"startTime":"not-a-time","values":{"temperature":99.0}},
                            {"startTime":"2026-08-21T21:12:00Z","values":{}},
                            {"startTime":"2026-08-21T21:15:00Z","values":{"temperature":69.1,"weatherCode":1101,"cloudCover":56.4,"cloudBase":1.0,"cloudCeiling":3.0}}
                          ]
                        }]
                      }
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val api = TomorrowIoApi(
            HttpClient(engine) { install(ContentNegotiation) { json(json) } },
            json,
            Clock.fixed(Instant.parse("2026-08-21T21:17:42Z"), ZoneOffset.UTC),
        ) { "test-key" }

        val result = api.getFiveMinuteHistory(37.4220, -122.0841, lookbackHours = 1)

        assertEquals("/v4/timelines", capturedPath)
        assertEquals("2026-08-21T20:15:00Z", capturedStart)
        assertEquals("2026-08-21T21:15:00Z", capturedEnd)
        assertEquals("imperial", capturedUnits)
        assertEquals(listOf("5m"), capturedTimesteps)
        assertEquals("temperature,weatherCode,cloudCover,cloudBase,cloudCeiling", capturedFields)
        assertEquals(2, result.subHourly.size)
        assertEquals(1787346600000L, result.subHourly[0].dateTime)
        assertEquals(1787346900000L, result.subHourly[1].dateTime)
        assertEquals(69.1f, result.providerCurrentTemp!!, 0.01f)
        assertEquals("Partly Cloudy", result.providerCurrentCondition)
        assertEquals(56, result.providerCurrentCloudCover)
        assertEquals(1787346900000L, result.providerCurrentObservedAt)
        assertEquals(1_609, result.subHourly[1].cloudEnvelopeBaseMeters)
        assertEquals(4_828, result.subHourly[1].cloudEnvelopeTopMeters)
        assertEquals(null, result.subHourly[1].precipAmountMm)
    }

    @Test
    fun `weatherCodeToCondition maps codes correctly`() {
        val api = TomorrowIoApi(HttpClient(MockEngine { respond("") }), json) { "test-key" }
        
        assertEquals("Clear", api.weatherCodeToCondition(1000))
        assertEquals("Mostly Clear", api.weatherCodeToCondition(1100))
        assertEquals("Cloudy", api.weatherCodeToCondition(1001))
        assertEquals("Fog", api.weatherCodeToCondition(2000))
        assertEquals("Thunderstorm", api.weatherCodeToCondition(8000))
        assertEquals("Unknown", api.weatherCodeToCondition(9999))
    }
}
