package com.weatherwidget.data.remote

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Replays responses recorded from the live API on 2026-10-06 (Mountain View, IMPERIAL) from
 * `src/test/resources/google-weather/`.
 */
@Category(ShortDuration::class)
class GoogleWeatherApiTest {

    // Synchronized: the client issues its requests concurrently, and a plain list lost one.
    private val requests: MutableList<HttpRequestData> = java.util.Collections.synchronizedList(mutableListOf())
    private var historyStatus = HttpStatusCode.OK
    private var clockMs = java.time.Instant.parse("2026-10-06T18:00:00Z").toEpochMilli()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("google-weather/$name.json")) { name }.readText()

    private fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData {
        requests += request
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("currentConditions:lookup") -> fixture("current")
            path.endsWith("forecast/days:lookup") -> fixture("days")
            path.endsWith("history/hours:lookup") ->
                if (historyStatus == HttpStatusCode.OK) {
                    fixture("history")
                } else {
                    return respond("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""", historyStatus)
                }
            path.endsWith("forecast/hours:lookup") -> {
                // Pages 1-3 per fetch: page 1 has no token, 2 and 3 chain from the recorded tokens.
                val n = requests.count { it.url.encodedPath.endsWith("forecast/hours:lookup") }
                fixture("hours${(n - 1) % 3 + 1}")
            }
            else -> error("unexpected $path")
        }
        return respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    }

    private fun api(key: String? = "test-key", engine: MockEngine = MockEngine { route(it) }) =
        GoogleWeatherApi(HttpClient(engine), Json { ignoreUnknownKeys = true }, nowMs = { clockMs }) { key }

    private fun historyCalls() = requests.count { it.url.encodedPath.endsWith("history/hours:lookup") }

    @Test
    fun `parses ten daily rows in fahrenheit with day and night rain chance`() = runBlocking {
        val result = api().getForecast(37.422, -122.084)

        assertEquals(10, result.daily.size)
        val today = result.daily.first()
        assertEquals("2026-10-06", today.date)
        assertEquals(81.9f, today.highTemp!!, 0.01f)
        assertTrue("low is °F, not °C", today.lowTemp!! > 45f)
        assertEquals(WeatherSource.GOOGLE_WEATHER.id, today.source)
        assertEquals("CLEAR", today.iconToken)
        assertEquals("Clear", today.condition)
        assertNotNull(today.daytimePrecipProbability)
        assertNotNull(today.nighttimePrecipProbability)
        assertEquals(result.daily.map { it.date }.distinct(), result.daily.map { it.date })
    }

    @Test
    fun `follows exactly three hour pages and merges history without duplicate hours`() = runBlocking {
        val result = api().getForecast(37.422, -122.084)

        val hourPages = requests.filter { it.url.encodedPath.endsWith("forecast/hours:lookup") }
        assertEquals(3, hourPages.size)
        assertEquals(null, hourPages[0].url.parameters["pageToken"])
        assertNotNull(hourPages[1].url.parameters["pageToken"])
        // current + days + 3 hour pages + history: the billed cost of one full fetch.
        assertEquals(6, requests.size)

        val times = result.hourly.map { it.dateTime }
        assertEquals(times.distinct(), times)
        assertEquals(times.sorted(), times)
        // 24 elapsed hours from history + 72 forecast hours, contiguous.
        assertEquals(96, result.hourly.size)
        assertTrue(times.zipWithNext().all { (a, b) -> b - a == 3_600_000L })
        assertTrue(result.hourly.all { it.source == WeatherSource.GOOGLE_WEATHER.id })
        assertTrue(result.hourly.all { it.temperature in 30f..110f })
    }

    @Test
    fun `current conditions and no observations`() = runBlocking {
        val result = api().getForecast(37.422, -122.084)
        assertEquals(73.4f, result.providerCurrentTemp!!, 0.01f)
        assertEquals("Clear", result.providerCurrentCondition)
        assertNotNull(result.providerCurrentObservedAt)
        // Forecast-only source: the client itself never produces observation rows.
        assertTrue(result.rawObservations.isEmpty())
    }

    @Test
    fun `getCurrent costs exactly one request`() = runBlocking {
        val result = api().getCurrent(37.422, -122.084)
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.encodedPath.endsWith("currentConditions:lookup"))
        assertEquals(73.4f, result.providerCurrentTemp!!, 0.01f)
        assertTrue(result.daily.isEmpty() && result.hourly.isEmpty())
    }

    @Test
    fun `key travels in the header, never the url`() = runBlocking {
        api(key = "SECRET123").getForecast(37.422, -122.084)
        assertTrue(requests.all { it.headers["X-Goog-Api-Key"] == "SECRET123" })
        assertFalse(requests.any { "SECRET123" in it.url.toString() })
        assertTrue(requests.all { it.url.parameters["unitsSystem"] == "IMPERIAL" })
    }

    @Test
    fun `missing key makes no request`() = runBlocking {
        try {
            api(key = " ").getForecast(37.422, -122.084)
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("GOOGLE_WEATHER_API_KEY"))
        }
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `http error surfaces as ApiAccessException for this source`() = runBlocking {
        val engine = MockEngine {
            respond("""{"error":{"code":403,"status":"PERMISSION_DENIED"}}""", HttpStatusCode.Forbidden)
        }
        try {
            api(engine = engine).getForecast(37.422, -122.084)
            fail("expected ApiAccessException")
        } catch (e: ApiAccessException) {
            assertEquals(WeatherSource.GOOGLE_WEATHER, e.source)
            assertEquals(403, e.statusCode)
        }
    }

    // history/hours has a small per-project daily quota (default 10; 20 since 2026-10-06), so it is optional and best-effort.

    @Test
    fun `a fetch that does not need history makes no history call`() = runBlocking {
        val result = api().getForecast(37.422, -122.084, includeHistory = false)
        assertEquals(0, historyCalls())
        assertEquals(5, requests.size)
        assertEquals(72, result.hourly.size)
    }

    @Test
    fun `a history 429 keeps the forecast and blocks history until the quota resets`() = runBlocking {
        historyStatus = HttpStatusCode.TooManyRequests
        val api = api()

        val first = api.getForecast(37.422, -122.084)
        assertEquals(10, first.daily.size)
        assertEquals("forecast hours survive; only the elapsed hours are lost", 72, first.hourly.size)
        assertEquals(1, historyCalls())

        api.getForecast(37.422, -122.084)
        assertEquals("no second call into an exhausted quota", 1, historyCalls())

        // Midnight Pacific (07:00Z in October) resets the quota.
        clockMs = java.time.Instant.parse("2026-10-07T07:00:01Z").toEpochMilli()
        historyStatus = HttpStatusCode.OK
        api.getForecast(37.422, -122.084)
        assertEquals(2, historyCalls())
    }

    @Test
    fun `history is needed only for a site with no history hours in the last day`() {
        val now = clockMs
        assertTrue(GoogleWeatherApi.needsHistory(emptyList(), now))
        assertTrue("hours older than a day do not count", GoogleWeatherApi.needsHistory(listOf(now - 30 * 3_600_000L), now))
        assertFalse(GoogleWeatherApi.needsHistory(listOf(now - 5 * 3_600_000L), now))
    }
}
