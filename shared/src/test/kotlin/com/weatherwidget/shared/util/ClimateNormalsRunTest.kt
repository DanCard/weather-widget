package com.weatherwidget.shared.util

import com.weatherwidget.data.model.DailyForecast
import com.weatherwidget.test.category.ShortDuration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
        val reply: () -> List<DailyForecast> = { (1..12).map { m -> DailyForecast("2025-%02d-15".format(m), 70f, 50f, "Clear") } },
    ) : ClimateNormalsRun.Host {
        var fetches = 0
        var stored = 0
        val repaints = mutableListOf<String>()
        var repaintFails = false
        val logs = mutableListOf<String>()
        override suspend fun cached(locationKey: String) = cache
        override suspend fun fetchDailyTemps(start: String, end: String): List<DailyForecast> { fetches++; return reply() }
        override suspend fun store(locationKey: String, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>) { stored++ }
        override suspend fun log(tag: String, message: String, level: String) { logs += "$tag/$level" }
        override suspend fun onNormalsCached(locationKey: String) {
            if (repaintFails) throw IllegalStateException("WorkManager not initialised")
            repaints += locationKey; logs += "REPAINT_HOOK"
        }
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
    fun noCacheFetchesWhateverTheSourceList() = runBlocking {
        // Normals are site data from the free archive: no source-list gate (user, 2026-10-23).
        val host = Host()
        ClimateNormalsRun.run(host, 37.4, -122.0)
        assertEquals(1, host.fetches)
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

    @Test
    fun ensureForSiteRepaintsOnceWhenItNewlyCaches() = runBlocking {
        // The 2026-11-22 round-trip: back at a site whose normals retention dropped. The hook runs
        // after the store, so the repaint reads the new rows.
        val host = Host()
        val newlyCached = ClimateNormalsRun.ensureForSite(host, 33.645, -111.911, "test")
        assertTrue(newlyCached)
        assertEquals(listOf(ClimateNormals.locationKey(33.645, -111.911)), host.repaints)
        assertEquals(listOf("CLIMATE_CACHED/INFO", "REPAINT_HOOK", "CLIMATE_REPAINT/INFO"), host.logs)
    }

    @Test
    fun ensureForSiteOnACachedSiteIsSilent() = runBlocking {
        val host = Host(cache = mapOf(1 to 60f) to mapOf(1 to 40f))
        assertFalse(ClimateNormalsRun.ensureForSite(host, 37.4, -122.0, "test"))
        assertEquals(0, host.fetches)
        assertTrue(host.repaints.isEmpty())
        assertTrue(host.logs.isEmpty())
    }

    @Test
    fun ensureForSiteNeverRepaintsAfterAnEmptyOrFailedFetch() = runBlocking {
        val empty = Host(reply = { emptyList() })
        assertFalse(ClimateNormalsRun.ensureForSite(empty, 37.4, -122.0, "test"))
        assertTrue(empty.repaints.isEmpty())
        val failing = Host(reply = { throw java.io.IOException("down") })
        assertFalse(ClimateNormalsRun.ensureForSite(failing, 37.4, -122.0, "test"))
        assertTrue(failing.repaints.isEmpty())
        assertEquals(listOf("CLIMATE_WARM_FAIL/WARN"), failing.logs)
    }

    @Test
    fun aFailingRepaintHookIsLoggedNotThrown() = runBlocking {
        val host = Host().apply { repaintFails = true }
        assertTrue(ClimateNormalsRun.ensureForSite(host, 37.4, -122.0, "test"))
        assertEquals(listOf("CLIMATE_CACHED/INFO", "CLIMATE_REPAINT_FAIL/WARN"), host.logs)
    }

    @Test
    fun parallelSettlesFetchAndRepaintOnce() = runBlocking {
        // Shares one store between two hosts, as two concurrent workers share one database.
        var cache: Pair<Map<Int, Float>, Map<Int, Float>> = emptyMap<Int, Float>() to emptyMap()
        var fetches = 0
        var repaints = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        fun host() = object : ClimateNormalsRun.Host {
            override suspend fun cached(locationKey: String) = cache
            override suspend fun fetchDailyTemps(start: String, end: String): List<DailyForecast> {
                fetches++
                gate.await()
                return (1..12).map { m -> DailyForecast("2025-%02d-15".format(m), 70f, 50f, "Clear") }
            }
            override suspend fun store(locationKey: String, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>) {
                cache = monthlyHigh to monthlyLow
            }
            override suspend fun log(tag: String, message: String, level: String) {}
            override suspend fun onNormalsCached(locationKey: String) { repaints++ }
        }
        val a = async { ClimateNormalsRun.ensureForSite(host(), 37.4, -122.0, "a") }
        val b = async { ClimateNormalsRun.ensureForSite(host(), 37.4, -122.0, "b") }
        yield()
        gate.complete(Unit)
        val results = listOf(a.await(), b.await())
        println("parallel settles: results=$results fetches=$fetches repaints=$repaints")
        assertEquals(1, fetches)
        assertEquals(1, repaints)
        assertEquals(listOf(true, false), results.sortedDescending())
    }
}
