package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** The hour a tap zooms on, both platforms ([HourlyZoomRules.tapOffsetHours], [DrawnTimeAxis]; audit 261021 J). */
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
    fun `DrawnTimeAxis maps truncated horizon fraction onto drawn hour not window afternoon`() {
        val zone = ZoneId.of("America/Phoenix")
        val now = LocalDateTime.of(2026, 11, 24, 14, 0)
        val nowMs = now.atZone(zone).toInstant().toEpochMilli()
        val sun03 = LocalDateTime.of(2026, 11, 29, 3, 0) // +109h
        val sun11 = LocalDateTime.of(2026, 11, 29, 11, 0) // +117h (OWM 5-day horizon)
        // WIDE window around Sun noon (offset +118): 03:00..21:00 (18h), but drawn points are 03:00..11:00 (8h).
        val axis = DrawnTimeAxis.resolve(sun03, sun11, hourlyOffset = 118, zoom = ZoomStage.WIDE.window(), now = now, zoneId = zone)
        assertEquals(8, axis.panSpanHours)
        // 9 AM is 6h past 3 AM = 0.75 of the drawn width -> offset +115 (Sun 09:00), not +123 (Sun 17:00).
        assertEquals(115, axis.tapOffsetHours(0.75, nowMs))
    }

    @Test
    fun `DrawnTimeAxis falls back to nominal window when fewer than two distinct points`() {
        val start = 1_000_000L
        val end = 9_000_000L
        assertEquals(DrawnTimeAxis(start, end), DrawnTimeAxis.resolve(null, null, start, end))
        assertEquals(DrawnTimeAxis(start, end), DrawnTimeAxis.resolve(2_000_000L, 2_000_000L, start, end))
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
