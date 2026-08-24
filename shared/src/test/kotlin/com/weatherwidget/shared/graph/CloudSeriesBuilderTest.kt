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

    /**
     * The forecast curve, and only the forecast curve, is gated on "now": neither the current hour
     * nor a future one gets a frozen day-ago prediction, so both fall back to the live row.
     */
    @Test
    fun `current and future hours take the live forecast, never a frozen one`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(0, 40), live(3, 70)),
            priorForecast = mapOf(now to 90, now + 3 * hour to 90),
            retroActual = emptyMap(),
            nowMs = now,
        )

        assertEquals(2, points.size)
        assertEquals(listOf(40, 70), points.map { it.forecastCover })
        points.forEach { assertTrue("a day-ago prediction is not a comparison yet", !it.isFrozen) }
    }

    /**
     * The regression this gate caused, measured on-device 2026-08-21 11:16: the NWS METAR blend had
     * the 11:00 hour at 65% ready from KNUQ@10:55 and KPAO@10:47, and the graph discarded it and
     * drew 10:00's 100% as its latest actual while the marine layer was visibly breaking up. An
     * observation is a measurement that already happened; the hour containing it being unfinished
     * says nothing about it.
     */
    @Test
    fun `the in-progress hour draws its actual`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(0, 40)),
            priorForecast = mapOf(now to 90),
            retroActual = mapOf(now to 12),
            nowMs = now + 42 * 60_000L, // 42 minutes into the hour
        )

        assertEquals("the latest observation is drawn, not withheld", 12, points[0].actualCover)
        assertEquals("but it gets no frozen comparison", 40, points[0].forecastCover)
        assertTrue(!points[0].isFrozen)
    }

    /**
     * Future hours carry no actual because none is ever filed for them — both platforms' reads stop
     * at `now`. The builder trusts that rather than re-deriving it: if a value for a future hour
     * ever DOES arrive, drawing it is the honest response, not silently hiding it.
     */
    @Test
    fun `a future hour with no filed actual draws none`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(3, 70)),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
        )

        assertNull(points[0].actualCover)
        assertEquals(70, points[0].forecastCover)
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
    fun `a natively-timestamped actual resolves to the nearest hour within 30 minutes`() {
        // The blend goes binless (plans/260824-subhourly-metar-cloud-blend.md): KPAO's :47 report
        // keys the series at :47, not :00. The per-hour actual must still resolve it — same value
        // the old bucket lookup would have filed under that hour.
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 50)),
            priorForecast = emptyMap(),
            retroActual = mapOf(target + 47 * 60_000L to 44, target + 20 * 60_000L to 90),
            nowMs = now,
        )
        // Nearest key to the hour mark is :20, 20 minutes away — wins over :47 (47 away).
        assertEquals(90, points[0].actualCover)
    }

    @Test
    fun `an actual farther than 30 minutes from the hour resolves to none`() {
        // The tolerance must have an edge or yesterday's reading would masquerade as this hour's.
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, 50)),
            priorForecast = emptyMap(),
            retroActual = mapOf(target + 45 * 60_000L to 44),
            nowMs = now,
        )
        assertNull(points[0].actualCover)
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

    /** The graph draws the visible layer: a row with only a low value is data, not a gap. */
    @Test
    fun `a future hour with only a low value draws the low layer`() {
        val row = live(+2, null).copy(cloudCoverLow = 12)
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(row),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
        )

        assertEquals(1, points.size)
        assertEquals(12, points[0].forecastCover)
        assertNull(points[0].actualCover)
    }

    /** Low wins over total on the live curve, or the curve steps at "now" under thin cirrus. */
    @Test
    fun `the live curve prefers the low layer over the total column`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(+2, 95).copy(cloudCoverLow = 8)),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
        )

        assertEquals(8, points[0].forecastCover)
    }
}
