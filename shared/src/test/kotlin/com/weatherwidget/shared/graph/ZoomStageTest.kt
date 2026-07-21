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
        assertEquals(24L, ZoomStage.WIDE.totalSpanHours)
        assertEquals(4L, ZoomStage.NARROW.totalSpanHours)
        assertEquals(72L, ZoomStage.THREE_DAY.totalSpanHours)
    }

    @Test
    fun `nearestByTotalSpan snaps to the closest stage`() {
        // Exact span hits.
        assertEquals(ZoomStage.NARROW, ZoomStage.nearestByTotalSpan(4))
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
        assertEquals(12L, ZoomStage.WIDE.backHours)
        assertEquals(12L, ZoomStage.WIDE.forwardHours)
        assertEquals(6, ZoomStage.WIDE.navJump)
        assertEquals(4, ZoomStage.WIDE.labelInterval)
        assertEquals(3, ZoomStage.WIDE.smoothIterations)

        assertEquals(2L, ZoomStage.NARROW.backHours)
        assertEquals(2L, ZoomStage.NARROW.forwardHours)
        assertEquals(1, ZoomStage.NARROW.navJump)
        assertEquals(1, ZoomStage.NARROW.labelInterval)
        assertEquals(1, ZoomStage.NARROW.smoothIterations)

        assertEquals(48L, ZoomStage.THREE_DAY.backHours)
        assertEquals(24L, ZoomStage.THREE_DAY.forwardHours)
        assertEquals(12, ZoomStage.THREE_DAY.navJump)
        assertEquals(12, ZoomStage.THREE_DAY.labelInterval)
        assertEquals(3, ZoomStage.THREE_DAY.smoothIterations)
    }
}
