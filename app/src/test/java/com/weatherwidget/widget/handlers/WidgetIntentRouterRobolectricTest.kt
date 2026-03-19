package com.weatherwidget.widget.handlers

import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.ZoomLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WidgetIntentRouterRobolectricTest {

    @Test
    fun `router action constants match provider action constants`() {
        assertEquals(WeatherWidgetProvider.ACTION_NAV_LEFT, WidgetIntentRouter.ACTION_NAV_LEFT)
        assertEquals(WeatherWidgetProvider.ACTION_NAV_RIGHT, WidgetIntentRouter.ACTION_NAV_RIGHT)
        assertEquals(WeatherWidgetProvider.ACTION_TOGGLE_API, WidgetIntentRouter.ACTION_TOGGLE_API)
        assertEquals(WeatherWidgetProvider.ACTION_TOGGLE_VIEW, WidgetIntentRouter.ACTION_TOGGLE_VIEW)
        assertEquals(WeatherWidgetProvider.ACTION_TOGGLE_PRECIP, WidgetIntentRouter.ACTION_TOGGLE_PRECIP)
        assertEquals(WeatherWidgetProvider.ACTION_CYCLE_ZOOM, WidgetIntentRouter.ACTION_CYCLE_ZOOM)
        assertEquals(WeatherWidgetProvider.ACTION_SET_VIEW, WidgetIntentRouter.ACTION_SET_VIEW)
    }

    @Test
    fun `router set-view extra key matches provider contract`() {
        assertEquals(WeatherWidgetProvider.EXTRA_TARGET_VIEW, WidgetIntentRouter.EXTRA_TARGET_VIEW)
    }

    @Test
    fun `buildRefreshScheduleDecision uses replace for manual refresh`() {
        val now = System.currentTimeMillis()

        val decision = WidgetIntentRouter.buildRefreshScheduleDecision(
            latestFetchedAt = now - 5 * 60 * 60 * 1000L,
            nowMs = now,
            reason = "manual_refresh",
            lastEnqueueForReasonMs = now - 1_000L,
        )

        assertTrue(decision.shouldEnqueue)
        assertEquals(androidx.work.ExistingWorkPolicy.REPLACE, decision.policy)
        assertEquals("manual_refresh", decision.reason)
        assertNull(decision.skipReason)
    }

    @Test
    fun `buildRefreshScheduleDecision uses keep for stale toggle refresh`() {
        val now = System.currentTimeMillis()

        val decision = WidgetIntentRouter.buildRefreshScheduleDecision(
            latestFetchedAt = now - 5 * 60 * 60 * 1000L,
            nowMs = now,
            reason = "stale_on_toggle_view",
            lastEnqueueForReasonMs = null,
        )

        assertTrue(decision.shouldEnqueue)
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, decision.policy)
        assertEquals("stale_on_toggle_view", decision.reason)
        assertNull(decision.skipReason)
    }

    @Test
    fun `buildRefreshScheduleDecision debounces repeated stale refreshes`() {
        val now = System.currentTimeMillis()

        val decision = WidgetIntentRouter.buildRefreshScheduleDecision(
            latestFetchedAt = now - 5 * 60 * 60 * 1000L,
            nowMs = now,
            reason = "stale_on_toggle_view",
            lastEnqueueForReasonMs = now - 5_000L,
        )

        assertFalse(decision.shouldEnqueue)
        assertEquals(androidx.work.ExistingWorkPolicy.KEEP, decision.policy)
        assertEquals("debounced", decision.skipReason)
    }

    @Test
    fun `buildGraphQueryWindow splits into center and now windows when far apart`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 12)
        val centerTime = now.plusDays(7).withHour(8).withMinute(20)

        val window = WidgetIntentRouter.buildGraphQueryWindow(centerTime, ZoomLevel.WIDE, now)

        assertEquals(LocalDateTime.of(2026, 3, 4, 0, 0), window.centerStart)
        assertEquals(LocalDateTime.of(2026, 3, 5, 0, 0), window.centerEnd)
        assertEquals(LocalDateTime.of(2026, 2, 25, 10, 0), window.nowStart)
        assertEquals(LocalDateTime.of(2026, 2, 25, 11, 0), window.nowEnd)
    }

    @Test
    fun `buildGraphQueryWindow omits now window when it overlaps center window`() {
        val now = LocalDateTime.of(2026, 2, 25, 10, 12)
        val centerTime = now.plusHours(1).withMinute(10)

        val window = WidgetIntentRouter.buildGraphQueryWindow(centerTime, ZoomLevel.WIDE, now)

        assertEquals(LocalDateTime.of(2026, 2, 25, 3, 0), window.centerStart)
        assertEquals(LocalDateTime.of(2026, 2, 26, 3, 0), window.centerEnd)
        assertNull(window.nowStart)
        assertNull(window.nowEnd)
    }
}
