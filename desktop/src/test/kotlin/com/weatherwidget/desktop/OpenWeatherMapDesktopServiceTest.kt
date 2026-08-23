package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class OpenWeatherMapDesktopServiceTest {

    @Test
    fun `full refresh returns forecast and populates current observation in rawObservations`() = runTest {
        val engine = MockEngine { request ->
            val content = when {
                request.url.encodedPath == "/data/2.5/weather" -> currentWeatherJson()
                request.url.encodedPath == "/data/2.5/forecast" -> forecastJson()
                else -> "{}"
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.OPEN_WEATHER_MAP.id,
            apiKeys = mapOf(WeatherSource.OPEN_WEATHER_MAP.id to "test-key"),
            injectedHttpClient = HttpClient(engine),
        )

        try {
            val result = service.fetchForecast()

            assertEquals(76.5f, result.providerCurrentTemp!!, 0.01f)
            assertEquals("Clear", result.providerCurrentCondition)
            assertEquals(1724419200000L, result.providerCurrentObservedAt)

            val obs = result.rawObservations.find { it.stationId == "OPEN_WEATHER_MAP_MAIN" }
            assertNotNull(obs)
            assertEquals(WeatherSource.OPEN_WEATHER_MAP.id, obs!!.api)
            assertEquals(76.5f, obs.temperature, 0.01f)
            assertEquals("Clear", obs.condition)
            assertEquals(1724419200000L, obs.timestamp)
        } finally {
            service.close()
        }
    }

    @Test
    fun `observations only refresh returns current observation`() = runTest {
        val engine = MockEngine { request ->
            val content = when {
                request.url.encodedPath == "/data/2.5/weather" -> currentWeatherJson()
                request.url.encodedPath == "/data/2.5/forecast" -> forecastJson()
                else -> "{}"
            }
            respond(
                content = content,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val service = DesktopWeatherService(
            latitude = 37.42,
            longitude = -122.08,
            weatherSource = WeatherSource.OPEN_WEATHER_MAP.id,
            apiKeys = mapOf(WeatherSource.OPEN_WEATHER_MAP.id to "test-key"),
            injectedHttpClient = HttpClient(engine),
        )

        try {
            val result = service.fetchObservationsOnly(recentOnly = true)

            assertEquals(76.5f, result.providerCurrentTemp!!, 0.01f)
            assertEquals(1, result.rawObservations.size)
            val obs = result.rawObservations.single()
            assertEquals("OPEN_WEATHER_MAP_MAIN", obs.stationId)
            assertEquals(76.5f, obs.temperature, 0.01f)
        } finally {
            service.close()
        }
    }

    private fun currentWeatherJson() = """
        {
          "coord": { "lon": -122.08, "lat": 37.42 },
          "weather": [{ "id": 800, "main": "Clear", "description": "clear sky", "icon": "01d" }],
          "main": { "temp": 76.5, "feels_like": 76.0, "temp_min": 74.0, "temp_max": 78.0, "humidity": 45 },
          "dt": 1724419200
        }
    """.trimIndent()

    private fun forecastJson() = """
        {
          "city": { "id": 5375480, "name": "Mountain View", "timezone": -25200 },
          "list": [
            {
              "dt": 1724419200,
              "main": { "temp": 76.5, "temp_min": 74.0, "temp_max": 78.0 },
              "weather": [{ "id": 800, "main": "Clear", "description": "clear sky", "icon": "01d" }],
              "clouds": { "all": 0 },
              "pop": 0.0
            },
            {
              "dt": 1724430000,
              "main": { "temp": 79.0, "temp_min": 76.0, "temp_max": 80.0 },
              "weather": [{ "id": 800, "main": "Clear", "description": "clear sky", "icon": "01d" }],
              "clouds": { "all": 0 },
              "pop": 0.0
            }
          ]
        }
    """.trimIndent()
}
