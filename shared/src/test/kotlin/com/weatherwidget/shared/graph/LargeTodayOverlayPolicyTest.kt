package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(ShortDuration::class)
class LargeTodayOverlayPolicyTest {
    @Test
    fun `Android ten by four and desktop nine by four each remove one date`() {
        val android = LargeTodayOverlayPolicy.resolve(
            LargeTodayOverlayPolicy.Profile.ANDROID_WIDGET, 10, 4, true, true,
        )
        val desktop = LargeTodayOverlayPolicy.resolve(
            LargeTodayOverlayPolicy.Profile.DESKTOP, 9, 4, true, true,
        )

        assertTrue(android.enabled)
        assertEquals(9, android.displayColumns)
        assertTrue(desktop.enabled)
        assertEquals(8, desktop.displayColumns)
    }

    @Test
    fun `manual desktop history zoom disables detailed Today mode`() {
        val decision = LargeTodayOverlayPolicy.resolve(
            LargeTodayOverlayPolicy.Profile.DESKTOP,
            availableColumns = 9,
            rows = 5,
            useGraph = true,
            todayVisible = true,
            extraHistoryColumns = 1,
        )

        assertFalse(decision.enabled)
        assertEquals(9, decision.displayColumns)
    }
}
