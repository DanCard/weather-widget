package com.weatherwidget.data.remote

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
                          "precipitationIntensity": 0.01
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
                          "precipitationIntensity": 0.0
                        }
                      }
                    ]
                  }
                ]
              }
            }
        """.trimIndent()

        val mockClient = createMockClient(hourlyResponse, dailyResponse)
        val api = TomorrowIoApi(mockClient, json)

        val result = api.getForecast(37.4220, -122.0841)

        assertNotNull(result)
        assertEquals(65.5f, result.currentTemp!!, 0.1f)
        assertEquals(1001, result.currentWeatherCode)
        
        assertEquals(1, result.hourly.size)
        assertEquals(65.5f, result.hourly[0].temperature, 0.1f)
        assertEquals(88, result.hourly[0].cloudCover!!) // Int conversion
        
        assertEquals(1, result.daily.size)
        assertEquals(70.0f, result.daily[0].highTemp, 0.1f)
        assertEquals(55.0f, result.daily[0].lowTemp, 0.1f)
        assertEquals("2026-04-14", result.daily[0].date)
    }

    @Test
    fun `weatherCodeToCondition maps codes correctly`() {
        val api = TomorrowIoApi(HttpClient(MockEngine { respond("") }), json)
        
        assertEquals("Clear", api.weatherCodeToCondition(1000))
        assertEquals("Mostly Clear", api.weatherCodeToCondition(1100))
        assertEquals("Cloudy", api.weatherCodeToCondition(1001))
        assertEquals("Fog", api.weatherCodeToCondition(2000))
        assertEquals("Thunderstorm", api.weatherCodeToCondition(8000))
        assertEquals("Unknown", api.weatherCodeToCondition(9999))
    }
}
