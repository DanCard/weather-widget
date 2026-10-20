package com.weatherwidget.data.remote.weathernext

import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class WeatherNextAvailabilityTest {
    private val now = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L

    @Test
    fun `covered places show the pill, off-map places do not`() {
        assertTrue(WeatherNextAvailability.showPill(37.42, -122.09, null, now)) // Mountain View
        assertTrue(WeatherNextAvailability.showPill(40.71, -74.0, null, now)) // New York
        assertTrue(WeatherNextAvailability.showPill(21.3, -157.8, null, now)) // Honolulu
        assertFalse(WeatherNextAvailability.showPill(50.45, 30.52, null, now)) // Kyiv
        assertFalse(WeatherNextAvailability.showPill(-33.9, 151.2, null, now)) // Sydney
        assertFalse(WeatherNextAvailability.showPill(null, null, null, now))
    }

    @Test
    fun `site gone hides the pill for a day, then it comes back to re-check`() {
        assertFalse(WeatherNextAvailability.showPill(37.42, -122.09, now - 2 * hour, now))
        assertTrue(WeatherNextAvailability.showPill(37.42, -122.09, now - 25 * hour, now))
    }

    private fun clientAnswering(status: HttpStatusCode) =
        WeatherNextForecastClient(HttpClient(MockEngine { respond("nope", status) }), Json)

    @Test
    fun `404 and 410 are gone, other failures are not`() = runBlocking {
        for (s in listOf(HttpStatusCode.NotFound, HttpStatusCode.Gone)) {
            val e = clientAnswering(s).fetchManifest(WeatherNextDomain.CA).exceptionOrNull()
            assertTrue("$s → $e", e is WeatherNextGoneException)
        }
        val e = clientAnswering(HttpStatusCode.ServiceUnavailable).fetchManifest(WeatherNextDomain.CA).exceptionOrNull()
        assertTrue(e != null && e !is WeatherNextGoneException)
    }
}
