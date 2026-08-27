package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The bands' half of [CloudSeriesBuilder], which mirrors the low band's forecast/actual split.
 * The low band's own cases live in [CloudSeriesBuilderTest].
 */
@Category(ShortDuration::class)
class CloudSeriesBuilderBandsTest {

    private val hour = 3_600_000L
    private val now = 1_755_720_000_000L

    private fun live(
        offsetHours: Long,
        cover: Int? = 40,
        mid: Int? = null,
        high: Int? = null,
    ) = HourlyForecast(
        dateTime = now + offsetHours * hour,
        temperature = 60f,
        condition = "Cloudy",
        cloudCover = cover,
        cloudCoverMid = mid,
        cloudCoverHigh = high,
        source = "OPEN_METEO",
        fetchedAt = now,
    )

    /**
     * The defect this change exists to fix: for an elapsed hour the live row has already been
     * retro-corrected, so reading its bands straight off drew the ACTUAL in the forecast's grey.
     */
    @Test
    fun `past hour splits the retro-corrected bands from the day-ago band prediction`() {
        val target = now - 5 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, mid = 20, high = 15)),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
            priorBands = mapOf(target to CloudBands(mid = 80, high = 70)),
            retroBands = mapOf(target to CloudBands(mid = 20, high = 15)),
        )

        val point = points.single()
        assertEquals(80, point.forecastBands.mid)
        assertEquals(70, point.forecastBands.high)
        assertEquals(20, point.actualBands.mid)
        assertEquals(15, point.actualBands.high)
        assertTrue(point.isFrozenBands)
    }

    @Test
    fun `future hour takes the live bands and is never frozen`() {
        val target = now + 3 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(3, mid = 55, high = 100)),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
            // A snapshot filed against a future hour must not be swapped in: nothing has happened
            // yet, so there is no comparison to draw.
            priorBands = mapOf(target to CloudBands(mid = 5, high = 5)),
        )

        val point = points.single()
        assertEquals(55, point.forecastBands.mid)
        assertEquals(100, point.forecastBands.high)
        assertFalse(point.isFrozenBands)
    }

    @Test
    fun `a missing band snapshot falls back to live and says so`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-5, mid = 44, high = null)),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
            priorBands = emptyMap(),
        )

        val point = points.single()
        assertEquals(44, point.forecastBands.mid)
        assertNull(point.forecastBands.high)
        assertFalse("without a snapshot the bands are a hindcast, not a forecast", point.isFrozenBands)
    }

    /**
     * The low band has a stored history of day-ago predictions that the bands do not, so an hour
     * can carry a genuine frozen low forecast while its bands are only the live row. Collapsing the
     * two flags would let the render claim a band comparison it cannot make.
     */
    @Test
    fun `isFrozen and isFrozenBands move independently`() {
        val target = now - 2 * hour
        val point = CloudSeriesBuilder.build(
            liveHours = listOf(live(-2, cover = 30, mid = 44)),
            priorForecast = mapOf(target to 90),
            retroActual = emptyMap(),
            nowMs = now,
            priorBands = emptyMap(),
        ).single()

        assertTrue(point.isFrozen)
        assertFalse(point.isFrozenBands)
    }

    /** Same ±30-minute anchor tolerance the low actual gets, for the same sub-hourly rows. */
    @Test
    fun `observed bands anchor to the nearest report within the tolerance`() {
        val target = now - 4 * hour
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-4, mid = 10)),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
            retroBands = mapOf(
                target + 15 * 60_000L to CloudBands(mid = 60),
                target + 45 * 60_000L to CloudBands(mid = 99),
            ),
        )

        assertEquals(60, points.single().actualBands.mid)
    }

    @Test
    fun `an observed band beyond the tolerance is not borrowed`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-4, mid = 10)),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
            retroBands = mapOf(now - 4 * hour + 91 * 60_000L to CloudBands(mid = 99)),
        )

        assertEquals(CloudBands.NONE, points.single().actualBands)
    }

    @Test
    fun `a source that reports no bands leaves both pairs empty`() {
        val points = CloudSeriesBuilder.build(
            liveHours = listOf(live(-1, cover = 70)),
            priorForecast = emptyMap(),
            retroActual = emptyMap(),
            nowMs = now,
        )

        val point = points.single()
        assertTrue(point.forecastBands.isEmpty)
        assertTrue(point.actualBands.isEmpty)
        assertFalse(point.isFrozenBands)
    }
}
