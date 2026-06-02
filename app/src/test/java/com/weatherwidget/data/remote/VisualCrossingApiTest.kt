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
import org.junit.Before
import org.junit.Test
import com.weatherwidget.test.category.ShortDuration
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class VisualCrossingApiTest {
    private lateinit var json: Json

    @Before
    fun setup() {
        json =
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            }
    }

    private fun createMockClient(responseJson: String, status: HttpStatusCode = HttpStatusCode.OK): HttpClient =
        HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = responseJson,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) {
                json(json)
            }
        }

    @Test
    fun `getForecast parses current daily and hourly forecasts`() = runTest {
        val responseJson =
            """
            {
              "currentConditions": {
                "datetimeEpoch": 1774940400,
                "temp": 62.4,
                "conditions": "Rain, Partially cloudy"
              },
              "days": [
                {
                  "datetime": "2026-03-31",
                  "datetimeEpoch": 1774940400,
                  "tempmax": 67.9,
                  "tempmin": 58.0,
                  "precip": 0.5,
                  "precipprob": 100.0,
                  "conditions": "Rain, Partially cloudy",
                  "hours": [
                    {
                      "datetimeEpoch": 1774940400,
                      "temp": 62.4,
                      "precip": 0.02,
                      "precipprob": 65.0,
                      "cloudcover": 62.8,
                      "conditions": "Rain"
                    }
                  ]
                },
                {
                  "datetime": "2026-04-01",
                  "datetimeEpoch": 1775026800,
                  "tempmax": 70.1,
                  "tempmin": 55.2,
                  "precipprob": 10.0,
                  "icon": "partly-cloudy-day",
                  "hours": [
                    {
                      "datetimeEpoch": 1775030400,
                      "temp": 60.1,
                      "precipprob": 10.0,
                      "cloudcover": 40.0,
                      "icon": "partly-cloudy-day"
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val api = VisualCrossingApi(createMockClient(responseJson), json) { "test-key" }
        val forecast = api.getForecast(37.42, -122.08)

        assertEquals(62.4f, forecast.currentTemp!!, 0.001f)
        assertEquals("Rain, Partially cloudy", forecast.currentCondition)
        assertEquals(2, forecast.daily.size)
        assertEquals(2, forecast.hourly.size)
        assertEquals("2026-03-31", forecast.daily[0].date)
        assertEquals(67.9f, forecast.daily[0].highTemp, 0.001f)
        assertEquals(58.0f, forecast.daily[0].lowTemp, 0.001f)
        assertEquals(100, forecast.daily[0].precipProbability)
        assertEquals(12.7f, forecast.daily[0].precipAmountMm!!, 0.001f)
        assertEquals(1774940400000L, forecast.hourly[0].dateTime)
        assertEquals(62.4f, forecast.hourly[0].temperature, 0.001f)
        assertEquals("Rain", forecast.hourly[0].condition)
        assertEquals(65, forecast.hourly[0].precipProbability)
        assertEquals(0.508f, forecast.hourly[0].precipAmountMm!!, 0.001f)
    }

    @Test
    fun `getForecast parses current response`() = runTest {
        val responseJson =
            """
            {
              "currentConditions": {
                "datetimeEpoch": 1774940400,
                "temp": 62.4,
                "conditions": "Rain, Partially cloudy"
              }
            }
            """.trimIndent()

        val api = VisualCrossingApi(createMockClient(responseJson), json) { "test-key" }
        val forecast = api.getForecast(37.42, -122.08)

        assertNotNull(forecast.currentTemp)
        assertEquals(62.4f, forecast.currentTemp!!, 0.001f)
        assertEquals("Rain, Partially cloudy", forecast.currentCondition)
        assertEquals(1774940400000L, forecast.currentObservedAt)
    }

    @Test
    fun `getForecast throws auth error for invalid key payload`() = runTest {
        val responseJson =
            """
            {
              "status": 401,
              "message": "Invalid API key"
            }
            """.trimIndent()

        val api = VisualCrossingApi(createMockClient(responseJson, HttpStatusCode.Unauthorized), json) { "test-key" }

        val exception =
            try {
                api.getForecast(37.42, -122.08)
                throw AssertionError("Expected ApiAccessException")
            } catch (e: ApiAccessException) {
                e
            }

        assertEquals(401, exception.statusCode)
        assertEquals(responseJson, exception.detail)
        assertEquals("Visual Crossing fetch failed: status 401. Detail: $responseJson", exception.message)
    }
}
