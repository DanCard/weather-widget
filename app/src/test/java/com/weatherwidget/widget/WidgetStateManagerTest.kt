package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class WidgetStateManagerTest {
    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var stateManager: WidgetStateManager

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        prefs = mockk(relaxed = true)
        editor = mockk(relaxed = true)
        
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        
        stateManager = WidgetStateManager(context)
    }

    @Test
    fun `toggleViewMode from DAILY to HOURLY resets offset`() {
        val widgetId = 1
        every { prefs.getInt("widget_view_mode_$widgetId", any()) } returns ViewMode.DAILY.ordinal
        
        stateManager.toggleViewMode(widgetId)
        
        // Should set view mode to HOURLY and reset offset to 0
        verify { editor.putInt("widget_view_mode_$widgetId", ViewMode.HOURLY.ordinal) }
        verify { editor.putInt("widget_hourly_offset_$widgetId", 0) }
    }

    @Test
    fun `toggleViewMode from PRECIPITATION to DAILY does not reset hourly offset`() {
        val widgetId = 1
        every { prefs.getInt("widget_view_mode_$widgetId", any()) } returns ViewMode.PRECIPITATION.ordinal
        
        stateManager.toggleViewMode(widgetId)
        
        // Should set view mode to DAILY
        verify { editor.putInt("widget_view_mode_$widgetId", ViewMode.DAILY.ordinal) }
        // Should NOT reset hourly offset (it's only reset when entering HOURLY from DAILY)
        verify(exactly = 0) { editor.putInt("widget_hourly_offset_$widgetId", 0) }
    }

    @Test
    fun `togglePrecipitationMode from DAILY to PRECIPITATION resets offset`() {
        val widgetId = 1
        every { prefs.getInt("widget_view_mode_$widgetId", any()) } returns ViewMode.DAILY.ordinal

        stateManager.togglePrecipitationMode(widgetId)

        verify { editor.putInt("widget_view_mode_$widgetId", ViewMode.PRECIPITATION.ordinal) }
        verify { editor.putInt("widget_hourly_offset_$widgetId", 0) }
    }

    // --- Zoom level tests ---

    @Test
    fun `getZoomLevel defaults to WIDE`() {
        val widgetId = 1
        every { prefs.getInt("widget_zoom_level_$widgetId", any()) } returns ZoomLevel.WIDE.ordinal

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(widgetId))
    }

    @Test
    fun `cycleZoomLevel toggles WIDE to NARROW`() {
        val widgetId = 1
        every { prefs.getInt("widget_zoom_level_$widgetId", any()) } returns ZoomLevel.WIDE.ordinal

        val result = stateManager.cycleZoomLevel(widgetId)

        assertEquals(ZoomLevel.NARROW, result)
        verify { editor.putInt("widget_zoom_level_$widgetId", ZoomLevel.NARROW.ordinal) }
    }

    @Test
    fun `cycleZoomLevel toggles NARROW back to WIDE`() {
        val widgetId = 1
        every { prefs.getInt("widget_zoom_level_$widgetId", any()) } returns ZoomLevel.NARROW.ordinal

        val result = stateManager.cycleZoomLevel(widgetId)

        assertEquals(ZoomLevel.WIDE, result)
        verify { editor.putInt("widget_zoom_level_$widgetId", ZoomLevel.WIDE.ordinal) }
    }

    @Test
    fun `getNavJump returns zoom-appropriate value`() {
        val widgetId = 1
        every { prefs.getInt("widget_zoom_level_$widgetId", any()) } returns ZoomLevel.WIDE.ordinal
        assertEquals(6, stateManager.getNavJump(widgetId))

        every { prefs.getInt("widget_zoom_level_$widgetId", any()) } returns ZoomLevel.NARROW.ordinal
        assertEquals(2, stateManager.getNavJump(widgetId))
    }

    @Test
    fun `navigateHourlyRight uses zoom-aware nav jump`() {
        val widgetId = 1
        every { prefs.getInt("widget_hourly_offset_$widgetId", any()) } returns 0
        every { prefs.getInt("widget_zoom_level_$widgetId", any()) } returns ZoomLevel.NARROW.ordinal

        val result = stateManager.navigateHourlyRight(widgetId)

        assertEquals(2, result)
        verify { editor.putInt("widget_hourly_offset_$widgetId", 2) }
    }

    @Test
    fun `navigateHourlyLeft uses zoom-aware nav jump`() {
        val widgetId = 1
        every { prefs.getInt("widget_hourly_offset_$widgetId", any()) } returns 6
        every { prefs.getInt("widget_zoom_level_$widgetId", any()) } returns ZoomLevel.NARROW.ordinal

        val result = stateManager.navigateHourlyLeft(widgetId)

        assertEquals(4, result)
        verify { editor.putInt("widget_hourly_offset_$widgetId", 4) }
    }

    @Test
    fun `toggleViewMode to DAILY resets zoom to WIDE`() {
        val widgetId = 1
        every { prefs.getInt("widget_view_mode_$widgetId", any()) } returns ViewMode.HOURLY.ordinal

        stateManager.toggleViewMode(widgetId)

        verify { editor.putInt("widget_view_mode_$widgetId", ViewMode.DAILY.ordinal) }
        verify { editor.putInt("widget_zoom_level_$widgetId", ZoomLevel.WIDE.ordinal) }
    }

    @Test
    fun `togglePrecipitationMode to DAILY resets zoom to WIDE`() {
        val widgetId = 1
        every { prefs.getInt("widget_view_mode_$widgetId", any()) } returns ViewMode.PRECIPITATION.ordinal

        stateManager.togglePrecipitationMode(widgetId)

        verify { editor.putInt("widget_view_mode_$widgetId", ViewMode.DAILY.ordinal) }
        verify { editor.putInt("widget_zoom_level_$widgetId", ZoomLevel.WIDE.ordinal) }
    }

    @Test
    fun `clearWidgetState removes zoom level`() {
        val widgetId = 1
        every { prefs.all } returns emptyMap<String, Any>()

        stateManager.clearWidgetState(widgetId)

        verify { editor.remove("widget_zoom_level_$widgetId") }
    }

    @Test
    fun `ZoomLevel enum has correct parameters`() {
        assertEquals(8L, ZoomLevel.WIDE.backHours)
        assertEquals(16L, ZoomLevel.WIDE.forwardHours)
        assertEquals(6, ZoomLevel.WIDE.navJump)
        assertEquals(4, ZoomLevel.WIDE.labelInterval)
        assertEquals(2, ZoomLevel.WIDE.precipSmoothIterations)

        assertEquals(2L, ZoomLevel.NARROW.backHours)
        assertEquals(3L, ZoomLevel.NARROW.forwardHours)
        assertEquals(2, ZoomLevel.NARROW.navJump)
        assertEquals(1, ZoomLevel.NARROW.labelInterval)
        assertEquals(0, ZoomLevel.NARROW.precipSmoothIterations)
    }
}
