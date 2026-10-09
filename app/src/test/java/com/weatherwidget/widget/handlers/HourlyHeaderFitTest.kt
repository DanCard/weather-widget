package com.weatherwidget.widget.handlers

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HourlyHeaderFitTest {

    // Plain px with a nominal 40 px zone, 28 px floor, 4 zones.
    private fun input(available: Float, scalable: Float = 100f, fixed: Float = 20f, minZone: Float = 28f) =
        HourlyHeaderFit.Input(
            availablePx = available,
            scalablePx = scalable,
            fixedPx = fixed,
            zoneCount = 4,
            nominalZonePx = 40f,
            minZonePx = minZone,
        )

    @Test
    fun `everything fits at nominal - nothing changes`() {
        val plan = HourlyHeaderFit.plan(input(available = 300f))

        assertEquals(HourlyHeaderFit.Plan(40f, 1f, fits = true), plan)
    }

    @Test
    fun `zones compress before the temperature shrinks`() {
        // 100 + 20 + 4 * 34 = 256
        val plan = HourlyHeaderFit.plan(input(available = 256f))

        assertEquals(34f, plan.zoneWidthPx, 0.001f)
        assertEquals(1f, plan.textScale, 0.0f)
        assertTrue(plan.fits)
    }

    @Test
    fun `temperature shrinks only once zones are at their floor`() {
        // Zones at 28: 20 + 112 = 132 fixed; 90 left for the 100 px icon + temperature.
        val plan = HourlyHeaderFit.plan(input(available = 222f))

        assertEquals(28f, plan.zoneWidthPx, 0.0f)
        assertEquals(0.9f, plan.textScale, 0.001f)
        assertTrue(plan.fits)
    }

    @Test
    fun `below both floors it does not fit - the caller drops something`() {
        // Needs 20 + 112 + 80 = 212 at the floors.
        val plan = HourlyHeaderFit.plan(input(available = 211f))

        assertFalse(plan.fits)
        assertEquals(28f, plan.zoneWidthPx, 0.0f)
        assertEquals(HourlyHeaderFit.MIN_TEXT_SCALE, plan.textScale, 0.0f)
    }

    @Test
    fun `zones that cannot be resized go straight to shrinking text`() {
        // Below API 31 the floor is the nominal width.
        val plan = HourlyHeaderFit.plan(input(available = 270f, minZone = 40f))

        assertEquals(40f, plan.zoneWidthPx, 0.0f)
        assertEquals(0.9f, plan.textScale, 0.001f)
        assertTrue(plan.fits)
    }
}
