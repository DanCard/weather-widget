package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.widget.handlers.WidgetIntentRouter
import com.weatherwidget.widget.handlers.RefreshScheduler
import com.weatherwidget.widget.WidgetActions
import com.weatherwidget.widget.WidgetActions.ACTION_CYCLE_ZOOM
import com.weatherwidget.widget.WidgetActions.EXTRA_ZOOM_CENTER_OFFSET
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
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
        // These tests exercise widget-state mechanics, not location handling. They previously relied
        // on the resolver's Google-HQ fallback to supply a location; with that gone, an interaction on
        // a location-less widget correctly paints the no-location state instead of rendering.
        ActiveLocationResolver.persist(context, 37.4220, -122.0841)
        RefreshScheduler.setIsRefreshDisabledForTesting(true)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
        RefreshScheduler.setIsRefreshDisabledForTesting(false)
    }

    @Test
    fun defaultZoomLevel_isWide() {
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun cycleZoom_wideThenNarrowThenTwoDayThenWide_whenEnabled() {
        stateManager.setMultiDayZoomEnabled(true)

        val afterFirst = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.NARROW, afterFirst)
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))

        val afterSecond = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.TWO_DAY, afterSecond)
        assertEquals(ZoomStage.TWO_DAY, stateManager.getZoomStage(testWidgetId))

        val afterThird = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.WIDE, afterThird)
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun cycleZoom_isATwoStopToggle_whenDisabled() {
        stateManager.setMultiDayZoomEnabled(false)

        assertEquals(ZoomStage.NARROW, stateManager.cycleZoomLevel(testWidgetId))
        assertEquals(ZoomStage.WIDE, stateManager.cycleZoomLevel(testWidgetId))
        assertEquals(ZoomStage.NARROW, stateManager.cycleZoomLevel(testWidgetId))
    }

    @Test
    fun twoDayZoom_hasHistoryLeaningSpan() {
        assertEquals(42L, ZoomStage.TWO_DAY.window().backHours)
        assertEquals(6L, ZoomStage.TWO_DAY.window().forwardHours)
        assertEquals(48L, ZoomStage.TWO_DAY.window().totalSpanHours)
    }

    @Test
    fun widgetPersistedOnTwoDay_readsBackAsWide_whenDisabled() {
        stateManager.setMultiDayZoomEnabled(true)
        stateManager.setZoomLevel(testWidgetId, ZoomStage.TWO_DAY)
        assertEquals(ZoomStage.TWO_DAY, stateManager.getZoomStage(testWidgetId))

        stateManager.setMultiDayZoomEnabled(false)

        assertEquals(
            "disabling the 2-day stage must not strand a widget on an unreachable view",
            ZoomStage.WIDE,
            stateManager.getZoomStage(testWidgetId),
        )
    }

    @Test
    fun zoomPersists_acrossWidgetUpdates() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))

        val freshStateManager = WidgetStateManager(context)
        assertEquals(ZoomStage.NARROW, freshStateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun zoomResets_whenSwitchingToDailyFromHourly() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))

        stateManager.toggleViewMode(testWidgetId)
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun zoomResets_whenSwitchingToDailyFromPrecipitation() {
        stateManager.setViewMode(testWidgetId, ViewMode.PRECIPITATION)
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))

        stateManager.togglePrecipitationMode(testWidgetId)
        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun zoomPreserved_whenSwitchingBetweenHourlyAndPrecip() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))

        runBlocking {
            try {
                WidgetIntentRouter.handleSetView(context, testWidgetId, ViewMode.PRECIPITATION)
            } catch (_: Exception) {}
        }

        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun handleCycleZoom_cyclesViaRouter() {
        stateManager.setMultiDayZoomEnabled(true)
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomStage.TWO_DAY, stateManager.getZoomStage(testWidgetId))

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun handleCycleZoom_withOffset_recentersOnZoomIn() {
        stateManager.setHourlyOffset(testWidgetId, 4)
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, zoomCenterOffset = 9)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))
        assertEquals(9, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun handleCycleZoom_withoutOffset_keepsCurrentOffset() {
        stateManager.setHourlyOffset(testWidgetId, 5)
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, zoomCenterOffset = null)
            } catch (_: Exception) {}
        }
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))
        assertEquals(5, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun handleCycleZoom_zoomOut_usesOffset() {
        stateManager.setMultiDayZoomEnabled(true)
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))
        stateManager.setHourlyOffset(testWidgetId, 9)

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, zoomCenterOffset = 0)
            } catch (_: Exception) {}
        }
        // Cycle from NARROW now advances to TWO_DAY (WIDE -> NARROW -> TWO_DAY -> WIDE).
        assertEquals(ZoomStage.TWO_DAY, stateManager.getZoomStage(testWidgetId))
        assertEquals(0, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun navJump_scalesWithZoom() {
        assertEquals(3, stateManager.getNavJump(testWidgetId))

        stateManager.cycleZoomLevel(testWidgetId)

        assertEquals(1, stateManager.getNavJump(testWidgetId))
    }

    @Test
    fun navigation_usesZoomAwareJump() {
        stateManager.setHourlyOffset(testWidgetId, 0)

        stateManager.navigateHourlyRight(testWidgetId)
        assertEquals(3, stateManager.getHourlyOffset(testWidgetId))

        stateManager.setHourlyOffset(testWidgetId, 0)
        stateManager.cycleZoomLevel(testWidgetId)
        stateManager.navigateHourlyRight(testWidgetId)
        assertEquals(1, stateManager.getHourlyOffset(testWidgetId))

        stateManager.navigateHourlyLeft(testWidgetId)
        assertEquals(0, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun clearWidgetState_resetsZoom() {
        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))

        stateManager.clearWidgetState(testWidgetId)
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun zoneIntentRoundTrip_allZones_producesCorrectOffsets() {
        val baseOffset = 0
        stateManager.setHourlyOffset(testWidgetId, baseOffset)

        // WIDE covers -12..+6 around the center across 13 zones, so the zones step 1.5h and round.
        val expectedOffsets = listOf(-12, -10, -9, -7, -6, -4, -3, -1, 0, 2, 3, 5, 6)

        for (zoneIndex in 0 until HourlyTouchZoneMapper.HOUR_ZONE_COUNT) {
            stateManager.clearWidgetState(testWidgetId)
            stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
            stateManager.setHourlyOffset(testWidgetId, baseOffset)
            assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))

            val zoneCenterOffset = HourlyTouchZoneMapper.zoneIndexToOffset(zoneIndex, baseOffset, ZoomStage.WIDE.window())
            val intent = Intent(context, WidgetActionReceiver::class.java).apply {
                action = WidgetActions.ACTION_CYCLE_ZOOM
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
                putExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
            }

            val extractedOffset = if (                intent.hasExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET)) {
                intent.getIntExtra(WidgetActions.EXTRA_ZOOM_CENTER_OFFSET, 0)
            } else {
                null
            }

            runBlocking {
                try {
                    WidgetIntentRouter.handleCycleZoom(context, testWidgetId, extractedOffset)
                } catch (_: Exception) {}
            }

            assertEquals("Zone $zoneIndex should zoom to NARROW", ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))
            assertEquals("Zone $zoneIndex offset", expectedOffsets[zoneIndex], stateManager.getHourlyOffset(testWidgetId))
        }
    }

    @Test
    fun zoneIntentRoundTrip_withNonZeroBaseOffset_addsCorrectly() {
        val baseOffset = 6
        stateManager.setHourlyOffset(testWidgetId, baseOffset)

        val zoneCenterOffset = HourlyTouchZoneMapper.zoneIndexToOffset(0, baseOffset, ZoomStage.WIDE.window())
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = ACTION_CYCLE_ZOOM
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
            putExtra(EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
        }

        val extractedOffset = intent.getIntExtra(EXTRA_ZOOM_CENTER_OFFSET, 0)

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, extractedOffset)
            } catch (_: Exception) {}
        }

        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))
        assertEquals(-6, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun zoneIntentRoundTrip_narrowZoomOut_usesOffsetExtra() {
        stateManager.setMultiDayZoomEnabled(true)
        stateManager.cycleZoomLevel(testWidgetId)
        val baseOffset = 0
        stateManager.setHourlyOffset(testWidgetId, baseOffset)

        val zoneCenterOffset = HourlyTouchZoneMapper.zoneIndexToOffset(0, baseOffset, ZoomStage.NARROW.window())
        val intent = Intent(context, WidgetActionReceiver::class.java).apply {
            action = ACTION_CYCLE_ZOOM
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, testWidgetId)
            putExtra(EXTRA_ZOOM_CENTER_OFFSET, zoneCenterOffset)
        }

        val extractedOffset = intent.getIntExtra(EXTRA_ZOOM_CENTER_OFFSET, 0)

        runBlocking {
            try {
                WidgetIntentRouter.handleCycleZoom(context, testWidgetId, extractedOffset)
            } catch (_: Exception) {}
        }

        // Cycle from NARROW now advances to TWO_DAY; the NARROW-zone offset extra (-3 at the
        // default 5h span) is still applied.
        assertEquals(ZoomStage.TWO_DAY, stateManager.getZoomStage(testWidgetId))
        assertEquals(-3, stateManager.getHourlyOffset(testWidgetId))
    }
}