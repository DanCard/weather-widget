package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Pins the shared zoom-stage table consumed by both Android (discrete widget zoom) and desktop
 * (snap-the-click-to-a-stage). Covers the cycle order, span math, the nearest-stage lookup, and the
 * declaration order that Android persists by ordinal.
 */
@Category(ShortDuration::class)
class ZoomStageTest {

    @Test
    fun `cycle order is WIDE NARROW THREE_DAY and wraps`() {
        assertEquals(ZoomStage.NARROW, ZoomStage.WIDE.next())
        assertEquals(ZoomStage.THREE_DAY, ZoomStage.NARROW.next())
        assertEquals(ZoomStage.WIDE, ZoomStage.THREE_DAY.next())
    }

    @Test
    fun `total span is back plus forward`() {
        assertEquals(18L, ZoomStage.WIDE.window().totalSpanHours)
        assertEquals(5L, ZoomStage.NARROW.window().totalSpanHours)
        assertEquals(72L, ZoomStage.THREE_DAY.window().totalSpanHours)
    }

    @Test
    fun `nearestByTotalSpan snaps to the closest stage`() {
        // Exact span hits (NARROW at its default 5h span).
        assertEquals(ZoomStage.NARROW, ZoomStage.nearestByTotalSpan(5))
        assertEquals(ZoomStage.WIDE, ZoomStage.nearestByTotalSpan(24))
        assertEquals(ZoomStage.THREE_DAY, ZoomStage.nearestByTotalSpan(72))
        // Off-stage spans pick the nearest: 30 is closest to WIDE(24); 60 closest to THREE_DAY(72).
        assertEquals(ZoomStage.WIDE, ZoomStage.nearestByTotalSpan(30))
        assertEquals(ZoomStage.THREE_DAY, ZoomStage.nearestByTotalSpan(60))
        // Extremes clamp to the end stages.
        assertEquals(ZoomStage.NARROW, ZoomStage.nearestByTotalSpan(0))
        assertEquals(ZoomStage.THREE_DAY, ZoomStage.nearestByTotalSpan(1000))
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
        assertEquals(2, ZoomStage.THREE_DAY.ordinal)
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
}
