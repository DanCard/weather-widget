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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class OpenWeatherMapApiTest {
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
    fun `getForecast parses current daily and hourly forecasts`() =
        runTest {
            val responseJson =
                """
                {
                  "timezone_offset": -25200,
                  "current": {
                    "dt": 1771894800,
                    "temp": 67.4,
                    "weather": [
                      { "description": "broken clouds" }
                    ]
                  },
                  "hourly": [
                    {
                      "dt": 1771894800,
                      "temp": 67.4,
                      "pop": 0.25,
                      "clouds": 64,
                      "rain": 0.38,
                      "weather": [
                        { "description": "light rain" }
                      ]
                    },
                    {
                      "dt": 1771898400,
                      "temp": 66.0,
                      "pop": 0.0,
                      "clouds": 20,
                      "weather": [
                        { "description": "clear sky" }
                      ]
                    }
                  ],
                  "daily": [
                    {
                      "dt": 1771902000,
                      "temp": { "min": 51.2, "max": 70.8 },
                      "pop": 0.4,
                      "rain": 1.5,
                      "weather": [
                        { "description": "moderate rain" }
                      ]
                    },
                    {
                      "dt": 1771988400,
                      "temp": { "min": 49.0, "max": 72.1 },
                      "pop": 0.1,
                      "snow": 0.7,
                      "clouds": 78,
                      "weather": [
                        { "description": "overcast clouds" }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(responseJson), json, apiKey = "test-key")
            val forecast = api.getForecast(37.42, -122.08)

            assertEquals(67.4f, forecast.currentTemp!!, 0.001f)
            assertEquals("Broken Clouds", forecast.currentCondition)
            assertEquals(2, forecast.daily.size)
            assertEquals(2, forecast.hourly.size)

            assertEquals("2026-02-23", forecast.daily[0].date)
            assertEquals(70.8f, forecast.daily[0].highTemp, 0.001f)
            assertEquals(51.2f, forecast.daily[0].lowTemp, 0.001f)
            assertEquals("Moderate Rain", forecast.daily[0].condition)
            assertEquals(40, forecast.daily[0].precipProbability)
            assertEquals(1.5f, forecast.daily[0].precipAmountMm!!, 0.001f)

            assertEquals(1771894800000L, forecast.hourly[0].dateTime)
            assertEquals(67.4f, forecast.hourly[0].temperature, 0.001f)
            assertEquals("Light Rain", forecast.hourly[0].condition)
            assertEquals(25, forecast.hourly[0].precipProbability)
            assertEquals(64, forecast.hourly[0].cloudCover)
            assertEquals(0.38f, forecast.hourly[0].precipAmountMm!!, 0.001f)
        }

    @Test
    fun `getForecast handles missing optional fields`() =
        runTest {
            val responseJson =
                """
                {
                  "timezone_offset": 0,
                  "daily": [
                    {
                      "dt": 1771902000,
                      "temp": { "min": 45.0, "max": 68.0 },
                      "weather": [
                        { "description": "clear sky" }
                      ]
                    }
                  ],
                  "hourly": [
                    {
                      "dt": 1771894800,
                      "temp": 60.0,
                      "weather": [
                        { "description": "few clouds" }
                      ]
                    }
                  ]
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(responseJson), json, apiKey = "test-key")
            val forecast = api.getForecast(37.42, -122.08)

            assertNull(forecast.currentTemp)
            assertEquals(1, forecast.daily.size)
            assertEquals(1, forecast.hourly.size)
            assertNull(forecast.daily[0].precipProbability)
            assertNull(forecast.daily[0].precipAmountMm)
            assertNull(forecast.hourly[0].precipProbability)
            assertNull(forecast.hourly[0].precipAmountMm)
            assertNull(forecast.hourly[0].cloudCover)
        }

    @Test
    fun `getCurrent parses current response`() =
        runTest {
            val responseJson =
                """
                {
                  "current": {
                    "dt": 1771894800,
                    "temp": 58.2,
                    "weather": [
                      { "description": "scattered clouds" }
                    ]
                  }
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(responseJson), json, apiKey = "test-key")
            val current = api.getCurrent(37.42, -122.08)

            assertNotNull(current)
            assertEquals(58.2f, current!!.temperature, 0.001f)
            assertEquals("Scattered Clouds", current.condition)
            assertEquals(1771894800000L, current.observedAt)
        }

    @Test
    fun `getForecast throws subscription required for OWM error payload`() =
        runTest {
            val responseJson =
                """
                {
                  "cod": 401,
                  "message": "Please note that using One Call 3.0 requires a separate subscription to the One Call by Call plan."
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(responseJson), json, apiKey = "test-key")

            val exception =
                try {
                    api.getForecast(37.42, -122.08)
                    throw AssertionError("Expected OpenWeatherMapAccessException")
                } catch (e: OpenWeatherMapApi.OpenWeatherMapAccessException) {
                    e
                }

            assertEquals(OpenWeatherMapApi.FailureReason.SUBSCRIPTION_REQUIRED, exception.reason)
            assertEquals(401, exception.statusCode)
            assertEquals(
                "Please note that using One Call 3.0 requires a separate subscription to the One Call by Call plan.",
                exception.detail,
            )
            assertEquals("OpenWeatherMap 401 error. One Call 3.0 subscription required.", exception.message)
        }
}
