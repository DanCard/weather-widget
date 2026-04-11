package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ZoomCycleRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 99992

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
        WidgetIntentRouter.setDisableRefreshForTesting(true)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        WidgetIntentRouter.setDisableRefreshForTesting(false)
    }

    @Test
    fun defaultZoomLevel_isWide() {
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun cycleZoom_wideThenNarrowThenWide() {
        val afterFirst = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, afterFirst)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        val afterSecond = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.WIDE, afterSecond)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun zoomPersists_acrossWidgetUpdates() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        val freshStateManager = WidgetStateManager(context)
        assertEquals(ZoomLevel.NARROW, freshStateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun zoomResets_whenSwitchingToDailyFromHourly() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        stateManager.toggleViewMode(testWidgetId)
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun zoomResets_whenSwitchingToDailyFromPrecipitation() {
        stateManager.setViewMode(testWidgetId, ViewMode.PRECIPITATION)
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        stateManager.togglePrecipitationMode(testWidgetId)
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun zoomPreserved_whenSwitchingBetweenHourlyAndPrecip() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

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

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun handleCycleZoom_withOffset_recentersOnZoomIn() {
        stateManager.setHourlyOffset(testWidgetId, 4)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, zoomCenterOffset = 9)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
        assertEquals(9, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun handleCycleZoom_withoutOffset_keepsCurrentOffset() {
        stateManager.setHourlyOffset(testWidgetId, 5)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, zoomCenterOffset = null)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
        assertEquals(5, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun handleCycleZoom_zoomOut_usesOffset() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
        stateManager.setHourlyOffset(testWidgetId, 9)

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, zoomCenterOffset = 0)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
        assertEquals(0, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun navJump_scalesWithZoom() {
        assertEquals(6, stateManager.getNavJump(testWidgetId))

        stateManager.cycleZoomLevel(testWidgetId)

        assertEquals(2, stateManager.getNavJump(testWidgetId))
    }

    @Test
    fun navigation_usesZoomAwareJump() {
        stateManager.setHourlyOffset(testWidgetId, 0)

        stateManager.navigateHourlyRight(testWidgetId)
        assertEquals(6, stateManager.getHourlyOffset(testWidgetId))

        stateManager.setHourlyOffset(testWidgetId, 0)
        stateManager.cycleZoomLevel(testWidgetId)
        stateManager.navigateHourlyRight(testWidgetId)
        assertEquals(2, stateManager.getHourlyOffset(testWidgetId))

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

    @Test
    fun zoneIntentRoundTrip_allZones_producesCorrectOffsets() {
        val baseOffset = 0
        stateManager.setHourlyOffset(testWidgetId, baseOffset)

        val expectedOffsets = listOf(-12, -10, -8, -6, -4, -2, 0, 2, 4, 6, 8, 10, 12)

        for (zoneIndex in 0 until WeatherWidgetProvider.HOUR_ZONE_COUNT) {
            stateManager.clearWidgetState(testWidgetId)
            stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
            stateManager.setHourlyOffset(testWidgetId, baseOffset)
            assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))

            val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(zoneIndex, baseOffset, ZoomLevel.WIDE)
            val intent = Intent(context, WeatherWidgetProvider::class.java).apply {
                action = WeatherWidgetProvider.ACTION_CYCLE_ZOOM
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
                putExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
            }

            val extractedOffset = if (intent.hasExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET)) {
                intent.getIntExtra(WeatherWidgetProvider.EXTRA_ZOOM_CENTER_OFFSET, 0)
            } else {
                null
            }

            runBlocking {
                try {
                    WidgetIntentRouter.handleCycleZoom(context, testWidgetId, extractedOffset)
                } catch (_: Exception) {}
            }

            assertEquals("Zone $zoneIndex should zoom to NARROW", ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
            assertEquals("Zone $zoneIndex offset", expectedOffsets[zoneIndex], stateManager.getHourlyOffset(testWidgetId))
        }
    }

    @Test
    fun zoneIntentRoundTrip_withNonZeroBaseOffset_addsCorrectly() {
        val baseOffset = 6
        stateManager.setHourlyOffset(testWidgetId, baseOffset)

        val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(0, baseOffset, ZoomLevel.WIDE)
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
        assertEquals(-6, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun zoneIntentRoundTrip_narrowZoomOut_usesOffsetExtra() {
        stateManager.cycleZoomLevel(testWidgetId)
        val baseOffset = 0
        stateManager.setHourlyOffset(testWidgetId, baseOffset)

        val zoneCenterOffset = WeatherWidgetProvider.zoneIndexToOffset(0, baseOffset, ZoomLevel.NARROW)
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

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
        assertEquals(-2, stateManager.getHourlyOffset(testWidgetId))
    }
}