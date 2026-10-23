package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/** [ClimateNormalsRun]: the one climate-normals fetch, against a fake host. */
@Category(ShortDuration::class)
class ClimateNormalsRunTest {
    private class Host(
        val cache: Pair<Map<Int, Float>, Map<Int, Float>> = emptyMap<Int, Float>() to emptyMap(),
        override val openMeteoEnabled: Boolean = true,
        val reply: () -> List<DailyForecast> = { (1..12).map { m -> DailyForecast("2025-%02d-15".format(m), 70f, 50f, "Clear") } },
    ) : ClimateNormalsRun.Host {
        var fetches = 0
        var stored = 0
        val logs = mutableListOf<String>()
        override suspend fun cached(locationKey: String) = cache
        override suspend fun fetchDailyTemps(start: String, end: String): List<DailyForecast> { fetches++; return reply() }
        override suspend fun store(locationKey: String, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>) { stored++ }
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag/$level" }
    }

    @Test
    fun cachedNormalsNeedNoFetch() = runBlocking {
        val host = Host(cache = mapOf(1 to 60f) to mapOf(1 to 40f))
        val result = ClimateNormalsRun.run(host, 37.4, -122.0)
        assertEquals(0, host.fetches)
        assertFalse(result.fetched)
        assertTrue(result.byMonthDay.isNotEmpty())
    }

    @Test
    fun openMeteoDisabledFetchesNothing() = runBlocking {
        val host = Host(openMeteoEnabled = false)
        ClimateNormalsRun.run(host, 37.4, -122.0)
        assertEquals(0, host.fetches)
        assertEquals(listOf("CLIMATE_SKIP_DISABLED/DEBUG"), host.logs)
    }

    @Test
    fun aFetchStoresAndReportsIt() = runBlocking {
        val host = Host()
        val result = ClimateNormalsRun.run(host, 37.4, -122.0)
        assertTrue(result.fetched)
        assertEquals(1, host.stored)
        assertEquals(listOf("CLIMATE_CACHED/INFO"), host.logs)
    }

    @Test
    fun anEmptyReplyIsLoudAndStoresNothing() = runBlocking {
        val host = Host(reply = { emptyList() })
        ClimateNormalsRun.run(host, 37.4, -122.0)
        assertEquals(0, host.stored)
        assertEquals(listOf("CLIMATE_FETCH_EMPTY/WARN"), host.logs)
    }

    @Test
    fun warmLogsFailuresButNeverSwallowsCancellation() {
        val failing = Host(reply = { throw java.io.IOException("down") })
        runBlocking { assertFalse(ClimateNormalsRun.warm(failing, 37.4, -122.0).fetched) }
        assertEquals(listOf("CLIMATE_WARM_FAIL/WARN"), failing.logs)
        val cancelled = Host(reply = { throw CancellationException("stop") })
        var threw = false
        runBlocking {
            try { ClimateNormalsRun.warm(cancelled, 37.4, -122.0) } catch (e: CancellationException) { threw = true }
        }
        assertTrue("cancellation propagates (desktop's catch-all swallowed it)", threw)
    }
}
