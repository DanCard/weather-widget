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
    private var hoursErrorBody: String? = null
    private var daysErrorBody: String? = null
    private var clockMs = java.time.Instant.parse("2026-10-06T18:00:00Z").toEpochMilli()

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("google-weather/$name.json")) { name }.readText()

    private fun MockRequestHandleScope.route(request: HttpRequestData): HttpResponseData {
        requests += request
        val path = request.url.encodedPath
        val body = when {
            path.endsWith("currentConditions:lookup") -> fixture("current")
            path.endsWith("forecast/days:lookup") && daysErrorBody != null ->
                return respond(daysErrorBody!!, HttpStatusCode.TooManyRequests)
            path.endsWith("forecast/days:lookup") -> fixture("days")
            path.endsWith("history/hours:lookup") ->
                if (historyStatus == HttpStatusCode.OK) {
                    fixture("history")
                } else {
                    return respond("""{"error":{"code":429,"status":"RESOURCE_EXHAUSTED"}}""", historyStatus)
                }
            path.endsWith("forecast/hours:lookup") && hoursErrorBody != null ->
                return respond(hoursErrorBody!!, HttpStatusCode.TooManyRequests)
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

    private fun hourPageCalls() = requests.count { it.url.encodedPath.endsWith("forecast/hours:lookup") }

    @org.junit.After
    fun clearQuotaBlocks() = SourceQuotaBlocks.reset()

    /** The 72 forecast hours of one full fetch, as they would sit in the DB an hour later. */
    private suspend fun storedAfterFullFetch(): List<com.weatherwidget.data.model.HourlyForecast> {
        val first = api().getForecast(37.422, -122.084, includeHistory = false)
        return first.hourly.map { it.copy(fetchedAt = clockMs - 3_600_000L) }
    }

    @Test
    fun `unchanged first page costs one hour call, not three`() = runBlocking {
        val stored = storedAfterFullFetch()
        requests.clear()
        val google = api()

        val result = google.getForecast(37.422, -122.084, includeHistory = false, storedHours = stored)

        assertEquals(1, hourPageCalls())
        assertEquals(24, result.hourly.size)
        assertTrue(google.lastHoursPaging!!, google.lastHoursPaging!!.startsWith("pages=1 reason=unchanged"))
    }

    @Test
    fun `a changed first page fetches the remaining pages`() = runBlocking {
        val stored = storedAfterFullFetch().map { it.copy(temperature = it.temperature + 2f) }
        requests.clear()
        val google = api()

        val result = google.getForecast(37.422, -122.084, includeHistory = false, storedHours = stored)

        assertEquals(3, hourPageCalls())
        assertEquals(72, result.hourly.size)
        assertTrue(google.lastHoursPaging!!, google.lastHoursPaging!!.startsWith("pages=3 reason=changed"))
    }

    @Test
    fun `without stored hours every page is fetched`() = runBlocking {
        val google = api()
        google.getForecast(37.422, -122.084, includeHistory = false)
        assertEquals(3, hourPageCalls())
        assertEquals("pages=3 reason=no_stored_hours_given", google.lastHoursPaging)
    }

    @Test
    fun `an hourly-limited fetch makes no hour or history call and keeps the daily forecast`() = runBlocking {
        val google = api()
        val result = google.getForecast(37.422, -122.084, includeHistory = true, includeHours = false)

        assertEquals(0, hourPageCalls())
        assertEquals(0, historyCalls())
        assertEquals(10, result.daily.size)
        assertTrue(result.hourly.isEmpty())
        assertEquals("pages=0 reason=hourly_limited", google.lastHoursPaging)
    }

    @Test
    fun `every billed request is reported once with its endpoint and status`() = runBlocking {
        val reported = mutableListOf<String>()
        val google = GoogleWeatherApi(
            HttpClient(MockEngine { route(it) }),
            Json { ignoreUnknownKeys = true },
            nowMs = { clockMs },
            onRequest = { reported += it },
        ) { "test-key" }

        google.getForecast(37.422, -122.084, includeHistory = false)

        assertEquals(requests.size, reported.size)
        assertEquals(3, reported.count { it == "endpoint=forecast/hours status=200" })
        assertEquals(1, reported.count { it == "endpoint=forecast/days status=200" })
        assertEquals(1, reported.count { it == "endpoint=currentConditions status=200" })
    }

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

    // forecast/hours has a per-project daily quota (60 on 2026-10-06). The hours are not optional, so
    // after a daily 429 nothing can succeed until midnight Pacific.

    private fun quota429(unit: String, metric: String = "forecast/hours") = """
        {"error":{"code":429,"status":"RESOURCE_EXHAUSTED","details":[{
          "@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"RATE_LIMIT_EXCEEDED",
          "metadata":{"quota_unit":"$unit","quota_limit":"QueriesPerDay",
                      "quota_metric":"weather.googleapis.com/$metric"}}]}}
    """.trimIndent()

    // forecast/hours and forecast/days each have their own per-project daily quota. Refusing one must
    // not cost the other (user, 2026-10-07: the daily view said "quota used" when only hours was).

    private fun hoursCalls() = requests.count { it.url.encodedPath.endsWith("forecast/hours:lookup") }
    private fun daysCalls() = requests.count { it.url.encodedPath.endsWith("forecast/days:lookup") }
    private val resetMs = java.time.Instant.parse("2026-10-07T07:00:00Z").toEpochMilli()

    @Test
    fun `an hours daily-quota 429 still returns the daily forecast and blocks only hours`() = runBlocking {
        hoursErrorBody = quota429("1/d/{project}", "forecast/hours")
        val api = api()

        val first = api.getForecast(37.422, -122.084, includeHistory = false)
        assertEquals(10, first.daily.size)
        assertTrue(first.hourly.isEmpty())
        assertEquals(mapOf(com.weatherwidget.data.model.ForecastProduct.HOURLY to resetMs), first.quotaRefused.mapValues { it.value.untilMs })
        assertTrue("carries the 429 body for the error page", GoogleQuota.isDailyQuotaExhausted(429, first.quotaRefused.values.single().detail))
        assertEquals(resetMs, SourceQuotaBlocks.blockedUntil(WeatherSource.GOOGLE_WEATHER.id, com.weatherwidget.data.model.ForecastProduct.HOURLY, clockMs))
        assertFalse("days still answer, so the source is not fully blocked", SourceQuotaBlocks.isFullyBlocked(WeatherSource.GOOGLE_WEATHER.id, clockMs))

        val hoursBefore = hoursCalls()
        val daysBefore = daysCalls()
        val second = api.getForecast(37.422, -122.084, includeHistory = false)
        assertEquals("no request into the exhausted hours quota", hoursBefore, hoursCalls())
        assertEquals("days keep being fetched", daysBefore + 1, daysCalls())
        assertEquals(10, second.daily.size)
        assertEquals(setOf(com.weatherwidget.data.model.ForecastProduct.HOURLY), second.quotaRefused.keys)

        // Midnight Pacific resets it.
        clockMs = resetMs + 1_000L
        hoursErrorBody = null
        requests.clear() // the mock picks hour pages by counting prior hour requests
        val reset = api.getForecast(37.422, -122.084, includeHistory = false)
        assertEquals(72, reset.hourly.size)
        assertTrue(reset.quotaRefused.isEmpty())
    }

    @Test
    fun `a days daily-quota 429 still returns the hourly forecast and blocks only days`() = runBlocking {
        daysErrorBody = quota429("1/d/{project}", "forecast/days")
        val api = api()

        val result = api.getForecast(37.422, -122.084, includeHistory = false)
        assertEquals(72, result.hourly.size)
        assertTrue(result.daily.isEmpty())
        assertEquals(setOf(com.weatherwidget.data.model.ForecastProduct.DAILY), result.quotaRefused.keys)
    }

    @Test
    fun `both quotas refused fails the fetch, then skips it without a request`() = runBlocking {
        hoursErrorBody = quota429("1/d/{project}", "forecast/hours")
        daysErrorBody = quota429("1/d/{project}", "forecast/days")
        val api = api()

        val first = runCatching { api.getForecast(37.422, -122.084, includeHistory = false) }.exceptionOrNull()
        assertTrue("$first", first is GoogleDailyQuotaException)
        assertTrue(SourceQuotaBlocks.isFullyBlocked(WeatherSource.GOOGLE_WEATHER.id, clockMs))
        val before = requests.size

        val second = runCatching { api.getForecast(37.422, -122.084, includeHistory = false) }.exceptionOrNull()
        assertTrue("$second", second is GoogleDailyQuotaException)
        assertEquals("no request while both are exhausted", before, requests.size)
        assertEquals(resetMs, (second as GoogleDailyQuotaException).resetAtMs)
        assertTrue("carries the original body for classification", GoogleQuota.isDailyQuotaExhausted(second))

        // Current conditions has its own quota and keeps working.
        api.getCurrent(37.422, -122.084)
        assertEquals(before + 1, requests.size)
    }

    @Test
    fun `quota product comes from the 429's quota metric`() {
        assertEquals(com.weatherwidget.data.model.ForecastProduct.HOURLY, GoogleQuota.forecastProductOf(quota429("1/d/{project}", "forecast/hours")))
        assertEquals(com.weatherwidget.data.model.ForecastProduct.DAILY, GoogleQuota.forecastProductOf(quota429("1/d/{project}", "forecast/days")))
        assertEquals(null, GoogleQuota.forecastProductOf(quota429("1/d/{project}", "history/hours")))
        assertEquals(null, GoogleQuota.forecastProductOf("no body"))
        // The real 2026-10-06 days body, pretty-printed.
        assertEquals(
            com.weatherwidget.data.model.ForecastProduct.DAILY,
            GoogleQuota.forecastProductOf(""" "quota_metric": "weather.googleapis.com/forecast/days", """),
        )
    }

    @Test
    fun `a per-minute 429 on hours does not block the next fetch`() = runBlocking {
        hoursErrorBody = quota429("1/min/{project}")
        val api = api()
        val first = runCatching { api.getForecast(37.422, -122.084) }.exceptionOrNull()
        assertTrue(first is ApiAccessException)
        assertFalse(GoogleQuota.isDailyQuotaExhausted(first))

        hoursErrorBody = null
        requests.clear() // the mock picks hour pages by counting prior hour requests
        assertEquals(72, api.getForecast(37.422, -122.084, includeHistory = false).hourly.size)
    }

    @Test
    fun `daily quota detection reads the ErrorInfo quota unit`() {
        assertTrue(GoogleQuota.isDailyQuotaExhausted(429, quota429("1/d/{project}")))
        assertFalse(GoogleQuota.isDailyQuotaExhausted(429, quota429("1/min/{project}")))
        assertFalse("status must be 429", GoogleQuota.isDailyQuotaExhausted(403, quota429("1/d/{project}")))
        assertFalse(GoogleQuota.isDailyQuotaExhausted(429, "No error body"))
        assertFalse(GoogleQuota.isDailyQuotaExhausted(429, null))
        // The literal Fold body shape: pretty-printed with spaces after the colon.
        assertTrue(GoogleQuota.isDailyQuotaExhausted(429, """ "quota_unit": "1/d/{project}", """))
    }
}
