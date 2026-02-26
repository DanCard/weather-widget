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
        every { editor.putFloat(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        
        stateManager = WidgetStateManager(context)
    }

    @Test
    fun `toggleViewMode from DAILY to HOURLY resets offset and zoom`() {
        val widgetId = 1
        every { prefs.getInt("widget_view_mode_$widgetId", any()) } returns ViewMode.DAILY.ordinal
        
        stateManager.toggleViewMode(widgetId)
        
        // Should set view mode to HOURLY and reset offset to 0 and zoom to WIDE
        verify { editor.putInt("widget_view_mode_$widgetId", ViewMode.TEMPERATURE.ordinal) }
        verify { editor.putInt("widget_hourly_offset_$widgetId", 0) }
        verify { editor.putInt("widget_zoom_level_$widgetId", ZoomLevel.WIDE.ordinal) }
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
    fun `togglePrecipitationMode from DAILY to PRECIPITATION resets offset and zoom`() {
        val widgetId = 1
        every { prefs.getInt("widget_view_mode_$widgetId", any()) } returns ViewMode.DAILY.ordinal

        stateManager.togglePrecipitationMode(widgetId)

        // Should set view mode to PRECIPITATION and reset offset to 0 and zoom to WIDE
        verify { editor.putInt("widget_view_mode_$widgetId", ViewMode.PRECIPITATION.ordinal) }
        verify { editor.putInt("widget_hourly_offset_$widgetId", 0) }
        verify { editor.putInt("widget_zoom_level_$widgetId", ZoomLevel.WIDE.ordinal) }
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
    fun `setHourlyOffset preserves day-click future offsets within range`() {
        val widgetId = 1

        stateManager.setHourlyOffset(widgetId, 129)

        verify { editor.putInt("widget_hourly_offset_$widgetId", 129) }
    }

    @Test
    fun `toggleViewMode to DAILY resets zoom to WIDE`() {
        val widgetId = 1
        every { prefs.getInt("widget_view_mode_$widgetId", any()) } returns ViewMode.TEMPERATURE.ordinal

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
    fun `toggleDisplaySource cycles through visible sources in order`() {
        val widgetId = 1
        // Already migrated, visible sources set
        every { prefs.getBoolean("api_pref_migrated", false) } returns true
        every { prefs.getString("visible_sources_order", any()) } returns "NWS,OPEN_METEO,WEATHER_API"
        every { prefs.contains("widget_display_source_$widgetId") } returns true
        var storedStep = 0
        every { prefs.getInt("widget_display_source_$widgetId", any()) } answers { storedStep }
        every { editor.putInt("widget_display_source_$widgetId", any()) } answers {
            storedStep = secondArg()
            editor
        }

        val first = stateManager.getCurrentDisplaySource(widgetId)
        val second = stateManager.toggleDisplaySource(widgetId)
        val third = stateManager.toggleDisplaySource(widgetId)
        val fourth = stateManager.toggleDisplaySource(widgetId)

        assertEquals(com.weatherwidget.data.model.WeatherSource.NWS, first)
        assertEquals(com.weatherwidget.data.model.WeatherSource.OPEN_METEO, second)
        assertEquals(com.weatherwidget.data.model.WeatherSource.WEATHER_API, third)
        assertEquals(com.weatherwidget.data.model.WeatherSource.NWS, fourth)
    }

    @Test
    fun `toggleDisplaySource clears current temp delta state`() {
        val widgetId = 1
        every { prefs.getBoolean("api_pref_migrated", false) } returns true
        every { prefs.getString("visible_sources_order", any()) } returns "NWS,OPEN_METEO,WEATHER_API"
        every { prefs.contains("widget_display_source_$widgetId") } returns true
        every { prefs.getInt("widget_display_source_$widgetId", any()) } returns 0

        stateManager.toggleDisplaySource(widgetId)

        verify { editor.remove("widget_current_temp_delta_$widgetId") }
        verify { editor.remove("widget_current_temp_delta_observed_$widgetId") }
        verify { editor.remove("widget_current_temp_delta_fetched_at_$widgetId") }
        verify { editor.remove("widget_current_temp_delta_updated_at_$widgetId") }
        verify { editor.remove("widget_current_temp_delta_source_$widgetId") }
        verify { editor.remove("widget_current_temp_delta_lat_$widgetId") }
        verify { editor.remove("widget_current_temp_delta_lon_$widgetId") }
    }

    @Test
    fun `toggleDisplaySource cycles only visible sources when some hidden`() {
        val widgetId = 1
        every { prefs.getBoolean("api_pref_migrated", false) } returns true
        every { prefs.getString("visible_sources_order", any()) } returns "NWS,WEATHER_API"
        every { prefs.contains("widget_display_source_$widgetId") } returns true
        var storedStep = 0
        every { prefs.getInt("widget_display_source_$widgetId", any()) } answers { storedStep }
        every { editor.putInt("widget_display_source_$widgetId", any()) } answers {
            storedStep = secondArg()
            editor
        }

        val first = stateManager.getCurrentDisplaySource(widgetId)
        val second = stateManager.toggleDisplaySource(widgetId)
        val third = stateManager.toggleDisplaySource(widgetId)

        assertEquals(com.weatherwidget.data.model.WeatherSource.NWS, first)
        assertEquals(com.weatherwidget.data.model.WeatherSource.WEATHER_API, second)
        assertEquals(com.weatherwidget.data.model.WeatherSource.NWS, third) // wraps around
    }

    @Test
    fun `getCurrentDisplaySource migrates legacy boolean toggle state`() {
        val widgetId = 1
        // Migration not done yet, old api_preference exists (ordinal 1 = PREFER_NWS)
        every { prefs.getBoolean("api_pref_migrated", false) } returns false
        every { prefs.contains("api_preference") } returns true
        every { prefs.getInt("api_preference", any()) } returns 1 // PREFER_NWS ordinal
        // After migration writes new pref, subsequent read should return it
        every { prefs.getString("visible_sources_order", any()) } returns "NWS,OPEN_METEO,WEATHER_API"
        every { prefs.contains("widget_display_source_$widgetId") } returns false
        every { prefs.getBoolean("widget_display_source_$widgetId", false) } returns true

        val source = stateManager.getCurrentDisplaySource(widgetId)

        assertEquals(com.weatherwidget.data.model.WeatherSource.OPEN_METEO, source)
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
