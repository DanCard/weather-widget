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

    /** Default [fetchedAt] is "now", which postdates every past hour used here. */
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
    fun `past hour splits the retro-corrected value from the day-ago prediction`() {
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 50)),
            priorForecast = mapOf(target to 100),
            nowMs = now,
        )

        assertEquals(1, points.size.toLong().toInt())
        assertEquals("forecast curve draws the day-ago prediction", 100, points[0].forecastCover)
        assertEquals("actual curve draws the retro-corrected value", 50, points[0].actualCover)
        assertTrue(points[0].isFrozen)
    }

    @Test
    fun `missing prior forecast falls back to live and says so`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 50)),
            priorForecast = emptyMap(),
            nowMs = now,
        )

        assertEquals(50, points[0].forecastCover)
        assertEquals(50, points[0].actualCover)
        assertTrue("a hindcast must never be labelled a frozen forecast", !points[0].isFrozen)
    }

    @Test
    fun `current and future hours carry no actual`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(0, 40), live(3, 70)),
            // A stray prior-run value for a future hour must not resurrect an actual.
            priorForecast = mapOf(now to 90, now + 3 * hour to 90),
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
            nowMs = now,
        )

        assertEquals(2, points.size)
        assertEquals(listOf(80, 20), points.map { it.actualCover })
    }

    /**
     * The Samsung-vs-desktop divergence, as a test. A device that has not refetched since the hour
     * elapsed holds a row that is still a prediction; it must not be drawn as the actual.
     */
    @Test
    fun `a row written before its hour ended is not an actual`() {
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            // Written an hour before the target hour even started.
            liveHours = listOf(live(-5, 99, fetchedAt = target - hour)),
            priorForecast = mapOf(target to 100),
            nowMs = now,
        )

        assertNull("a forecast must never land on the truth curve", points[0].actualCover)
        assertEquals(100, points[0].forecastCover)
    }

    @Test
    fun `a row written after its hour ended is an actual`() {
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 50, fetchedAt = target + 2 * hour)),
            priorForecast = mapOf(target to 100),
            nowMs = now,
        )

        assertEquals(50, points[0].actualCover)
    }

    /** Mid-hour is not enough: the hour was still in progress when the value was written. */
    @Test
    fun `a row written during its own hour is not an actual`() {
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 76, fetchedAt = target + 4 * 60_000L)),
            priorForecast = emptyMap(),
            nowMs = now,
        )

        assertNull(points[0].actualCover)
    }

    @Test
    fun `an unpopulated fetchedAt is treated as not corrected`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 50, fetchedAt = 0L)),
            priorForecast = emptyMap(),
            nowMs = now,
        )

        assertNull("a missing actual is honest; a fabricated one is not", points[0].actualCover)
    }

    @Test
    fun `frozen coverage reports the share of past points with a real prediction`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-4, 10), live(-3, 20), live(-2, 30), live(-1, 40)),
            priorForecast = mapOf(now - 4 * hour to 90, now - 3 * hour to 90),
            nowMs = now,
        )

        assertEquals(0.5f, CloudSeriesBuilder.frozenCoverage(points), 0.0001f)
    }

    @Test
    fun `output is ordered by time regardless of input order`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-1, 40), live(-3, 20), live(-2, 30)),
            priorForecast = emptyMap(),
            nowMs = now,
        )

        assertEquals(points.map { it.timeMs }.sorted(), points.map { it.timeMs })
    }
}
