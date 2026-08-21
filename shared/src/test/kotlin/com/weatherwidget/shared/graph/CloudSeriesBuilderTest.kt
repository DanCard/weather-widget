package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue

@Category(ShortDuration::class)
class CloudSeriesBuilderTest {

    private val hour = 3_600_000L
    private val now = 1_755_720_000_000L // top of an hour, for readable arithmetic

    private fun live(offsetHours: Long, cover: Int?, fetchedAt: Long = now) = HourlyForecast(
        dateTime = now + offsetHours * hour,
        temperature = 60f,
        condition = "Cloudy",
        cloudCover = cover,
        source = "OPEN_METEO",
        fetchedAt = fetchedAt,
    )

    /**
     * The measured regression this whole feature exists for: at 11:00 the live row had been
     * retro-corrected to 50 while the day-ago prediction was 100. The forecast curve must draw the
     * prediction and the actual curve the correction — not the same number twice.
     */
    @Test
    fun `past hour splits the stored actual from the day-ago prediction`() {
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 99)),
            priorForecast = mapOf(target to 100),
            retroActual = mapOf(target to 50),
            nowMs = now,
        )

        assertEquals(1, points.size)
        assertEquals("forecast curve draws the day-ago prediction", 100, points[0].forecastCover)
        assertEquals("actual curve draws the stored actual", 50, points[0].actualCover)
        assertTrue(points[0].isFrozen)
    }

    @Test
    fun `missing prior forecast falls back to live and says so`() {
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 50)),
            priorForecast = emptyMap(),
            retroActual = mapOf(target to 50),
            nowMs = now,
        )

        assertEquals(50, points[0].forecastCover)
        assertEquals(50, points[0].actualCover)
        assertTrue("a hindcast must never be labelled a frozen forecast", !points[0].isFrozen)
    }

    /**
     * The Android bug, as a test. Every stored row is a pre-hour forecast, so the old `fetchedAt`
     * inference yielded no actuals at all — and with `hasActual` false the renderer drew neither the
     * actual curve nor the dashed forecast. An explicitly filed actual must survive that.
     */
    @Test
    fun `a stored actual is drawn even when every live row predates its own hour`() {
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 99, fetchedAt = target - hour)),
            priorForecast = mapOf(target to 100),
            retroActual = mapOf(target to 8),
            nowMs = now,
        )

        assertEquals("the actual comes from the filed series, not the live row", 8, points[0].actualCover)
        assertEquals(100, points[0].forecastCover)
    }

    /** The converse: `fetchedAt` no longer manufactures an actual on its own. */
    @Test
    fun `a past hour with no filed actual draws no actual`() {
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 50, fetchedAt = target + 2 * hour)),
            priorForecast = mapOf(target to 100),
            retroActual = emptyMap(),
            nowMs = now,
        )

        assertNull("a missing actual is honest; an inferred one is not", points[0].actualCover)
        assertEquals(100, points[0].forecastCover)
    }

    @Test
    fun `current and future hours carry no actual`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(0, 40), live(3, 70)),
            // Stray values for a future hour must not resurrect an actual.
            priorForecast = mapOf(now to 90, now + 3 * hour to 90),
            retroActual = mapOf(now to 11, now + 3 * hour to 11),
            nowMs = now,
        )

        assertEquals(2, points.size)
        points.forEach { assertNull("nothing has happened yet at ${it.timeMs}", it.actualCover) }
        assertEquals(listOf(40, 70), points.map { it.forecastCover })
        points.forEach { assertTrue(!it.isFrozen) }
    }

    /** An hour in progress is not yet past: no settled actual, no useful comparison. */
    @Test
    fun `the in-progress hour is treated as present, not past`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(0, 40)),
            priorForecast = mapOf(now to 90),
            retroActual = mapOf(now to 12),
            nowMs = now + 42 * 60_000L, // 42 minutes into the hour
        )

        assertNull(points[0].actualCover)
        assertEquals(40, points[0].forecastCover)
    }

    /** Gaps stay gaps: an hour with no cloud value must not be drawn as a clear sky. */
    @Test
    fun `hours without cloud data are omitted, never zeroed`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-3, 80), live(-2, null), live(-1, 20)),
            priorForecast = emptyMap(),
            retroActual = mapOf(now - 3 * hour to 80, now - 2 * hour to 55, now - 1 * hour to 20),
            nowMs = now,
        )

        assertEquals(2, points.size)
        assertEquals(listOf(80, 20), points.map { it.actualCover })
    }

    @Test
    fun `out-of-range stored values are clamped`() {
        val target = now - 2 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-2, 50)),
            priorForecast = emptyMap(),
            retroActual = mapOf(target to 140),
            nowMs = now,
        )

        assertEquals(100, points[0].actualCover)
    }

    @Test
    fun `frozen coverage reports the share of past points with a real prediction`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-4, 10), live(-3, 20), live(-2, 30), live(-1, 40)),
            priorForecast = mapOf(now - 4 * hour to 90, now - 3 * hour to 90),
            retroActual = (1..4).associate { now - it * hour to 10 * it },
            nowMs = now,
        )

        assertEquals(0.5f, CloudSeriesBuilder.frozenCoverage(points), 0.0001f)
    }

    @Test
    fun `output is ordered by time regardless of input order`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-1, 40), live(-3, 20), live(-2, 30)),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
        )

        assertEquals(points.map { it.timeMs }.sorted(), points.map { it.timeMs })
    }
}
