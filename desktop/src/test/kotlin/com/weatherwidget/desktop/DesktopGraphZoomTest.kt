package com.weatherwidget.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Unit tests for the continuous-zoom span model in [DesktopGraphUtils]. The desktop temperature/
 * precip/cloud graphs derive their whole window (and label/smoothing cadence) from a single
 * `zoomFactor` in [0,1]; these tests pin the endpoints, monotonicity, and clamping.
 */
class DesktopGraphZoomTest {

    @Test
    fun `endpoints map to configured min and max spans`() {
        assertEquals(DesktopGraphUtils.MIN_BACK_HOURS, DesktopGraphUtils.backHoursFor(0f))
        assertEquals(DesktopGraphUtils.MAX_BACK_HOURS, DesktopGraphUtils.backHoursFor(1f))
        assertEquals(DesktopGraphUtils.MIN_FORWARD_HOURS, DesktopGraphUtils.forwardHoursFor(0f))
        assertEquals(DesktopGraphUtils.MAX_FORWARD_HOURS, DesktopGraphUtils.forwardHoursFor(1f))
    }

    @Test
    fun `max zoom-out is 6 days back and 1 day forward`() {
        assertEquals(144, DesktopGraphUtils.backHoursFor(1f))
        assertEquals(24, DesktopGraphUtils.forwardHoursFor(1f))
    }

    @Test
    fun `factor is clamped outside the unit interval`() {
        assertEquals(DesktopGraphUtils.MIN_BACK_HOURS, DesktopGraphUtils.backHoursFor(-2f))
        assertEquals(DesktopGraphUtils.MAX_BACK_HOURS, DesktopGraphUtils.backHoursFor(5f))
    }

    @Test
    fun `back and forward grow monotonically with the factor`() {
        var prevBack = DesktopGraphUtils.backHoursFor(0f)
        var prevForward = DesktopGraphUtils.forwardHoursFor(0f)
        var z = 0.1f
        while (z <= 1f) {
            val back = DesktopGraphUtils.backHoursFor(z)
            val forward = DesktopGraphUtils.forwardHoursFor(z)
            assertTrue("back should be non-decreasing at z=$z", back >= prevBack)
            assertTrue("forward should be non-decreasing at z=$z", forward >= prevForward)
            prevBack = back
            prevForward = forward
            z += 0.1f
        }
    }

    @Test
    fun `view is history-leaning - back outgrows forward at wide zoom`() {
        // At full zoom-out there is far more history than forecast.
        assertTrue(DesktopGraphUtils.backHoursFor(1f) > DesktopGraphUtils.forwardHoursFor(1f) * 2)
    }

    @Test
    fun `label interval always divides 24 and widens with span`() {
        val tight = DesktopGraphUtils.labelIntervalFor(4)
        val wide = DesktopGraphUtils.labelIntervalFor(168)
        assertEquals(0, 24 % DesktopGraphUtils.labelIntervalFor(4))
        assertEquals(0, 24 % DesktopGraphUtils.labelIntervalFor(36))
        assertEquals(0, 24 % DesktopGraphUtils.labelIntervalFor(168))
        assertTrue("wider spans need sparser labels", wide >= tight)
    }

    @Test
    fun `smoothing increases with span`() {
        assertTrue(DesktopGraphUtils.smoothIterationsFor(168) >= DesktopGraphUtils.smoothIterationsFor(4))
    }

    @Test
    fun `legacy zoom strings migrate to a factor`() {
        assertEquals(0f, DesktopGraphUtils.zoomFactorFromLegacy("NARROW"))
        assertEquals(DesktopGraphUtils.DEFAULT_ZOOM_FACTOR, DesktopGraphUtils.zoomFactorFromLegacy("WIDE"))
        assertEquals(DesktopGraphUtils.DEFAULT_ZOOM_FACTOR, DesktopGraphUtils.zoomFactorFromLegacy(null))
    }

    @Test
    fun `pan drag direction and magnitude`() {
        // Drag right (positive px) reveals earlier time -> the hourly offset decreases.
        assertTrue(DesktopGraphUtils.panDeltaHours(100f, 800f, 24) < 0f)
        // A full-width drag pans the whole visible span.
        assertEquals(-24f, DesktopGraphUtils.panDeltaHours(800f, 800f, 24), 0.001f)
        // Zero-width guard (no NaN/division blow-up before the canvas is measured).
        assertEquals(0f, DesktopGraphUtils.panDeltaHours(50f, 0f, 24), 0f)
    }

    @Test
    fun `drag residual is zero at whole hours`() {
        assertEquals(0f, DesktopGraphUtils.dragResidualPx(0f, 40f), 0.001f)
        assertEquals(0f, DesktopGraphUtils.dragResidualPx(3f, 40f), 0.001f)
        assertEquals(0f, DesktopGraphUtils.dragResidualPx(-2f, 40f), 0.001f)
    }

    @Test
    fun `data step plus residual is perfectly linear in drag`() {
        // The on-screen slide = whole-hour data shift (-round(D)*pph) + sub-hour residual. The two
        // sum to exactly -D*pph for every D, which is what makes the drag continuous across hour
        // boundaries at any zoom.
        val pph = 40f
        for (d in listOf(0f, 0.3f, 0.49f, 0.51f, 0.99f, 1.0f, 1.5f, -0.7f, -2.4f)) {
            val dataStepPx = -d.roundToInt() * pph
            val net = dataStepPx + DesktopGraphUtils.dragResidualPx(d, pph)
            assertEquals("net slide at d=$d", -d * pph, net, 0.01f)
        }
    }
}
