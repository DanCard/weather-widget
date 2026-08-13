package com.weatherwidget.desktop

import com.weatherwidget.shared.graph.HourlyZoomRules
import com.weatherwidget.shared.graph.ZoomStage
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Guards [resnapNarrowZoomAfterSpanChange], the save-path fix for "changing Hourly Zoom hours in
 * Settings doesn't update the already-open desktop view".
 *
 * The desktop hourly graph renders its window from `config.zoomFactor` alone; the
 * `narrowZoomSpanHours` setting only shapes the factor a click stores when it lands on NARROW. So
 * changing the setting while viewing NARROW left the graph on the old span. The helper re-derives
 * `zoomFactor` from the new span, but only when the current factor is nearest to the NARROW stage —
 * WIDE and THREE_DAY must not move when the setting changes.
 */
@Category(ShortDuration::class)
class NarrowSpanResnapTest {

    private fun config(
        narrowZoomSpanHours: Int = HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS,
        zoomFactor: Float = DesktopGraphUtils.DEFAULT_ZOOM_FACTOR,
    ) = DesktopConfig(
        lat = 37.42,
        lon = -122.08,
        label = "Test",
        narrowZoomSpanHours = narrowZoomSpanHours,
        zoomFactor = zoomFactor,
    )

    @Test
    fun `changing the span while viewing NARROW re-snaps the zoom factor`() {
        val oldSpan = 5
        val prev = config(
            narrowZoomSpanHours = oldSpan,
            zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, oldSpan),
        )
        val next = prev.copy(narrowZoomSpanHours = 8)

        val result = resnapNarrowZoomAfterSpanChange(prev, next)

        assertEquals(
            DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, 8),
            result.zoomFactor,
        )
        assertEquals(8, result.narrowZoomSpanHours)
    }

    @Test
    fun `the re-snapped factor renders the new span`() {
        val spans = HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS
        spans.forEach { oldSpan ->
            val oldFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.NARROW, oldSpan)
            spans.filter { it != oldSpan }.forEach { newSpan ->
                val prev = config(narrowZoomSpanHours = oldSpan, zoomFactor = oldFactor)
                val result = resnapNarrowZoomAfterSpanChange(prev, prev.copy(narrowZoomSpanHours = newSpan))
                val back = DesktopGraphUtils.backHoursFor(result.zoomFactor)
                val forward = DesktopGraphUtils.forwardHoursFor(result.zoomFactor)
                assertEquals(
                    "span ${oldSpan}h -> ${newSpan}h must render ${newSpan}h",
                    newSpan,
                    back + forward,
                )
            }
        }
    }

    @Test
    fun `unchanged span returns the same instance`() {
        val prev = config()
        assertSame(prev, resnapNarrowZoomAfterSpanChange(prev, prev))
    }

    @Test
    fun `changing the span while viewing WIDE leaves the zoom factor untouched`() {
        val prev = config(zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.WIDE))
        val next = prev.copy(narrowZoomSpanHours = 8)

        val result = resnapNarrowZoomAfterSpanChange(prev, next)

        assertEquals(prev.zoomFactor, result.zoomFactor)
        assertEquals(8, result.narrowZoomSpanHours)
    }

    @Test
    fun `changing the span while viewing THREE_DAY leaves the zoom factor untouched`() {
        val prev = config(zoomFactor = DesktopGraphUtils.zoomFactorForStage(ZoomStage.THREE_DAY))
        val next = prev.copy(narrowZoomSpanHours = 8)

        val result = resnapNarrowZoomAfterSpanChange(prev, next)

        assertEquals(prev.zoomFactor, result.zoomFactor)
        assertEquals(8, result.narrowZoomSpanHours)
    }

    @Test
    fun `a wheel-zoom position nearest to NARROW also re-snaps`() {
        // A continuous position that is not exactly the old NARROW factor but whose nearest stage is
        // NARROW (span 6 is closest to 5, far from WIDE's 24). The click cycle treats this as NARROW,
        // so the setting change must re-snap it too.
        val nearNarrowFactor = (0..1000)
            .map { it / 1000f }
            .first { z ->
                DesktopGraphUtils.backHoursFor(z) + DesktopGraphUtils.forwardHoursFor(z) == 6
            }
        val prev = config(narrowZoomSpanHours = 5, zoomFactor = nearNarrowFactor)
        val next = prev.copy(narrowZoomSpanHours = 7)

        val result = resnapNarrowZoomAfterSpanChange(prev, next)

        val back = DesktopGraphUtils.backHoursFor(result.zoomFactor)
        val forward = DesktopGraphUtils.forwardHoursFor(result.zoomFactor)
        assertEquals(7, back + forward)
    }
}
