package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class WeatherWidgetProviderRobolectricTest {

    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private lateinit var provider: WeatherWidgetProvider
    private lateinit var actionReceiver: WidgetActionReceiver
    private val widgetId = 9011

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        provider = WeatherWidgetProvider()
        actionReceiver = WidgetActionReceiver()
        stateManager.clearWidgetState(widgetId)
    }

    @Test
    fun `onDeleted clears widget state`() {
        stateManager.setViewMode(widgetId, ViewMode.PRECIPITATION)
        stateManager.setHourlyOffset(widgetId, 24)
        stateManager.setDateOffset(widgetId, 5)
        stateManager.setZoomLevel(widgetId, ZoomLevel.NARROW)
        stateManager.setTransientMessage(widgetId, "stale", Long.MAX_VALUE)
        stateManager.markMissingDataRefreshRequested(widgetId, "NWS", "hourly_gaps")
        stateManager.setWidgetLocations(intArrayOf(widgetId), 37.42, -122.08)

        provider.onDeleted(context, intArrayOf(widgetId))

        assertEquals(ViewMode.DAILY, stateManager.getViewMode(widgetId))
        assertEquals(0, stateManager.getHourlyOffset(widgetId))
        assertEquals(0, stateManager.getDateOffset(widgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(widgetId))
        assertNull(stateManager.getActiveTransientMessage(widgetId))
        assertNull(stateManager.getStoredWidgetLocation(widgetId))
        assertTrue(stateManager.shouldRefreshMissingData(widgetId, "NWS", "hourly_gaps", Long.MAX_VALUE))
    }

    @Test
    fun `onReceive with invalid widget id navigation keeps state unchanged`() {
        stateManager.setDateOffset(widgetId, 2)

        val intent = Intent(WidgetActions.ACTION_NAV_LEFT).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }

        actionReceiver.onReceive(context, intent)

        assertEquals(2, stateManager.getDateOffset(widgetId))
    }

    @Test
    fun `malformed set-view action is rejected without changing widget state`() {
        stateManager.setViewMode(widgetId, ViewMode.TEMPERATURE)
        val intent =
            Intent(WidgetActions.ACTION_SET_VIEW).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                putExtra(WidgetActions.EXTRA_TARGET_VIEW, "not-a-view")
            }

        actionReceiver.onReceive(context, intent)

        assertEquals(ViewMode.TEMPERATURE, stateManager.getViewMode(widgetId))
    }

    @Test
    fun `zoneIndexToOffset maps edge zones correctly`() {
        assertEquals(-12, HourlyTouchZoneMapper.zoneIndexToOffset(0, 0))
        assertEquals(12, HourlyTouchZoneMapper.zoneIndexToOffset(12, 0))
        assertEquals(0, HourlyTouchZoneMapper.zoneIndexToOffset(6, 0))
        assertEquals(-2, HourlyTouchZoneMapper.zoneIndexToOffset(5, 0))
    }

    @Test
    fun `zoneIndexToOffset maps THREE_DAY asymmetric window`() {
        // THREE_DAY spans 48h back / 24h forward (72h). The visual-center zone (6) sits at
        // (24-48)/2 = -12h, and the edges land exactly on the window bounds.
        assertEquals(-48, HourlyTouchZoneMapper.zoneIndexToOffset(0, 0, ZoomLevel.THREE_DAY))
        assertEquals(-12, HourlyTouchZoneMapper.zoneIndexToOffset(6, 0, ZoomLevel.THREE_DAY))
        assertEquals(24, HourlyTouchZoneMapper.zoneIndexToOffset(12, 0, ZoomLevel.THREE_DAY))
        // Base offset is added through.
        assertEquals(-38, HourlyTouchZoneMapper.zoneIndexToOffset(0, 10, ZoomLevel.THREE_DAY))
    }
}
