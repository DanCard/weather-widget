package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The behavioural contract for memoizing the blend: **bit-identical results, fewer computations**.
 *
 * These tests deliberately compare the cached path against a *bypassed* computation rather than
 * against hardcoded temperatures — a hardcoded expectation would still pass if the cache served a
 * stale-but-plausible series, which is the exact failure mode worth guarding.
 *
 * See `plans/260817-desktop-idle-cpu-blend-memoization.md`.
 */
@Category(ShortDuration::class)
class ActualsAggregatorCacheTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Before
    fun resetCache() {
        // The cache is a shared object-level memo; other tests in the suite populate it.
        ActualsAggregator.blendCache.clear()
    }

    @Test
    fun `aligned centre snaps to the half hour`() {
        // This quantization is *why* the blend is memoizable — assert it directly so a change to it
        // fails here rather than silently shrinking the cache's hit rate.
        val base = epoch("2026-06-22T15:00:00")
        assertEquals(base, ActualsAggregator.alignedCenterMs(base))
        assertEquals(base, ActualsAggregator.alignedCenterMs(base + 29 * 60_000L))
        assertEquals(base + 3600_000L, ActualsAggregator.alignedCenterMs(base + 30 * 60_000L))
        assertEquals(base + 3600_000L, ActualsAggregator.alignedCenterMs(base + 59 * 60_000L))
        assertEquals(base + 3600_000L, ActualsAggregator.alignedCenterMs(base + 60 * 60_000L))
    }

    @Test
    fun `cached resolution equals the uncached resolution across a half-hour boundary`() {
        val obs = observations()
        val hourly = forecasts()
        val start = epoch("2026-06-22T14:05:00")

        // Sweep a full hour a minute at a time, crossing both a :30 and a :00 boundary — i.e. the
        // exact cadence the desktop status tick runs at.
        for (minute in 0..60) {
            val now = start + minute * 60_000L

            ActualsAggregator.blendCache.clear()
            val uncached = resolve(obs, hourly, now)

            // Same call again, now with whatever the cache has accumulated from previous iterations.
            val cached = resolve(obs, hourly, now)

            assertEquals("temperature diverged at minute $minute", uncached?.first, cached?.first)
            assertEquals("observedAt diverged at minute $minute", uncached?.second, cached?.second)
            assertEquals("fetchedAt diverged at minute $minute", uncached?.third, cached?.third)
        }
    }

    @Test
    fun `the blend is computed once per aligned centre, not once per minute`() {
        val obs = observations()
        val hourly = forecasts()
        val start = epoch("2026-06-22T14:05:00")

        ActualsAggregator.blendCache.clear()
        for (minute in 0..60) {
            resolve(obs, hourly, start + minute * 60_000L)
        }

        // 61 calls spanning 14:05..15:05 snap to just two aligned centres — 14:00 (for 14:05..14:29)
        // and 15:00 (for 14:30..15:05, since 15:00..15:05 rounds down again). So the blend runs
        // twice, not 61 times. This ~30x reduction is the whole point of the change.
        assertEquals("one blend per distinct aligned centre", 2L, ActualsAggregator.blendCache.misses)
        assertEquals(59L, ActualsAggregator.blendCache.hits)
    }

    @Test
    fun `the observed point still advances minute to minute within one cached window`() {
        // Guards the subtle half of the design: the *series* is cached, but the selection
        // `timestamp <= nowMs` must stay live. Freezing the whole resolution for 30 minutes would
        // pass the equality test above while stalling the displayed reading.
        val hourly = forecasts()
        val obs = listOf(
            observation("S", "2026-06-22T14:05:00", 60f),
            observation("S", "2026-06-22T14:20:00", 70f),
        )

        ActualsAggregator.blendCache.clear()
        val before = resolve(obs, hourly, epoch("2026-06-22T14:10:00"))
        val after = resolve(obs, hourly, epoch("2026-06-22T14:25:00"))

        assertNotNull(before)
        assertNotNull(after)
        assertEquals(epoch("2026-06-22T14:05:00"), before!!.second)
        assertEquals(
            "the later tick must pick up the 14:20 reading despite sharing a cached series",
            epoch("2026-06-22T14:20:00"),
            after!!.second,
        )
        // Both ticks are inside the 14:00 aligned centre, so exactly one blend backed them.
        assertEquals(1L, ActualsAggregator.blendCache.misses)
    }

    @Test
    fun `a rebuilt observation list invalidates the cache`() {
        // A fetch rebuilds the lists, which is what must force a recompute. Equal contents in a new
        // list still miss, by design.
        val hourly = forecasts()
        val now = epoch("2026-06-22T15:00:00")

        ActualsAggregator.blendCache.clear()
        resolve(observations(), hourly, now)
        resolve(observations(), hourly, now)

        assertEquals(2L, ActualsAggregator.blendCache.misses)
        assertEquals(0L, ActualsAggregator.blendCache.hits)
    }

    @Test
    fun `a new observation changes the resolved reading`() {
        // End-to-end freshness check: the cache must never be able to hide a newly arrived reading.
        val hourly = forecasts()
        val now = epoch("2026-06-22T15:00:00")

        ActualsAggregator.blendCache.clear()
        val first = resolve(observations(), hourly, now)

        val withNewer = observations() + observation("S", "2026-06-22T14:55:00", 88f)
        val second = resolve(withNewer, hourly, now)

        assertNotNull(first)
        assertNotNull(second)
        assertEquals(epoch("2026-06-22T14:55:00"), second!!.second)
        assertTrue("the newer reading must reach the result", second.first > first!!.first)
    }

    @Test
    fun `the diagnostics path is not served from the cache`() {
        // resolveCurrentObservationDetails passes captureLatestDominantAtOrBeforeMs = nowMs, which is
        // not part of the cache key, so it must recompute or its dominant contribution would be wrong.
        val obs = observations()
        val hourly = forecasts()

        ActualsAggregator.blendCache.clear()
        repeat(3) {
            ActualsAggregator.resolveCurrentObservationDetails(
                observations = obs,
                hourlyForecasts = hourly,
                displaySourceId = SOURCE,
                userLat = LAT,
                userLon = LON,
                nowMs = epoch("2026-06-22T15:00:00"),
                zoneId = zone,
            )
        }
        assertEquals("diagnostics must not populate or consult the memo", 0, ActualsAggregator.blendCache.size)
        assertEquals(0L, ActualsAggregator.blendCache.hits)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun resolve(
        obs: List<ObservationReading>,
        hourly: List<HourlyForecast>,
        now: Long,
    ): Triple<Float, Long, Long>? = ActualsAggregator.resolveCurrentObservation(
        observations = obs,
        hourlyForecasts = hourly,
        displaySourceId = SOURCE,
        userLat = LAT,
        userLon = LON,
        nowMs = now,
        zoneId = zone,
    )

    private fun observations(): List<ObservationReading> = listOf(
        observation("S", "2026-06-22T13:00:00", 64f),
        observation("S", "2026-06-22T14:00:00", 68f),
        observation("T", "2026-06-22T14:00:00", 67f),
        observation("S", "2026-06-22T14:45:00", 71f),
    )

    private fun forecasts(): List<HourlyForecast> {
        val startTime = LocalDateTime.parse("2026-06-21T00:00:00")
        return (0..72).map { index ->
            HourlyForecast(
                dateTime = startTime.plusHours(index.toLong()).atZone(zone).toInstant().toEpochMilli(),
                temperature = 60f + (index % 24),
                condition = "Clear",
                source = SOURCE,
            )
        }
    }

    private fun observation(stationId: String, time: String, temperature: Float) = ObservationReading(
        stationId = stationId,
        stationName = stationId,
        timestamp = epoch(time),
        temperature = temperature,
        condition = "observed",
        locationLat = LAT,
        locationLon = LON,
        distanceKm = 2f,
        api = SOURCE,
        stationType = "OFFICIAL",
    )

    private fun epoch(value: String): Long =
        LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()

    private companion object {
        const val LAT = 37.4220
        const val LON = -122.0841
        val SOURCE: String = WeatherSource.NWS.id
    }
}
