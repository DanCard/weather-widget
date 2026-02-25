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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class WeatherApiTest {
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
                addHandler {
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

    @Test
    fun `getForecast parses daily and hourly forecasts`() =
        runTest {
            val responseJson =
                """
                {
                  "current": {
                    "temp_f": 67.6
                  },
                  "forecast": {
                    "forecastday": [
                      {
                        "date": "2026-02-24",
                        "day": {
                          "maxtemp_f": 71.4,
                          "mintemp_f": 50.1,
                          "daily_chance_of_rain": 35,
                          "condition": {
                            "text": "Partly cloudy"
                          }
                        },
                        "hour": [
                          {
                            "time": "2026-02-24 00:00",
                            "temp_f": 52.2,
                            "chance_of_rain": 10,
                            "condition": {
                              "text": "Clear"
                            }
                          },
                          {
                            "time": "2026-02-24 13:00",
                            "temp_f": 66.8,
                            "chance_of_rain": 45,
                            "condition": {
                              "text": "Light rain"
                            }
                          }
                        ]
                      },
                      {
                        "date": "2026-02-25",
                        "day": {
                          "maxtemp_f": 73.5,
                          "mintemp_f": 48.5,
                          "daily_chance_of_rain": 5,
                          "condition": {
                            "text": "Sunny"
                          }
                        },
                        "hour": []
                      }
                    ]
                  }
                }
                """.trimIndent()

            val api = WeatherApi(createMockClient(responseJson), json)
            val forecast = api.getForecast(37.42, -122.08, days = 2)

            assertEquals(67.6f, forecast.currentTemp!!, 0.001f)
            assertEquals(2, forecast.daily.size)
            assertEquals(2, forecast.hourly.size)

            assertEquals("2026-02-24", forecast.daily[0].date)
            assertEquals(71.4f, forecast.daily[0].highTemp, 0.001f)
            assertEquals(50.1f, forecast.daily[0].lowTemp, 0.001f)
            assertEquals("Partly cloudy", forecast.daily[0].condition)
            assertEquals(35, forecast.daily[0].precipProbability)

            assertEquals("2026-02-25", forecast.daily[1].date)
            assertEquals(73.5f, forecast.daily[1].highTemp, 0.001f)
            assertEquals(48.5f, forecast.daily[1].lowTemp, 0.001f)
            assertEquals("Sunny", forecast.daily[1].condition)
            assertEquals(5, forecast.daily[1].precipProbability)

            assertEquals("2026-02-24T00:00", forecast.hourly[0].dateTime)
            assertEquals(52.2f, forecast.hourly[0].temperature)
            assertEquals("Clear", forecast.hourly[0].condition)
            assertEquals(10, forecast.hourly[0].precipProbability)

            assertEquals("2026-02-24T13:00", forecast.hourly[1].dateTime)
            assertEquals(66.8f, forecast.hourly[1].temperature)
            assertEquals("Light rain", forecast.hourly[1].condition)
            assertEquals(45, forecast.hourly[1].precipProbability)
        }

    @Test
    fun `getForecast handles missing optional fields`() =
        runTest {
            val responseJson =
                """
                {
                  "forecast": {
                    "forecastday": [
                      {
                        "date": "2026-02-24",
                        "day": {
                          "maxtemp_f": 70.0,
                          "mintemp_f": 49.0,
                          "condition": {
                            "text": "Cloudy"
                          }
                        },
                        "hour": [
                          {
                            "time": "2026-02-24 10:00",
                            "temp_f": 60.0,
                            "condition": {
                              "text": "Cloudy"
                            }
                          }
                        ]
                      }
                    ]
                  }
                }
                """.trimIndent()

            val api = WeatherApi(createMockClient(responseJson), json)
            val forecast = api.getForecast(37.42, -122.08, days = 1)

            assertNull(forecast.currentTemp)
            assertEquals(1, forecast.daily.size)
            assertEquals(1, forecast.hourly.size)
            assertNull(forecast.daily[0].precipProbability)
            assertNull(forecast.hourly[0].precipProbability)
        }
}
