package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.ShortDuration
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/**
 * The write side of the cloud actual series — the leg Android never had.
 */
@Category(ShortDuration::class)
class RetroCloudActualTest {

    private val hour = 3_600_000L
    private val now = 1_755_720_000_000L

    private fun hourly(offsetHours: Long, low: Int?, total: Int? = 100) = HourlyForecast(
        dateTime = now + offsetHours * hour,
        temperature = 60f,
        condition = "Cloudy",
        cloudCover = total,
        cloudCoverLow = low,
        source = "OPEN_METEO",
    )

    @Test
    fun `settled hours qualify as actuals`() {
        val actuals = RetroCloudActual.qualifyingActuals(
            listOf(hourly(-5, 8), hourly(-4, 13), hourly(-3, 11)),
            now,
        )

        assertEquals(
            mapOf(now - 5 * hour to 8, now - 4 * hour to 13, now - 3 * hour to 11),
            actuals,
        )
    }

    /**
     * The measured regression. The 20:00 hour ended at 21:00, but a fetch at 21:37 still returned
     * the pre-hour forecast (6%) while 21:39 onward returned the corrected 86% that the surface
     * stations backed. Filing at hour-end+0 publishes a forecast as the actual.
     */
    @Test
    fun `an hour that has ended but not settled does not qualify`() {
        val target = now - hour // ended exactly at `now`
        val hourly = listOf(hourly(-1, 6))

        assertTrue(
            "hour has ended",
            CloudSeriesBuilder.isRetroCorrected(target, now),
        )
        assertTrue(
            "but must not be filed yet: $target",
            RetroCloudActual.qualifyingActuals(hourly, now).isEmpty(),
        )
        assertEquals(
            "and qualifies once the settling lag has passed",
            mapOf(target to 6),
            RetroCloudActual.qualifyingActuals(hourly, now + RetroCloudActual.SETTLE_MS),
        )
    }

    /** The settling lag must clear the ~38 minutes actually observed, with headroom. */
    @Test
    fun `the settling lag exceeds the measured correction delay`() {
        assertTrue(
            "SETTLE_MS=${RetroCloudActual.SETTLE_MS} must exceed the observed ~38min lag",
            RetroCloudActual.SETTLE_MS > 40 * 60_000L,
        )
    }

    /** The hour containing now has not settled, so it has no actual yet. */
    @Test
    fun `the in-progress hour and the future do not qualify`() {
        val actuals = RetroCloudActual.qualifyingActuals(
            listOf(hourly(0, 40), hourly(1, 50), hourly(4, 60)),
            now + 30 * 60_000L,
        )

        assertTrue("nothing has settled yet: $actuals", actuals.isEmpty())
    }

    /** Exactly one hour after the hour start is the boundary: the hour has just ended. */
    @Test
    fun `the boundary is the hour end plus the settling lag`() {
        val target = now - hour
        val settledAt = target + hour + RetroCloudActual.SETTLE_MS
        assertFalse(RetroCloudActual.qualifyingActuals(listOf(hourly(-1, 20)), settledAt - 1).containsKey(target))
        assertTrue(RetroCloudActual.qualifyingActuals(listOf(hourly(-1, 20)), settledAt).containsKey(target))
    }

    /**
     * The whole point of the variable switch. On 2026-08-20 the total column read 83-99% all
     * afternoon on thin cirrus while every surface station reported clear; the low layer read 8-13%.
     * The actual series must carry the low layer, or it draws an overcast nobody saw.
     */
    @Test
    fun `actuals carry the low layer, not the total column`() {
        val actuals = RetroCloudActual.qualifyingActuals(
            listOf(hourly(-4, low = 13, total = 99)),
            now,
        )

        assertEquals(13, actuals[now - 4 * hour])
    }

    /** A missing low value must stay missing rather than paint a clear sky nobody observed. */
    @Test
    fun `hours without a low value are omitted, never zeroed`() {
        val actuals = RetroCloudActual.qualifyingActuals(
            listOf(hourly(-4, low = null, total = 99), hourly(-3, low = 20)),
            now,
        )

        assertEquals(mapOf(now - 3 * hour to 20), actuals)
    }

    @Test
    fun `out-of-range values are clamped`() {
        val actuals = RetroCloudActual.qualifyingActuals(
            listOf(hourly(-4, low = 140), hourly(-3, low = -5)),
            now,
        )

        assertEquals(100, actuals[now - 4 * hour])
        assertEquals(0, actuals[now - 3 * hour])
    }

    /** Deterministic bucketing, so refetching an hour REPLACEs in place instead of accumulating. */
    @Test
    fun `the bucket is the hour itself`() {
        assertEquals(now - 5 * hour, RetroCloudActual.bucketFor(now - 5 * hour))
    }

    /** The two synthetic series must never collide in the shared table. */
    @Test
    fun `the actual and forecast series use distinct source ids`() {
        assertTrue(RetroCloudActual.SOURCE_ID != PriorDayCloudForecast.SOURCE_ID)
    }
}
