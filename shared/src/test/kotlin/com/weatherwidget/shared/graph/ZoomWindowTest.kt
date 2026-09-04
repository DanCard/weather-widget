package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the user-configurable NARROW span table: how a chosen span (4–8h) splits into back/forward
 * hours and what the nav arrows step at that span. Consumed by both the Android widget's discrete
 * zoom stage and desktop's click-snap, so a change here moves both platforms.
 */
@Category(ShortDuration::class)
class ZoomWindowTest {

    /** span -> (backHours, forwardHours, navJump). Back-heavy on odd spans. */
    private val expectedNarrow = mapOf(
        4 to Triple(2L, 2L, 1),
        5 to Triple(3L, 2L, 1),
        6 to Triple(3L, 3L, 1),
        7 to Triple(4L, 3L, 1),
        8 to Triple(4L, 4L, 1),
    )

    @Test
    fun `narrow span splits back-heavy and sets the nav jump`() {
        for ((span, expected) in expectedNarrow) {
            val window = ZoomStage.NARROW.window(span)
            val (back, forward, navJump) = expected
            assertEquals("backHours at span=$span", back, window.backHours)
            assertEquals("forwardHours at span=$span", forward, window.forwardHours)
            assertEquals("navJump at span=$span", navJump, window.navJump)
            assertEquals("totalSpanHours at span=$span", span.toLong(), window.totalSpanHours)
        }
    }

    @Test
    fun `narrow keeps per-hour labels and light smoothing at every span`() {
        for (span in HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS) {
            val window = ZoomStage.NARROW.window(span)
            assertEquals("labelInterval at span=$span", 1, window.labelInterval)
            assertEquals("smoothIterations at span=$span", 1, window.smoothIterations)
            assertEquals(ZoomStage.NARROW, window.stage)
        }
    }

    @Test
    fun `out of range spans clamp into 4 to 8`() {
        assertEquals(ZoomStage.NARROW.window(4), ZoomStage.NARROW.window(3))
        assertEquals(ZoomStage.NARROW.window(4), ZoomStage.NARROW.window(0))
        assertEquals(ZoomStage.NARROW.window(8), ZoomStage.NARROW.window(9))
        assertEquals(ZoomStage.NARROW.window(8), ZoomStage.NARROW.window(240))
        assertEquals(4, HourlyZoomRules.clampNarrowSpan(-1))
        assertEquals(8, HourlyZoomRules.clampNarrowSpan(100))
    }

    @Test
    fun `default span is 5 hours`() {
        assertEquals(5, HourlyZoomRules.DEFAULT_NARROW_SPAN_HOURS)
        val default = ZoomStage.NARROW.window()
        assertEquals(3L, default.backHours)
        assertEquals(2L, default.forwardHours)
        assertEquals(1, default.navJump)
    }

    @Test
    fun `wide and two day are unaffected by the narrow span setting`() {
        for (span in HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS) {
            val wide = ZoomStage.WIDE.window(span)
            // 18h, symmetric: 9h of history against 9h of forecast.
            assertEquals(9L, wide.backHours)
            assertEquals(9L, wide.forwardHours)
            assertEquals(18L, wide.totalSpanHours)
            assertEquals(3, wide.navJump)
            assertEquals(4, wide.labelInterval)
            assertEquals(3, wide.smoothIterations)

            val twoDay = ZoomStage.TWO_DAY.window(span)
            assertEquals(36L, twoDay.backHours)
            assertEquals(12L, twoDay.forwardHours)
            assertEquals(8, twoDay.navJump)
            assertEquals(6, twoDay.labelInterval)
            assertEquals(3, twoDay.smoothIterations)
        }
    }

    @Test
    fun `nav jump is a sixth of a span above the narrow range`() {
        // Desktop's continuous zoom reaches spans the NARROW setting never produces; those step a
        // sixth of the span. Spans up to 8h step 1h, and so does anything under 12h once the
        // fraction is applied. The two fixed stages fall out of the same rule: 18h->3h, 48h->8h.
        assertEquals(1, HourlyZoomRules.navJumpHours(8))
        assertEquals(1, HourlyZoomRules.navJumpHours(9))
        assertEquals(3, HourlyZoomRules.navJumpHours(18))
        assertEquals(4, HourlyZoomRules.navJumpHours(24))
        assertEquals(8, HourlyZoomRules.navJumpHours(48))
    }

    @Test
    fun `narrow widgets thin the tight-view footer labels once the span passes 6h`() {
        // A narrow (2-3 column) widget budgets ~4 footer labels: WIDE thins its span to every 6h there.
        // Labelling every hour of a widened tight view would draw up to 8 and crowd them.
        assertEquals(1, HourlyZoomRules.narrowWidgetLabelInterval(4))
        assertEquals(1, HourlyZoomRules.narrowWidgetLabelInterval(5))
        assertEquals(2, HourlyZoomRules.narrowWidgetLabelInterval(6))
        assertEquals(2, HourlyZoomRules.narrowWidgetLabelInterval(8))
        // Every span stays within the budget WIDE already set for these widgets.
        for (span in HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS) {
            val labels = span / HourlyZoomRules.narrowWidgetLabelInterval(span)
            assertEquals("span=$span must fit the narrow-widget label budget", true, labels <= 5)
        }
    }

    @Test
    fun `nav jump never returns zero`() {
        for (span in 0..3) {
            assertEquals("span=$span", 1, HourlyZoomRules.navJumpHours(span))
        }
    }

    @Test
    fun `compact toString keeps zoom logs one token`() {
        assertEquals("NARROW(-3/+2 jump=1)", ZoomStage.NARROW.window(5).toString())
        assertEquals("WIDE(-9/+9 jump=3)", ZoomStage.WIDE.window().toString())
    }

    @Test
    fun `date footer mode turns on at exactly 48 hours`() {
        assertFalse(HourlyZoomRules.isDateMode(47L))
        assertTrue(HourlyZoomRules.isDateMode(48L))
        assertTrue(HourlyZoomRules.isDateMode(49L))
    }
}
