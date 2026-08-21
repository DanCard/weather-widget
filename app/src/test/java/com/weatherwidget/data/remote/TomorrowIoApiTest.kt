package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class TomorrowIoApiTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private fun createMockClient(hourlyJson: String, dailyJson: String): HttpClient {
        val engine = MockEngine { request ->
            val responseJson = if (request.url.parameters["timesteps"] == "1h") hourlyJson else dailyJson
            respond(
                content = responseJson,
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
        // precipAmountMm comes from precipitationAccumulation (inches) × 25.4, NOT intensity.
        assertEquals(0.01f * 25.4f, result.hourly[0].precipAmountMm!!, 0.001f)

        assertEquals(1, result.daily.size)
        assertEquals(70.0f, result.daily[0].highTemp, 0.1f)
        assertEquals(55.0f, result.daily[0].lowTemp, 0.1f)
        assertEquals("2026-04-14", result.daily[0].date)
        assertEquals(0.2f * 25.4f, result.daily[0].precipAmountMm!!, 0.001f)
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
    fun `hourly request asks for the bounded six-hour lookback`() = runBlocking {
        var capturedStartTime: String? = null
        val emptyTimeline = """{"data":{"timelines":[{"intervals":[]}]}}"""
        val engine = MockEngine { request ->
            if (request.url.parameters["timesteps"] == "1h") {
                capturedStartTime = request.url.parameters["startTime"]
            }
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

        assertEquals("nowMinus6h", capturedStartTime)
    }

    @Test
    fun `getRealtime parses source-native current temperature cloud and timestamp`() = runBlocking {
        var capturedPath: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            respond(
                content = """
                    {
                      "data": {
                        "time": "2026-08-21T21:15:00Z",
                        "values": {
                          "temperature": 69.1,
                          "weatherCode": 1101,
                          "cloudCover": 56.4
                        }
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
        ) { "test-key" }

        val reading = api.getRealtime(37.4220, -122.0841)

        assertNotNull(reading)
        assertEquals("/v4/weather/realtime", capturedPath)
        assertEquals(69.1f, reading!!.temperature, 0.01f)
        assertEquals("Partly Cloudy", reading.condition)
        assertEquals(56, reading.cloudCover)
        assertEquals(1787346900000L, reading.observedAt)
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
