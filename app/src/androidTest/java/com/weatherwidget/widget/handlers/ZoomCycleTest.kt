package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.testutil.AndroidTestWidgetState
import com.weatherwidget.testutil.IsolatedIntegrationTest
import com.weatherwidget.widget.ViewMode
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import com.weatherwidget.widget.ZoomLevel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for zoom level cycling behavior.
 * Verifies that tapping the graph toggles between WIDE and NARROW zoom,
 * that zoom state persists across widget updates, and resets appropriately.
 */
@RunWith(AndroidJUnit4::class)
class ZoomCycleTest : IsolatedIntegrationTest("zoom_cycle") {

    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 99992

    @Before
    override fun setup() {
        super.setup()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
    }

    @After
    override fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        super.cleanup()
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
    fun handleCycleZoom_withOffset_recentersOnZoomIn() {
        // Start with an existing offset (e.g., scrolled forward by 4 hours)
        stateManager.setHourlyOffset(testWidgetId, 4)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))

        // Simulate tapping a zone that calculated an absolute center of 9
        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, zoomCenterOffset = 9)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
        
        // The absolute offset should be exactly what was passed in
        assertEquals(9, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun handleCycleZoom_withoutOffset_keepsCurrentOffset() {
        stateManager.setHourlyOffset(testWidgetId, 5)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))

        // Zoom in without offset (e.g. from NARROW back out)
        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, zoomCenterOffset = null)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
        // Offset unchanged
        assertEquals(5, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun handleCycleZoom_zoomOut_ignoresOffset() {
        // Start in NARROW zoom
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
        stateManager.setHourlyOffset(testWidgetId, 9)

        // Zoom out — offset param should be ignored since we're going NARROW→WIDE
        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, zoomCenterOffset = 0)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
        // Offset should remain 9, not changed to 0
        assertEquals(9, stateManager.getHourlyOffset(testWidgetId))
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

    // --- Integration tests: zone intent round-trip ---

    /**
     * Simulates the full zone tap flow:
     * 1. Build intent the same way setupZoomTapZones does (using zoneIndexToOffset)
     * 2. Extract the extra the same way handleCycleZoomAction does
     * 3. Pass through handleCycleZoom
     * 4. Verify final state
     */
    @Test
    fun zoneIntentRoundTrip_allZones_producesCorrectOffsets() {
        val baseOffset = 0
        stateManager.setHourlyOffset(testWidgetId, baseOffset)

        // WIDE view spans -8..+16 from baseOffset; each zone resolves to the earlier
        // hour in its 2h bucket so narrow mode can center the tapped hour.
        val expectedOffsets = listOf(-8, -6, -4, -2, 0, 2, 4, 6, 8, 10, 12, 14)

        for (zoneIndex in 0 until WeatherWidgetProvider.HOUR_ZONE_COUNT) {
            // Reset to WIDE zoom for each zone test
            stateManager.clearWidgetState(testWidgetId)
            stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
            stateManager.setHourlyOffset(testWidgetId, baseOffset)
            assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))

            // Step 1: Build intent (same as setupZoomTapZones)
            val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(zoneIndex, baseOffset)
            val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_CYCLE_ZOOM
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
                putExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
            }

            // Step 2: Extract extra (same as handleCycleZoomAction)
            val extractedOffset = if (intent.hasExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET)) {
                intent.getIntExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, 0)
            } else {
                null
            }

            // Step 3: Pass through router
            runBlocking {
                try {
                    WidgetIntentRouter.handleCycleZoom(context, testWidgetId, extractedOffset)
                } catch (_: Exception) {}
            }

            // Step 4: Verify
            assertEquals("Zone $zoneIndex should zoom to NARROW", ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
            assertEquals("Zone $zoneIndex offset", expectedOffsets[zoneIndex], stateManager.getHourlyOffset(testWidgetId))
        }
    }

    @Test
    fun zoneIntentRoundTrip_withNonZeroBaseOffset_addsCorrectly() {
        val baseOffset = 6  // User has navigated 6h forward
        stateManager.setHourlyOffset(testWidgetId, baseOffset)

        // Tap zone 0 (leftmost): with baseOffset=6 this should target absolute offset -2.
        val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(0, baseOffset)
        val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WeatherWidgetProvider.ACTION_CYCLE_ZOOM
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
            putExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
        }

        val extractedOffset = intent.getIntExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, 0)

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, extractedOffset)
            } catch (_: Exception) {}
        }

        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
        assertEquals(-2, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun zoneIntentRoundTrip_narrowZoomOut_noOffsetExtra() {
        // Start in NARROW
        stateManager.cycleZoomLevel(testWidgetId)
        stateManager.setHourlyOffset(testWidgetId, 9)

        // Build intent without EXTRA_ZOOM_CENTER_OFFSET (same as NARROW tap)
        val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
            action = WeatherWidgetProvider.ACTION_CYCLE_ZOOM
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
        }

        // Extract: should be null since no extra
        assertFalse(intent.hasExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET))
        val extractedOffset: Int? = null

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, extractedOffset)
            } catch (_: Exception) {}
        }

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
        assertEquals(9, stateManager.getHourlyOffset(testWidgetId))  // Preserved
    }
}
