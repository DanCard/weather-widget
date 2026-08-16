package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the shared zoom-stage table consumed by both Android (discrete widget zoom) and desktop
 * (snap-the-click-to-a-stage). Covers the cycle order under both values of the 2-day setting, span
 * math, the nearest-stage lookup, resolve coercion, and the declaration order Android persists by
 * ordinal.
 */
@Category(ShortDuration::class)
class ZoomStageTest {

    @Test
    fun `cycle order with the 2-day setting off is a two-stop toggle`() {
        assertEquals(ZoomStage.NARROW, ZoomStage.WIDE.next())
        assertEquals(ZoomStage.WIDE, ZoomStage.NARROW.next())
        assertEquals(ZoomStage.WIDE, ZoomStage.TWO_DAY.next())
    }

    @Test
    fun `cycle order with the 2-day setting on is WIDE NARROW TWO_DAY and wraps`() {
        assertEquals(ZoomStage.NARROW, ZoomStage.WIDE.next(multiDayEnabled = true))
        assertEquals(ZoomStage.TWO_DAY, ZoomStage.NARROW.next(multiDayEnabled = true))
        assertEquals(ZoomStage.WIDE, ZoomStage.TWO_DAY.next(multiDayEnabled = true))
    }

    @Test
    fun `resolve coerces a stranded TWO_DAY back to WIDE when the setting is off`() {
        assertEquals(ZoomStage.WIDE, ZoomStage.resolve(ZoomStage.TWO_DAY, multiDayEnabled = false))
        assertEquals(ZoomStage.TWO_DAY, ZoomStage.resolve(ZoomStage.TWO_DAY, multiDayEnabled = true))
        assertEquals(ZoomStage.NARROW, ZoomStage.resolve(ZoomStage.NARROW, multiDayEnabled = false))
        assertEquals(ZoomStage.WIDE, ZoomStage.resolve(ZoomStage.WIDE, multiDayEnabled = false))
    }

    @Test
    fun `total span is back plus forward`() {
        assertEquals(18L, ZoomStage.WIDE.window().totalSpanHours)
        assertEquals(5L, ZoomStage.NARROW.window().totalSpanHours)
        assertEquals(48L, ZoomStage.TWO_DAY.window().totalSpanHours)
    }

    @Test
    fun `nearestByTotalSpan snaps to the closest stage`() {
        // Exact span hits (NARROW at its default 5h span).
        assertEquals(ZoomStage.NARROW, ZoomStage.nearestByTotalSpan(5))
        assertEquals(ZoomStage.WIDE, ZoomStage.nearestByTotalSpan(24))
        assertEquals(ZoomStage.TWO_DAY, ZoomStage.nearestByTotalSpan(48))
        // Off-stage spans pick the nearest: 30 is closest to WIDE(18); 60 closest to TWO_DAY(48).
        assertEquals(ZoomStage.WIDE, ZoomStage.nearestByTotalSpan(30))
        assertEquals(ZoomStage.TWO_DAY, ZoomStage.nearestByTotalSpan(60))
        // Extremes clamp to the end stages.
        assertEquals(ZoomStage.NARROW, ZoomStage.nearestByTotalSpan(0))
        assertEquals(ZoomStage.TWO_DAY, ZoomStage.nearestByTotalSpan(1000))
    }

    @Test
    fun `nearestByTotalSpan follows the configured narrow span`() {
        // A 12h view sits between NARROW and WIDE(18). At a 4h narrow span WIDE is nearer (6 vs
        // 8 away); widen NARROW to 8h and the gap flips (4 vs 6), so the same on-screen span now
        // snaps to a different stage. Desktop must therefore pass its configured span in, or a
        // click can cycle from a stage the user isn't looking at.
        assertEquals(ZoomStage.WIDE, ZoomStage.nearestByTotalSpan(12, narrowSpanHours = 4))
        assertEquals(ZoomStage.NARROW, ZoomStage.nearestByTotalSpan(12, narrowSpanHours = 8))
    }

    @Test
    fun `default stage is WIDE`() {
        assertEquals(ZoomStage.WIDE, ZoomStage.DEFAULT)
    }

    @Test
    fun `declaration order is load-bearing for ordinal persistence`() {
        // Android stores the selected stage by ordinal; reordering would silently remap saved state.
        assertEquals(0, ZoomStage.WIDE.ordinal)
        assertEquals(1, ZoomStage.NARROW.ordinal)
        assertEquals(2, ZoomStage.TWO_DAY.ordinal)
    }

    @Test
    fun `stage parameters match the historical Android values`() {
        // The pre-setting NARROW geometry is still reachable by asking for a 4h span; the rest of
        // the per-span table lives in ZoomWindowTest.
        val narrow = ZoomStage.NARROW.window(4)
        assertEquals(2L, narrow.backHours)
        assertEquals(2L, narrow.forwardHours)
        assertEquals(1, narrow.navJump)
    }

    @Test
    fun `two day window is 42 back 6 forward`() {
        val twoDay = ZoomStage.TWO_DAY.window()
        assertEquals(42L, twoDay.backHours)
        assertEquals(6L, twoDay.forwardHours)
        assertEquals(48L, twoDay.totalSpanHours)
        assertEquals(8, twoDay.navJump)
        assertEquals(6, twoDay.labelInterval)
        assertEquals(3, twoDay.smoothIterations)
    }
}
