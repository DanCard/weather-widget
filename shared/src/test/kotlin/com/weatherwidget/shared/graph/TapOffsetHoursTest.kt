package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import kotlin.math.roundToInt

/** The hour a tap zooms on, both platforms ([HourlyZoomRules.tapOffsetHours]; audit 261021 J). */
@Category(ShortDuration::class)
class TapOffsetHoursTest {
    @Test
    fun `the hour at a fraction of the window`() {
        // Window now-6h .. now+6h: left edge, centre, right edge, and half up at a half hour.
        assertEquals(-6, HourlyZoomRules.tapOffsetHours(0.0, -6.0, 12.0))
        assertEquals(0, HourlyZoomRules.tapOffsetHours(0.5, -6.0, 12.0))
        assertEquals(6, HourlyZoomRules.tapOffsetHours(1.0, -6.0, 12.0))
        assertEquals(-4, HourlyZoomRules.tapOffsetHours(0.125, -6.0, 12.0))
    }

    @Test
    fun `Android's 13 zones land where the old zone formula put them`() {
        // The formula HourlyTouchZoneMapper used before it called the shared rule.
        fun old(zone: Int, offset: Int, zoom: ZoomWindow): Int {
            val perZone = (zoom.backHours + zoom.forwardHours) / 12f
            val asymmetry = (zoom.forwardHours - zoom.backHours) / 2f
            return offset + (perZone * (zone - 6) + asymmetry).roundToInt()
        }
        for (narrow in HourlyZoomRules.MIN_NARROW_SPAN_HOURS..HourlyZoomRules.MAX_NARROW_SPAN_HOURS) {
            for (stage in ZoomStage.entries) {
                val zoom = stage.window(narrow)
                for (offset in -720..720 step 7) {
                    for (zone in 0..12) {
                        val shared = HourlyZoomRules.tapOffsetHours(
                            zone / 12.0,
                            (offset - zoom.backHours).toDouble(),
                            (zoom.backHours + zoom.forwardHours).toDouble(),
                        )
                        assertEquals("$zoom offset=$offset zone=$zone", old(zone, offset, zoom), shared)
                    }
                }
            }
        }
    }
}
