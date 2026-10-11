package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastParts
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * What each splitting client sends for [ForecastParts] (no network):
 * performance/261011-hourly-cadence-by-last-view-and-fuzzy-full-window.md.
 */
@Category(ShortDuration::class)
class ForecastPartsRequestTest {
    private val requests = mutableListOf<HttpRequestData>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(body: String = "{}") = HttpClient(
        MockEngine { request ->
            requests += request
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    private val openMeteoBody = """{"timezone":"UTC","current":{"time":"2026-10-11T10:00","temperature_2m":60}}"""

    @Test
    fun `Open-Meteo daily-only drops hourly but keeps daily, current and minutely_15`() = runBlocking {
        OpenMeteoApi(client(openMeteoBody), json).getForecast(37.4, -122.1, parts = ForecastParts.DAILY)
        val params = requests.single().url.parameters
        assertNull(params["hourly"])
        assertNotNull(params["daily"])
        assertNotNull(params["current"])
        assertNotNull(params["minutely_15"])
    }

    @Test
    fun `Open-Meteo hourly-only drops daily but keeps hourly, current and minutely_15`() = runBlocking {
        OpenMeteoApi(client(openMeteoBody), json).getForecast(37.4, -122.1, parts = ForecastParts.HOURLY)
        val params = requests.single().url.parameters
        assertNull(params["daily"])
        assertNotNull(params["hourly"])
        assertNotNull(params["current"])
        assertNotNull(params["minutely_15"])
    }

    @Test
    fun `Open-Meteo both is one request with every block`() = runBlocking {
        OpenMeteoApi(client(openMeteoBody), json).getForecast(37.4, -122.1)
        val params = requests.single().url.parameters
        assertNotNull(params["daily"])
        assertNotNull(params["hourly"])
    }

    private fun tomorrow() = TomorrowIoApi(client("""{"data":{"timelines":[]}}"""), json) { "key" }

    @Test
    fun `Tomorrow io asks only the timestep each part needs`() = runBlocking {
        tomorrow().getForecast(37.4, -122.1, parts = ForecastParts.DAILY)
        tomorrow().getForecast(37.4, -122.1, parts = ForecastParts.HOURLY)
        tomorrow().getForecast(37.4, -122.1, parts = ForecastParts.BOTH)
        assertEquals(listOf(listOf("1d"), listOf("1h"), listOf("1h", "1d")), requests.map { it.url.parameters.getAll("timesteps") })
    }

    private fun silurian() = SilurianApi(client(), json) { "key" }

    private fun silurianPaths() = requests.map { it.url.encodedPath.substringAfter("/api/v1") }.sorted()

    @Test
    fun `Silurian daily-only skips the hourly endpoint`() = runBlocking {
        val result = silurian().getForecast(37.4, -122.1, parts = ForecastParts.DAILY)
        assertEquals(listOf("/forecast/daily"), silurianPaths())
        assertTrue(result.hourly.isEmpty())
    }

    @Test
    fun `Silurian hourly-only skips the daily endpoint`() = runBlocking {
        val result = silurian().getForecast(37.4, -122.1, parts = ForecastParts.HOURLY)
        assertEquals(listOf("/forecast/hourly"), silurianPaths())
        assertTrue(result.daily.isEmpty())
    }

    @Test
    fun `Silurian both calls both endpoints`() = runBlocking {
        silurian().getForecast(37.4, -122.1)
        assertEquals(listOf("/forecast/daily", "/forecast/hourly"), silurianPaths())
        assertFalse(requests.isEmpty())
    }
}
