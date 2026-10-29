package com.weatherwidget.shared.observations

import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.model.WeatherSource.GOOGLE_WEATHER
import com.weatherwidget.data.model.WeatherSource.NWS
import com.weatherwidget.data.model.WeatherSource.OPEN_METEO
import com.weatherwidget.data.model.WeatherSource.OPEN_WEATHER_MAP
import com.weatherwidget.data.model.WeatherSource.SILURIAN
import com.weatherwidget.data.model.WeatherSource.TOMORROW_IO
import com.weatherwidget.data.model.WeatherSource.WEATHER_API
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The current-conditions run both platforms use (Phase 3 of plans/261013-shared-current-conditions-fetch.md;
 * Android's rule, user 2026-10-13).
 */
@Category(ShortDuration::class)
class CurrentConditionsRunTest {
    private val lat = 37.4166
    private val lon = -122.0889
    private var now = 1_791_900_000_000L

    private class FakeHost(
        val visible: List<WeatherSource>,
        val displayed: Set<String>,
        val failing: Set<WeatherSource> = emptySet(),
        val providers: Map<WeatherSource, WeatherSource> = emptyMap(),
    ) : CurrentConditionsRun.Host {
        val fetched = mutableListOf<WeatherSource>()
        val failures = mutableListOf<WeatherSource>()
        val logs = mutableListOf<String>()
        var lastRun = 0L
        val attempts = mutableMapOf<String, Long>()
        var clock: () -> Long = { 0L }

        override fun visibleSources() = visible
        override fun displayedSourceIds() = displayed
        override fun lastRunMs(latitude: Double, longitude: Double) = lastRun
        override fun setLastRunMs(latitude: Double, longitude: Double, ms: Long) { lastRun = ms }
        override fun lastAttemptMs(feedId: String) = attempts[feedId] ?: 0L
        override fun markAttempt(feedId: String, ms: Long) { attempts[feedId] = ms }
        override suspend fun fetch(feed: WeatherSource, latitude: Double, longitude: Double): CurrentConditionsRun.Reading? {
            fetched += feed
            if (feed in failing) throw ApiAccessException(feed, 429, "quota", "${feed.id} 429")
            return CurrentConditionsRun.Reading(feed, 70f, "Clear", 0L)
        }
        override fun recordSuccess(feed: WeatherSource) = Unit
        override fun recordFailure(feed: WeatherSource, error: Exception) { failures += feed }
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag $message" }
        override fun actualsPreference(source: WeatherSource): WeatherSource? = providers[source]
    }

    private fun run(host: FakeHost) = CurrentConditionsRun(host) { now }.also { host.clock = { now } }

    @Test
    fun `the user's desktop sources - one NWS fetch serves all four, never Google or Open-Meteo`() = runBlocking {
        // config.json 2026-10-13: visible Google, Open-Meteo, Silurian, NWS; Open-Meteo and Google
        // take NWS actuals (Silurian borrows NWS by default in coverage).
        val host = FakeHost(
            listOf(GOOGLE_WEATHER, OPEN_METEO, SILURIAN, NWS),
            displayed = setOf(GOOGLE_WEATHER.id),
            providers = mapOf(OPEN_METEO to NWS, GOOGLE_WEATHER to NWS),
        )
        run(host).run(lat, lon, source = null, reason = "charging_loop", force = false)

        assertEquals(listOf(NWS), host.fetched)
        assertTrue(host.logs.any { it.startsWith("CURR_FETCH_COMPLETE reason=charging_loop successCount=1") })
    }

    @Test
    fun `Open-Meteo on its own actuals is its own feed`() = runBlocking {
        val host = FakeHost(listOf(GOOGLE_WEATHER, OPEN_METEO, NWS), displayed = setOf(GOOGLE_WEATHER.id))
        run(host).run(lat, lon, source = null, reason = "charging_loop", force = false)
        assertEquals(setOf(NWS, OPEN_METEO), host.fetched.toSet())
        assertEquals("one fetch per feed", 2, host.fetched.size)
    }

    @Test
    fun `fourth-ranked and lower feeds that are not displayed are fetched at most hourly`() = runBlocking {
        val host = FakeHost(listOf(NWS, OPEN_METEO, GOOGLE_WEATHER, TOMORROW_IO, WEATHER_API), displayed = setOf(NWS.id))
        val run = run(host)
        run.run(lat, lon, null, "charging_loop", force = false)
        assertEquals(setOf(NWS, OPEN_METEO, TOMORROW_IO, WEATHER_API), host.fetched.toSet())

        host.fetched.clear()
        now += 10 * 60_000L
        run.run(lat, lon, null, "charging_loop", force = false)
        assertEquals("ranks 3 and 4 wait an hour", setOf(NWS, OPEN_METEO), host.fetched.toSet())
        assertTrue(host.logs.any { it.startsWith("CURR_FETCH_THROTTLE_SKIP reason=charging_loop throttled=TOMORROW_IO@rank3, WEATHER_API@rank4") })

        host.fetched.clear()
        now += CurrentConditionsRun.LOW_PRIORITY_INTERVAL_MS
        run.run(lat, lon, null, "charging_loop", force = false)
        assertTrue(TOMORROW_IO in host.fetched && WEATHER_API in host.fetched)
    }

    @Test
    fun `a clock set back before a feed's last attempt does not throttle it`() = runBlocking {
        // Desktop's old in-memory throttle read a negative elapsed time as "not due" until the clock
        // caught up again; Android's (now the run's) reads it as due (user, 2026-10-24).
        val host = FakeHost(listOf(NWS, OPEN_METEO, GOOGLE_WEATHER, TOMORROW_IO), displayed = setOf(NWS.id))
        host.attempts[TOMORROW_IO.id] = now + 3 * CurrentConditionsRun.LOW_PRIORITY_INTERVAL_MS
        run(host).run(lat, lon, null, "charging_loop", force = false)
        assertTrue(TOMORROW_IO in host.fetched)
        assertEquals("marked at the attempt's own time", now, host.attempts[TOMORROW_IO.id])
    }

    @Test
    fun `a displayed low-ranked source is never throttled`() = runBlocking {
        val host = FakeHost(listOf(NWS, OPEN_METEO, GOOGLE_WEATHER, OPEN_WEATHER_MAP), displayed = setOf(OPEN_WEATHER_MAP.id))
        val run = run(host)
        run.run(lat, lon, null, "charging_loop", force = false)
        now += 10 * 60_000L
        host.fetched.clear()
        run.run(lat, lon, null, "charging_loop", force = false)
        assertTrue(OPEN_WEATHER_MAP in host.fetched)
    }

    @Test
    fun `the freshness gate skips an unforced run within five minutes, a forced one goes`() = runBlocking {
        val host = FakeHost(listOf(NWS), displayed = setOf(NWS.id))
        val run = run(host)
        run.run(lat, lon, null, "charging_loop", force = false)
        now += 60_000L
        host.fetched.clear()
        assertEquals(0, run.run(lat, lon, null, "charging_loop", force = false).getOrThrow())
        assertTrue(host.fetched.isEmpty())
        assertTrue(host.logs.any { it.startsWith("CURR_FETCH_FRESH_SKIP") })

        run.run(lat, lon, NWS, "user_observations_screen", force = true)
        assertEquals(listOf(NWS), host.fetched)
    }

    @Test
    fun `a named source fetches its feed alone and a borrower asks for its provider`() = runBlocking {
        val host = FakeHost(listOf(GOOGLE_WEATHER, OPEN_METEO, NWS), displayed = setOf(GOOGLE_WEATHER.id))
        run(host).run(lat, lon, GOOGLE_WEATHER, "battery_loop", force = false)
        assertEquals(listOf(NWS), host.fetched)
    }

    @Test
    fun `a failing feed is recorded and logged, the others still run`() = runBlocking {
        val host = FakeHost(listOf(OPEN_METEO, NWS), displayed = setOf(NWS.id), failing = setOf(OPEN_METEO))
        val attempted = run(host).run(lat, lon, null, "charging_loop", force = false).getOrThrow()
        assertEquals(2, attempted)
        assertEquals(listOf(OPEN_METEO), host.failures)
        assertTrue(host.logs.any { it == "CURR_FETCH_ERROR source=OPEN_METEO code=HTTP_429 detail=quota" })
        assertTrue(host.logs.any { it.startsWith("CURR_FETCH_COMPLETE reason=charging_loop successCount=1") })
    }

    @Test
    fun `each stored reading bumps the stored-data version, a failed feed does not`() = runBlocking {
        val version = com.weatherwidget.shared.util.StoredDataVersion()
        val host = FakeHost(listOf(NWS, TOMORROW_IO), displayed = setOf(NWS.id, TOMORROW_IO.id), failing = setOf(TOMORROW_IO))
        CurrentConditionsRun(host, version) { now }.run(lat, lon, source = null, reason = "test", force = true)

        assertEquals(setOf(NWS, TOMORROW_IO), host.fetched.toSet())
        assertEquals(1L, version.current())
    }
}
