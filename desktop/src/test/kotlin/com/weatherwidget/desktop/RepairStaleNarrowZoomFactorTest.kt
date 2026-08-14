package com.weatherwidget.desktop

import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Guards [repairStaleNarrowZoomFactor], the load-time heal for the config corruption that made
 * Settings say 6 h while the narrow view drew 4 h (`zoomFactor: 0.0` left behind when the span was
 * changed from 4 to 6).
 *
 * The repair must be surgical: it only snaps factors that are *exactly* a NARROW-stage factor for a
 * span that no longer matches the configured one. Arbitrary continuous wheel-zoom positions near the
 * narrow band must survive a restart untouched.
 */
@Category(ShortDuration::class)
class RepairStaleNarrowZoomFactorTest {

    private fun config(
        narrowZoomSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS,
        zoomFactor: Float = DesktopGraphUtils.DEFAULT_ZOOM_FACTOR,
    ) = DesktopConfig(
lat = 37.42,
lon = -122.08,
label = "Test",
zoomFactor = zoomFactor,
settings = DesktopSettings(narrowZoomSpanHours = narrowZoomSpanHours),
)

    private fun renderedTotal(zoomFactor: Float): Int =
        DesktopGraphUtils.backHoursFor(zoomFactor) + DesktopGraphUtils.forwardHoursFor(zoomFactor)

    @Test
    fun `the reported corruption is healed`() {
        // zoomFactor 0.0 is the NARROW factor for a 4 h span; the setting was changed to 6 h.
        val stale = config(narrowZoomSpanHours = 6, zoomFactor = 0.0f)

        val healed = repairStaleNarrowZoomFactor(stale)

        assertEquals(6, renderedTotal(healed.zoomFactor))
        assertEquals(6, healed.settings.narrowZoomSpanHours)
    }

    @Test
    fun `every stale narrow factor is re-snapped to the configured span`() {
        val spans = HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS
        spans.forEach { oldSpan ->
            spans.filter { it != oldSpan }.forEach { newSpan ->
                val stale = config(
                    narrowZoomSpanHours = newSpan,
                    zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, oldSpan),
                )
                val healed = repairStaleNarrowZoomFactor(stale)
                assertEquals(
                    "stale ${oldSpan}h factor with ${newSpan}h setting must render ${newSpan}h",
                    newSpan,
                    renderedTotal(healed.zoomFactor),
                )
            }
        }
    }

    @Test
    fun `a consistent narrow config is returned unchanged`() {
        val span = 6
        val consistent = config(
            narrowZoomSpanHours = span,
            zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, span),
        )
        assertSame(consistent, repairStaleNarrowZoomFactor(consistent))
    }

    @Test
    fun `a continuous zoom position rendering a narrow-sized span is left untouched`() {
        // 0.052 renders 3 back + 3 forward = 6 h, but it is NOT the NARROW factor for 6 (that is
        // 0.051, the lowest factor on the 6 h plateau). A user wheel-zoomed here must not be snapped.
        val wheelPosition = 0.052f
        val configured = 8
        val continuous = config(narrowZoomSpanHours = configured, zoomFactor = wheelPosition)

        val result = repairStaleNarrowZoomFactor(continuous)

        assertEquals(wheelPosition, result.zoomFactor)
        assertEquals(6, renderedTotal(wheelPosition))
    }

    @Test
    fun `WIDE and THREE_DAY factors are left untouched`() {
        listOf(ZoomStage.WIDE, ZoomStage.THREE_DAY).forEach { stage ->
            val c = config(
                narrowZoomSpanHours = 8,
                zoomFactor = DesktopGraphUtils.zoomFactorForStage(stage),
            )
            val result = repairStaleNarrowZoomFactor(c)
            assertEquals(c.zoomFactor, result.zoomFactor)
        }
    }
}
