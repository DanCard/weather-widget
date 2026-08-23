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

    private fun createMockClient(
        currentJson: String,
        forecastJson: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val path = request.url.encodedPath
                    val content = if (path.contains("/weather")) currentJson else forecastJson
                    respond(
                        content = content,
                        status = status,
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
    fun `getForecast parses 2_5 weather and forecast responses`() =
        runTest {
            val currentWeatherJson =
                """
                {
                  "coord": { "lon": -122.08, "lat": 37.42 },
                  "weather": [
                    { "id": 804, "main": "Clouds", "description": "overcast clouds", "icon": "04d" }
                  ],
                  "main": {
                    "temp": 67.4,
                    "feels_like": 67.0,
                    "temp_min": 65.0,
                    "temp_max": 70.0,
                    "pressure": 1014,
                    "humidity": 60
                  },
                  "dt": 1771894800,
                  "timezone": -25200,
                  "id": 5375480,
                  "name": "Mountain View",
                  "cod": 200
                }
                """.trimIndent()

            val forecastJson =
                """
                {
                  "cod": "200",
                  "message": 0,
                  "cnt": 2,
                  "city": {
                    "id": 5375480,
                    "name": "Mountain View",
                    "coord": { "lat": 37.42, "lon": -122.08 },
                    "country": "US",
                    "timezone": -25200
                  },
                  "list": [
                    {
                      "dt": 1771894800,
                      "main": { "temp": 67.4, "temp_min": 65.0, "temp_max": 68.0 },
                      "weather": [ { "id": 500, "main": "Rain", "description": "light rain", "icon": "10d" } ],
                      "clouds": { "all": 64 },
                      "pop": 0.3,
                      "rain": { "3h": 1.2 },
                      "dt_txt": "2026-02-23 17:00:00"
                    },
                    {
                      "dt": 1771905600,
                      "main": { "temp": 70.8, "temp_min": 68.0, "temp_max": 72.0 },
                      "weather": [ { "id": 800, "main": "Clear", "description": "clear sky", "icon": "01d" } ],
                      "clouds": { "all": 10 },
                      "pop": 0.0,
                      "dt_txt": "2026-02-23 20:00:00"
                    }
                  ]
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(currentWeatherJson, forecastJson), json) { "test-key" }
            val forecast = api.getForecast(37.42, -122.08)

            assertEquals(67.4f, forecast.providerCurrentTemp!!, 0.001f)
            assertEquals("Clouds", forecast.providerCurrentCondition)
            assertEquals(1771894800000L, forecast.providerCurrentObservedAt)

            assertEquals(1, forecast.daily.size)
            assertEquals("2026-02-23", forecast.daily[0].date)
            assertEquals(70.8f, forecast.daily[0].highTemp, 0.001f)
            assertEquals(67.4f, forecast.daily[0].lowTemp, 0.001f)
            assertEquals(30, forecast.daily[0].precipProbability)
            assertEquals(1.2f, forecast.daily[0].precipAmountMm!!, 0.001f)

            // Hourly forecasts include 3h entries + interpolated intermediate hours
            assertEquals(4, forecast.hourly.size)
            assertEquals(1771894800000L, forecast.hourly[0].dateTime)
            assertEquals(67.4f, forecast.hourly[0].temperature, 0.001f)
            assertEquals("Rain", forecast.hourly[0].condition)
            assertEquals(30, forecast.hourly[0].precipProbability)
            assertEquals(64, forecast.hourly[0].cloudCover)

            // Intermediate hour +1
            assertEquals(1771894800000L + 3600_000L, forecast.hourly[1].dateTime)
            assertEquals(67.4f + (70.8f - 67.4f) * (1f / 3f), forecast.hourly[1].temperature, 0.01f)

            // Intermediate hour +2
            assertEquals(1771894800000L + 7200_000L, forecast.hourly[2].dateTime)
            assertEquals(67.4f + (70.8f - 67.4f) * (2f / 3f), forecast.hourly[2].temperature, 0.01f)

            // Hour +3 (the second 3h point)
            assertEquals(1771905600000L, forecast.hourly[3].dateTime)
            assertEquals(70.8f, forecast.hourly[3].temperature, 0.001f)
            assertEquals("Clear", forecast.hourly[3].condition)
        }

    @Test
    fun `getForecast handles missing optional fields`() =
        runTest {
            val currentWeatherJson =
                """
                {
                  "main": {},
                  "weather": []
                }
                """.trimIndent()

            val forecastJson =
                """
                {
                  "cod": "200",
                  "city": { "timezone": 0 },
                  "list": [
                    {
                      "dt": 1771902000,
                      "main": { "temp": 65.0 },
                      "weather": [ { "main": "Clear" } ]
                    }
                  ]
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(currentWeatherJson, forecastJson), json) { "test-key" }
            val forecast = api.getForecast(37.42, -122.08)

            assertNull(forecast.providerCurrentTemp)
            assertNull(forecast.providerCurrentCondition)
            assertNull(forecast.providerCurrentObservedAt)

            assertEquals(1, forecast.daily.size)
            assertEquals(1, forecast.hourly.size)
            assertNull(forecast.daily[0].precipProbability)
            assertEquals(0f, forecast.daily[0].precipAmountMm!!, 0.001f)
            assertNull(forecast.hourly[0].precipProbability)
            assertEquals(0f, forecast.hourly[0].precipAmountMm!!, 0.001f)
            assertNull(forecast.hourly[0].cloudCover)
        }

    @Test
    fun `getForecast throws ApiAccessException for 401 error`() =
        runTest {
            val responseJson =
                """
                {
                  "cod": 401,
                  "message": "Invalid API key. Please see https://openweathermap.org/faq#error401 for more info."
                }
                """.trimIndent()

            val api = OpenWeatherMapApi(createMockClient(responseJson, responseJson, HttpStatusCode.Unauthorized), json) { "test-key" }

            val exception =
                try {
                    api.getForecast(37.42, -122.08)
                    throw AssertionError("Expected ApiAccessException")
                } catch (e: ApiAccessException) {
                    e
                }

            assertEquals(401, exception.statusCode)
            assertEquals(responseJson, exception.detail)
            assertEquals(
                "OpenWeatherMap fetch failed: status 401. Detail: $responseJson",
                exception.message,
            )
        }
}
