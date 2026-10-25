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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class OpenWeatherMapDesktopServiceTest {

    @Test
    fun `full refresh carries the provider current but files it as no observation`() = runTest {
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

            assertEquals(71.2f, result.providerCurrentTemp!!, 0.01f)
            assertEquals("Clear", result.providerCurrentCondition)
            assertEquals(CURRENT_DT_MS, result.providerCurrentObservedAt)

            // Current readings come only from the current-conditions path (SourceFetchTable, F1); the
            // forecast's elapsed 3-hourly slots are still filed as provider history.
            assertTrue(
                "no row from the current endpoint: ${result.rawObservations}",
                result.rawObservations.none { it.timestamp == CURRENT_DT_MS },
            )
            val history = result.rawObservations.single { it.timestamp == 1724419200000L }
            assertEquals("OPEN_WEATHER_MAP_MAIN", history.stationId)
            assertEquals(76.5f, history.temperature, 0.01f)
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

            assertEquals(71.2f, result.providerCurrentTemp!!, 0.01f)
            assertEquals(1, result.rawObservations.size)
            val obs = result.rawObservations.single()
            assertEquals("OPEN_WEATHER_MAP_MAIN", obs.stationId)
            assertEquals(71.2f, obs.temperature, 0.01f)
            assertEquals(CURRENT_DT_MS, obs.timestamp)
        } finally {
            service.close()
        }
    }

    private fun currentWeatherJson() = """
        {
          "coord": { "lon": -122.08, "lat": 37.42 },
          "weather": [{ "id": 800, "main": "Clear", "description": "clear sky", "icon": "01d" }],
          "main": { "temp": 71.2, "feels_like": 71.0, "temp_min": 70.0, "temp_max": 72.0, "humidity": 45 },
          "dt": 1724420700
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

    private companion object {
        /** The current endpoint's reading time: 25 min after the first forecast slot, so the two rows differ. */
        const val CURRENT_DT_MS = 1724420700000L
    }
}
