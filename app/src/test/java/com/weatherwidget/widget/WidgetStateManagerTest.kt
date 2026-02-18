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
}
