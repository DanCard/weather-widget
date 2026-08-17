package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the memo itself. The behavioural guarantee that matters — "the cached path returns
 * what the uncached path would have" — is proved against the real blend in
 * [ActualsAggregatorCacheTest]; here we pin the keying rules.
 */
@Category(ShortDuration::class)
class BlendSeriesCacheTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Test
    fun `identical arguments hit the cache and compute once`() {
        val cache = BlendSeriesCache()
        val computes = AtomicInteger(0)
        val obs = observations()
        val hourly = forecasts()

        val first = cache.get(obs, hourly, computes)
        val second = cache.get(obs, hourly, computes)

        assertEquals("blend should be computed once", 1, computes.get())
        assertSame("cache must return the same instance, not a recomputation", first, second)
        assertEquals(1L, cache.hits)
        assertEquals(1L, cache.misses)
    }

    @Test
    fun `an equal-but-distinct observation list is a miss`() {
        // Keying is by reference identity: a new list means a fetch rebuilt it, so the memo must not
        // claim a hit even when the contents happen to match. Content comparison would cost about
        // what the blend costs, which would defeat the cache.
        val cache = BlendSeriesCache()
        val computes = AtomicInteger(0)
        val hourly = forecasts()

        cache.get(observations(), hourly, computes)
        cache.get(observations(), hourly, computes)

        assertEquals(2, computes.get())
    }

    @Test
    fun `each scalar argument discriminates the key`() {
        val obs = observations()
        val hourly = forecasts()

        // Every scalar that reshapes the emitted series must be part of the key. If any of these
        // stopped discriminating, one window's series would be served for another's.
        val variants: List<(BlendSeriesCache, AtomicInteger) -> Unit> = listOf(
            { c, n -> c.get(obs, hourly, n, displaySourceId = WeatherSource.OPEN_METEO.id) },
            { c, n -> c.get(obs, hourly, n, userLat = LAT + 1.0) },
            { c, n -> c.get(obs, hourly, n, userLon = LON + 1.0) },
            { c, n -> c.get(obs, hourly, n, startMs = 1L) },
            { c, n -> c.get(obs, hourly, n, endMs = 2L) },
            { c, n -> c.get(obs, hourly, n, personalStationWeight = 2.0) },
            { c, n -> c.get(obs, hourly, n, zoneId = ZoneId.of("UTC")) },
        )

        for (variant in variants) {
            val cache = BlendSeriesCache()
            val computes = AtomicInteger(0)
            cache.get(obs, hourly, computes) // baseline
            variant(cache, computes)
            assertEquals("variant should not have hit the baseline entry", 2, computes.get())
        }
    }

    @Test
    fun `capacity is bounded and the oldest entry is evicted`() {
        val cache = BlendSeriesCache(capacity = 2)
        val computes = AtomicInteger(0)
        val obs = observations()
        val hourly = forecasts()

        cache.get(obs, hourly, computes, startMs = 1L)
        cache.get(obs, hourly, computes, startMs = 2L)
        cache.get(obs, hourly, computes, startMs = 3L) // evicts startMs=1

        assertEquals(2, cache.size)
        cache.get(obs, hourly, computes, startMs = 1L)
        assertEquals("evicted entry must recompute", 4, computes.get())
        cache.get(obs, hourly, computes, startMs = 3L)
        assertEquals("recently used entry must still be resident", 4, computes.get())
    }

    @Test
    fun `a hit promotes its entry so two live windows never evict each other`() {
        // The real usage is exactly this: a current-temp window and a 24h-ago window, alternating
        // every tick. With plain FIFO eviction and a small capacity they would thrash.
        val cache = BlendSeriesCache(capacity = 2)
        val computes = AtomicInteger(0)
        val obs = observations()
        val hourly = forecasts()

        cache.get(obs, hourly, computes, startMs = 1L)
        cache.get(obs, hourly, computes, startMs = 2L)
        repeat(5) {
            cache.get(obs, hourly, computes, startMs = 1L)
            cache.get(obs, hourly, computes, startMs = 2L)
        }
        assertEquals("both windows should stay resident", 2, computes.get())
    }

    @Test
    fun `clear drops every entry`() {
        val cache = BlendSeriesCache()
        val computes = AtomicInteger(0)
        val obs = observations()
        val hourly = forecasts()

        cache.get(obs, hourly, computes)
        cache.clear()
        assertEquals(0, cache.size)
        cache.get(obs, hourly, computes)
        assertEquals(2, computes.get())
    }

    @Test
    fun `concurrent access is safe and converges on one entry`() {
        // compute() runs outside the lock, so concurrent first-callers may duplicate the work. That
        // is benign (the blend is pure) but must not corrupt the cache or return a wrong series.
        val cache = BlendSeriesCache()
        val computes = AtomicInteger(0)
        val obs = observations()
        val hourly = forecasts()
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.submit {
                start.await()
                repeat(50) { cache.get(obs, hourly, computes) }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdown()

        assertEquals("only one entry should survive", 1, cache.size)
        assertTrue("duplicate computes should be bounded by thread count", computes.get() <= threads)
    }

    @Test
    fun `distinct results are not conflated across keys`() {
        val cache = BlendSeriesCache()
        val obs = observations()
        val hourly = forecasts()

        val a = cache.getOrCompute(obs, hourly, SOURCE, LAT, LON, 1L, 9L, 1.0, zone) { result("a") }
        val b = cache.getOrCompute(obs, hourly, SOURCE, LAT, LON, 2L, 9L, 1.0, zone) { result("b") }

        assertNotSame(a, b)
        assertEquals("a", a.observations.single().stationId)
        assertEquals("b", b.observations.single().stationId)
        // And re-reading each key still yields its own value.
        assertSame(a, cache.getOrCompute(obs, hourly, SOURCE, LAT, LON, 1L, 9L, 1.0, zone) { result("x") })
        assertSame(b, cache.getOrCompute(obs, hourly, SOURCE, LAT, LON, 2L, 9L, 1.0, zone) { result("x") })
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun BlendSeriesCache.get(
        observations: List<ObservationReading>,
        hourly: List<HourlyForecast>,
        computes: AtomicInteger,
        displaySourceId: String = SOURCE,
        userLat: Double = LAT,
        userLon: Double = LON,
        startMs: Long = 0L,
        endMs: Long = 10L,
        personalStationWeight: Double = 1.0,
        zoneId: ZoneId = zone,
    ): BlendObservationResult = getOrCompute(
        observations, hourly, displaySourceId, userLat, userLon,
        startMs, endMs, personalStationWeight, zoneId,
    ) {
        computes.incrementAndGet()
        result("s")
    }

    private fun result(stationId: String) = BlendObservationResult(
        observations = listOf(observation(stationId, "2026-06-22T15:00:00", 70f)),
        stats = BlendObservationStats(
            rawObservationCount = 0,
            filteredObservationCount = 0,
            stationCount = 0,
            candidateTimeCount = 0,
            emittedPointCount = 0,
            dedupSkippedCount = 0,
        ),
    )

    private fun observations(): List<ObservationReading> = listOf(
        observation("S", "2026-06-22T14:00:00", 68f),
        observation("S", "2026-06-22T15:00:00", 70f),
    )

    private fun forecasts(): List<HourlyForecast> = (0..24).map { index ->
        HourlyForecast(
            dateTime = LocalDateTime.parse("2026-06-22T00:00:00")
                .plusHours(index.toLong()).atZone(zone).toInstant().toEpochMilli(),
            temperature = 60f + index,
            condition = "Clear",
            source = SOURCE,
        )
    }

    private fun observation(stationId: String, time: String, temperature: Float) = ObservationReading(
        stationId = stationId,
        stationName = stationId,
        timestamp = LocalDateTime.parse(time).atZone(zone).toInstant().toEpochMilli(),
        temperature = temperature,
        condition = "observed",
        locationLat = LAT,
        locationLon = LON,
        distanceKm = 2f,
        api = SOURCE,
        stationType = "OFFICIAL",
    )

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
        val SOURCE: String = WeatherSource.NWS.id
    }
}
