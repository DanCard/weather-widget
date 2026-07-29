package com.weatherwidget.data.remote

import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

@Category(ShortDuration::class)
class WeatherApiHistoryTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `history uses one date endpoint and parses response without current object`() = runBlocking {
        var requestedPath = ""
        var requestedDate: String? = null
        var includedEndDate = false
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requestedPath = request.url.encodedPath
                    requestedDate = request.url.parameters["dt"]
                    includedEndDate = request.url.parameters.contains("end_dt")
                    respond(
                        content = HISTORY_RESPONSE,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
            install(ContentNegotiation) { json(json) }
        }

        val result =
            WeatherApi(client, json) { "test-key" }
                .getHistory(37.42, -122.08, LocalDate.of(2026, 7, 27))

        assertEquals("/v1/history.json", requestedPath)
        assertEquals("2026-07-27", requestedDate)
        assertFalse(includedEndDate)
        assertNull(result.currentTemp)
        assertEquals(1, result.daily.size)
        assertEquals(2, result.hourly.size)
        assertEquals(78.2f, result.daily.single().highTemp, 0.001f)
        assertEquals(0.8f, result.hourly[1].precipAmountMm!!, 0.001f)
        assertEquals(72, result.hourly[1].cloudCover)
    }

    @Test
    fun `history preserves typed HTTP failure without exposing key`() = runBlocking {
        val secretKey = "secret-test-key"
        val client = HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = """{"error":{"message":"key=$secretKey history unavailable"}}""",
                        status = HttpStatusCode.Forbidden,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            }
        }
        val exception =
            runCatching {
                WeatherApi(client, json) { secretKey }
                    .getHistory(37.42, -122.08, LocalDate.of(2026, 7, 27))
            }.exceptionOrNull() as ApiAccessException

        assertEquals(403, exception.statusCode)
        assertFalse(exception.message.orEmpty().contains(secretKey))
        assertFalse(exception.detail.contains(secretKey))
    }

    companion object {
        private val HISTORY_RESPONSE =
            """
            {
              "forecast": {
                "forecastday": [{
                  "date": "2026-07-27",
                  "day": {
                    "maxtemp_f": 78.2,
                    "mintemp_f": 57.4,
                    "totalprecip_mm": 0.8,
                    "daily_chance_of_rain": 35,
                    "condition": {"text": "Partly cloudy"}
                  },
                  "hour": [
                    {
                      "time_epoch": 1785135600,
                      "temp_f": 58.1,
                      "precip_mm": 0.0,
                      "chance_of_rain": 5,
                      "cloud": 12,
                      "condition": {"text": "Clear"}
                    },
                    {
                      "time_epoch": 1785139200,
                      "temp_f": 59.0,
                      "precip_mm": 0.8,
                      "chance_of_rain": 35,
                      "cloud": 72,
                      "condition": {"text": "Light rain"}
                    }
                  ]
                }]
              }
            }
            """.trimIndent()
    }
}
