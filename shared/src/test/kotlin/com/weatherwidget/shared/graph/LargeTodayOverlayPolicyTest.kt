package com.weatherwidget.shared.graph

import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
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
    fun `small Android widget enables the overlay once Today keeps a full day column`() {
        val decision = LargeTodayOverlayPolicy.resolve(
            LargeTodayOverlayPolicy.Profile.ANDROID_WIDGET,
            availableColumns = 5,
            rows = 4,
            useGraph = true,
            todayVisible = true,
        )

        assertTrue(decision.enabled)
        assertEquals(4, decision.displayColumns)
    }

    @Test
    fun `sub-three-column Android widget keeps the overlay off`() {
        val decision = LargeTodayOverlayPolicy.resolve(
            LargeTodayOverlayPolicy.Profile.ANDROID_WIDGET,
            availableColumns = 2,
            rows = 4,
            useGraph = true,
            todayVisible = true,
        )

        assertEquals(false, decision.enabled)
        assertEquals(2, decision.displayColumns)
    }

    @Test
    fun `desktop Today overlay stays enabled with extra history columns`() {
        val decision = LargeTodayOverlayPolicy.resolve(
            LargeTodayOverlayPolicy.Profile.DESKTOP,
            availableColumns = 9,
            rows = 5,
            useGraph = true,
            todayVisible = true,
        )

        assertTrue(decision.enabled)
        assertEquals(8, decision.displayColumns)
    }
}
