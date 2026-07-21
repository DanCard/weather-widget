package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import java.time.LocalDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class HeaderDeltaGateTest {

    private val now = LocalDateTime.of(2026, 7, 3, 14, 0)

    @Test
    fun visibleWhenNowInsideWindow() {
        assertTrue(HeaderDeltaGate.isVisible(windowEndTime = now.plusHours(2), now = now, appliedDelta = -2.1f))
    }

    @Test
    fun visibleWhenScrolledIntoFuture() {
        assertTrue(HeaderDeltaGate.isVisible(windowEndTime = now.plusHours(48), now = now, appliedDelta = -2.1f))
    }

    @Test
    fun hiddenWhenWindowEntirelyInPast() {
        assertFalse(HeaderDeltaGate.isVisible(windowEndTime = now.minusHours(3), now = now, appliedDelta = -2.1f))
    }

    @Test
    fun visibleWhenWindowEndsExactlyAtNow() {
        assertTrue(HeaderDeltaGate.isVisible(windowEndTime = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS), now = now, appliedDelta = -2.1f))
    }

    @Test
    fun hiddenWhenDeltaNull() {
        assertFalse(HeaderDeltaGate.isVisible(windowEndTime = now.plusHours(2), now = now, appliedDelta = null))
    }

    @Test
    fun hiddenWhenDeltaBelowThreshold() {
        assertFalse(HeaderDeltaGate.isVisible(windowEndTime = now.plusHours(2), now = now, appliedDelta = 0.05f))
    }
}
