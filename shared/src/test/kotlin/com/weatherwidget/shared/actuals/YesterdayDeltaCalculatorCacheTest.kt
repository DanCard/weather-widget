package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The yesterday-delta blend is memoized on the same cache. Its window is keyed on `observedAtMs` —
 * an *observation* timestamp, not `now` — so it is stable between observation arrivals even as the
 * status tick fires repeatedly.
 *
 * See `plans/260817-desktop-idle-cpu-blend-memoization.md`.
 */
@Category(ShortDuration::class)
class YesterdayDeltaCalculatorCacheTest {
    private val zone = ZoneId.of("America/Los_Angeles")

    @Before
    fun resetCache() {
        ActualsAggregator.blendCache.clear()
    }

    @Test
    fun `repeated ticks at one observedAt compute the blend once`() {
        val obs = observations()
        val hourly = forecasts()
        val observedAt = epoch("2026-06-22T15:00:00")

        val results = (0 until 10).map { computeDelta(obs, hourly, observedAt, 72f) }

        assertNotNull(results.first())
        assertEquals("every tick must agree", 1, results.distinct().size)
        assertEquals("one blend for ten ticks", 1L, ActualsAggregator.blendCache.misses)
        assertEquals(9L, ActualsAggregator.blendCache.hits)
    }

    @Test
    fun `cached delta equals the uncached delta`() {
        val obs = observations()
        val hourly = forecasts()
        val observedAt = epoch("2026-06-22T15:00:00")

        ActualsAggregator.blendCache.clear()
        val uncached = computeDelta(obs, hourly, observedAt, 72f)
        val cached = computeDelta(obs, hourly, observedAt, 72f)

        assertEquals(uncached, cached)
        assertNotNull(uncached)
    }

    @Test
    fun `a new observedAt is a different window and recomputes`() {
        val obs = observations()
        val hourly = forecasts()

        computeDelta(obs, hourly, epoch("2026-06-22T15:00:00"), 72f)
        computeDelta(obs, hourly, epoch("2026-06-22T15:20:00"), 73f)

        assertEquals(2L, ActualsAggregator.blendCache.misses)
    }

    @Test
    fun `the current temperature is applied live, not cached`() {
        // currentObservedTemp is subtracted after the blend, so a changed current reading must move
        // the delta even when the cached yesterday window is reused.
        val obs = observations()
        val hourly = forecasts()
        val observedAt = epoch("2026-06-22T15:00:00")

        val at72 = computeDelta(obs, hourly, observedAt, 72f)
        val at75 = computeDelta(obs, hourly, observedAt, 75f)

        assertNotNull(at72)
        assertNotNull(at75)
        assertEquals(3f, at75!! - at72!!, 0.01f)
        assertEquals("both used the same cached window", 1L, ActualsAggregator.blendCache.misses)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun computeDelta(
        obs: List<ObservationReading>,
        hourly: List<HourlyForecast>,
        observedAtMs: Long,
        currentTemp: Float,
    ): Float? = YesterdayDeltaCalculator.computeDelta(
        observations = obs,
        hourlyForecasts = hourly,
        displaySourceId = SOURCE,
        userLat = LAT,
        userLon = LON,
        observedAtMs = observedAtMs,
        currentObservedTemp = currentTemp,
        zoneId = zone,
    )

    private fun observations(): List<ObservationReading> = listOf(
        observation("S", "2026-06-21T15:00:00", 68f),
        observation("S", "2026-06-22T15:00:00", 72f),
    )

    private fun forecasts(): List<HourlyForecast> {
        val startTime = LocalDateTime.parse("2026-06-21T00:00:00")
        return (0..48).map { index ->
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
