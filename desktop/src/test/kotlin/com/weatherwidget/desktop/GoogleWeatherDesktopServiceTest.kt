package com.weatherwidget.desktop

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ActualsProviderResolver
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Real [DesktopWeatherService] + GoogleWeatherApi on recorded responses: desktop parity with the
 * Android fetch, and the quota guarantee that an observations-only refresh never calls Google.
 */
@Category(ShortDuration::class)
class GoogleWeatherDesktopServiceTest {
    // Synchronized: the client issues its requests concurrently, and a plain list lost one.
    private val requests: MutableList<HttpRequestData> = java.util.Collections.synchronizedList(mutableListOf())

    @Before
    fun setup() = ActualsProviderResolver.resetPreferenceSource()

    @After
    fun tearDown() = ActualsProviderResolver.resetPreferenceSource()

    private fun service(): DesktopWeatherService {
        val google = GoogleWeatherFixtures.engine(requests)
        val engine = MockEngine { request ->
            if (request.url.host == "weather.googleapis.com") {
                google.config.requestHandlers.single().invoke(this, request)
            } else {
                // Borrowed actuals feeds (METAR via aviationweather.gov): empty, not under test.
                requests += request
                respond("[]", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        }
        return DesktopWeatherService(
            latitude = 37.422,
            longitude = -122.084,
            weatherSource = WeatherSource.GOOGLE_WEATHER.id,
            apiKeys = mapOf(WeatherSource.GOOGLE_WEATHER.id to "test-key"),
            injectedHttpClient = HttpClient(engine),
        )
    }

    private fun googleRequests() = requests.filter { it.url.host == "weather.googleapis.com" }

    @Test
    fun `full refresh returns daily and hourly and files no synthetic observations`() = runTest {
        val service = service()
        try {
            val result = service.fetchForecast()

            assertEquals(6, googleRequests().size)
            assertEquals(10, result.daily.size)
            val nowHour = Instant.now().truncatedTo(ChronoUnit.HOURS).toEpochMilli()
            assertTrue(result.hourly.any { it.dateTime == nowHour + 71 * 3_600_000L })
            assertTrue(result.hourly.all { it.source == WeatherSource.GOOGLE_WEATHER.id })
            // Forecast-only: no GOOGLE_WEATHER_MAIN backfill rows from its own forecast.
            assertTrue(result.rawObservations.none { it.api == WeatherSource.GOOGLE_WEATHER.id })
            assertTrue(result.providerCurrentTemp != null)
        } finally {
            service.close()
        }
    }

    @Test
    fun `observations-only refresh makes zero Google requests`() = runTest {
        val service = service()
        try {
            service.fetchObservationsOnly(recentOnly = true, userLocationChange = false)
            assertEquals(0, googleRequests().size)
        } finally {
            service.close()
        }
    }
}
