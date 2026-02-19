package com.weatherwidget.widget.handlers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for zoom level cycling behavior.
 * Verifies that tapping the graph toggles between WIDE and NARROW zoom,
 * that zoom state persists across widget updates, and resets appropriately.
 */
@RunWith(AndroidJUnit4::class)
class ZoomCycleTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 99992

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        stateManager.setViewMode(testWidgetId, ViewMode.HOURLY)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
    }

    @Test
    fun defaultZoomLevel_isWide() {
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun cycleZoom_wideThenNarrowThenWide() {
        // First tap: WIDE → NARROW
        val afterFirst = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, afterFirst)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        // Second tap: NARROW → WIDE
        val afterSecond = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.WIDE, afterSecond)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun zoomPersists_acrossWidgetUpdates() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        // Simulate a widget update by creating a new WidgetStateManager instance
        val freshStateManager = WidgetStateManager(context)
        assertEquals(ZoomLevel.NARROW, freshStateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun zoomResets_whenSwitchingToDailyFromHourly() {
        // Set NARROW zoom in hourly mode
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        // Toggle back to DAILY
        stateManager.toggleViewMode(testWidgetId)
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun zoomResets_whenSwitchingToDailyFromPrecipitation() {
        stateManager.setViewMode(testWidgetId, ViewMode.PRECIPITATION)
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        // Toggle back to DAILY
        stateManager.togglePrecipitationMode(testWidgetId)
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun zoomPreserved_whenSwitchingBetweenHourlyAndPrecip() {
        // Zoom in while in HOURLY
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        // Switch to PRECIPITATION (not going through DAILY)
        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.PRECIPITATION)
            } catch (_: Exception) {}
        }

        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun handleCycleZoom_cyclesViaRouter() {
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))

        // Simulate graph tap via router
        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        // Tap again
        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun navJump_scalesWithZoom() {
        // WIDE: 6h jumps
        assertEquals(6, stateManager.getNavJump(testWidgetId))

        stateManager.cycleZoomLevel(testWidgetId)

        // NARROW: 2h jumps
        assertEquals(2, stateManager.getNavJump(testWidgetId))
    }

    @Test
    fun navigation_usesZoomAwareJump() {
        stateManager.setHourlyOffset(testWidgetId, 0)

        // WIDE zoom: navigate right should jump 6
        stateManager.navigateHourlyRight(testWidgetId)
        assertEquals(6, stateManager.getHourlyOffset(testWidgetId))

        // Switch to NARROW and navigate right: should jump 2
        stateManager.setHourlyOffset(testWidgetId, 0)
        stateManager.cycleZoomLevel(testWidgetId)
        stateManager.navigateHourlyRight(testWidgetId)
        assertEquals(2, stateManager.getHourlyOffset(testWidgetId))

        // Navigate left from 2: should go back to 0
        stateManager.navigateHourlyLeft(testWidgetId)
        assertEquals(0, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun clearWidgetState_resetsZoom() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        stateManager.clearWidgetState(testWidgetId)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }
}
