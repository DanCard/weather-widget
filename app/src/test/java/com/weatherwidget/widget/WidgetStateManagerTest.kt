package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.weatherwidget.data.model.WeatherSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        every { prefs.getLong(any(), any()) } returns 0L
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
    fun `getEffectiveVisibleSourcesOrder preserves Open-Meteo when enabled`() {
        every { prefs.getBoolean("api_pref_migrated", false) } returns true
        every { prefs.getString("visible_sources_order", any()) } returns "NWS,OPEN_METEO,WEATHER_API"

        val sources = stateManager.getEffectiveVisibleSourcesOrder(37.42, -122.08)

        assertEquals(
            listOf(
                com.weatherwidget.data.model.WeatherSource.NWS,
                com.weatherwidget.data.model.WeatherSource.OPEN_METEO,
                com.weatherwidget.data.model.WeatherSource.WEATHER_API,
            ),
            sources
        )
    }

    @Test
    fun `getVisibleSourcesOrder uses new default order without silurian on fresh install`() {
        every { prefs.getBoolean("api_pref_migrated", false) } returns true
        every { prefs.getBoolean("silurian_migration_done_v2", false) } returns true
        every { prefs.getString("visible_sources_order", any()) } returns null

        val sources = stateManager.getVisibleSourcesOrder()

        assertEquals(
            listOf(
                com.weatherwidget.data.model.WeatherSource.NWS,
                com.weatherwidget.data.model.WeatherSource.WEATHER_API,
                com.weatherwidget.data.model.WeatherSource.OPEN_METEO,
            ),
            sources
        )
    }

    @Test
    fun `getVisibleSourcesOrder migrates existing stored order to append silurian`() {
        every { prefs.getBoolean("api_pref_migrated", false) } returns true
        every { prefs.getBoolean("silurian_migration_done_v2", false) } returns false
        every { prefs.getString("visible_sources_order", any()) } returnsMany listOf(
            "NWS,WEATHER_API,OPEN_METEO",
            "NWS,WEATHER_API,OPEN_METEO,SILURIAN",
        )

        val sources = stateManager.getVisibleSourcesOrder()

        assertEquals(
            listOf(
                com.weatherwidget.data.model.WeatherSource.NWS,
                com.weatherwidget.data.model.WeatherSource.WEATHER_API,
                com.weatherwidget.data.model.WeatherSource.OPEN_METEO,
                com.weatherwidget.data.model.WeatherSource.SILURIAN,
            ),
            sources
        )
        verify { editor.putString("visible_sources_order", "NWS,WEATHER_API,OPEN_METEO,SILURIAN") }
        verify { editor.putBoolean("silurian_migration_done_v2", true) }
    }

    @Test
    fun `toggleDisplaySource keeps current temp delta state`() {
        val widgetId = 1
        every { prefs.getBoolean("api_pref_migrated", false) } returns true
        every { prefs.getString("visible_sources_order", any()) } returns "NWS,OPEN_METEO,WEATHER_API"
        every { prefs.contains("widget_display_source_$widgetId") } returns true
        every { prefs.getInt("widget_display_source_$widgetId", any()) } returns 0

        stateManager.toggleDisplaySource(widgetId)

        verify(exactly = 0) { editor.remove("widget_current_temp_delta_$widgetId") }
        verify(exactly = 0) { editor.remove("widget_current_temp_delta_observed_$widgetId") }
        verify(exactly = 0) { editor.remove("widget_current_temp_delta_fetched_at_$widgetId") }
        verify(exactly = 0) { editor.remove("widget_current_temp_delta_updated_at_$widgetId") }
        verify(exactly = 0) { editor.remove("widget_current_temp_delta_source_$widgetId") }
        verify(exactly = 0) { editor.remove("widget_current_temp_delta_lat_$widgetId") }
        verify(exactly = 0) { editor.remove("widget_current_temp_delta_lon_$widgetId") }
    }

    @Test
    fun `set and get current temp delta state is source scoped`() {
        val widgetId = 7
        val nwsState =
            CurrentTemperatureDeltaState(
                delta = -5f,
                lastObservedTemp = 83f,
                lastObservedFetchedAt = 1000L,
                updatedAtMs = 1000L,
                sourceId = WeatherSource.NWS.id,
                locationLat = 37.42,
                locationLon = -122.08,
            )

        every { prefs.contains("widget_current_temp_delta_${widgetId}_${WeatherSource.NWS.id}") } returns true
        every { prefs.contains("widget_current_temp_delta_observed_${widgetId}_${WeatherSource.NWS.id}") } returns true
        every { prefs.contains("widget_current_temp_delta_fetched_at_${widgetId}_${WeatherSource.NWS.id}") } returns true
        every { prefs.contains("widget_current_temp_delta_${widgetId}_${WeatherSource.OPEN_METEO.id}") } returns false
        every { prefs.contains("widget_current_temp_delta_observed_${widgetId}_${WeatherSource.OPEN_METEO.id}") } returns false
        every { prefs.contains("widget_current_temp_delta_fetched_at_${widgetId}_${WeatherSource.OPEN_METEO.id}") } returns false
        every { prefs.getString("widget_current_temp_delta_source_${widgetId}_${WeatherSource.NWS.id}", null) } returns WeatherSource.NWS.id
        every { prefs.getString("widget_current_temp_delta_lat_${widgetId}_${WeatherSource.NWS.id}", null) } returns "37.42"
        every { prefs.getString("widget_current_temp_delta_lon_${widgetId}_${WeatherSource.NWS.id}", null) } returns "-122.08"
        every { prefs.getFloat("widget_current_temp_delta_${widgetId}_${WeatherSource.NWS.id}", 0f) } returns -5f
        every { prefs.getFloat("widget_current_temp_delta_observed_${widgetId}_${WeatherSource.NWS.id}", 0f) } returns 83f
        every { prefs.getLong("widget_current_temp_delta_fetched_at_${widgetId}_${WeatherSource.NWS.id}", 0L) } returns 1000L
        every { prefs.getLong("widget_current_temp_delta_updated_at_${widgetId}_${WeatherSource.NWS.id}", 0L) } returns 1000L

        stateManager.setCurrentTempDeltaState(widgetId, WeatherSource.NWS, nwsState)

        verify { editor.putFloat("widget_current_temp_delta_${widgetId}_${WeatherSource.NWS.id}", -5f) }
        verify { editor.putString("widget_current_temp_delta_source_${widgetId}_${WeatherSource.NWS.id}", WeatherSource.NWS.id) }

        val restoredNws = stateManager.getCurrentTempDeltaState(widgetId, WeatherSource.NWS)
        val restoredMeteo = stateManager.getCurrentTempDeltaState(widgetId, WeatherSource.OPEN_METEO)

        assertNotNull(restoredNws)
        assertEquals(WeatherSource.NWS.id, restoredNws?.sourceId)
        assertEquals(-5f, restoredNws?.delta ?: 0f, 0.01f)
        assertNull(restoredMeteo)
    }

    @Test
    fun `get current temp delta state migrates matching legacy widget scoped state`() {
        val widgetId = 9
        every { prefs.contains("widget_current_temp_delta_${widgetId}_${WeatherSource.NWS.id}") } returns false
        every { prefs.contains("widget_current_temp_delta_observed_${widgetId}_${WeatherSource.NWS.id}") } returns false
        every { prefs.contains("widget_current_temp_delta_fetched_at_${widgetId}_${WeatherSource.NWS.id}") } returns false
        every { prefs.contains("widget_current_temp_delta_$widgetId") } returns true
        every { prefs.contains("widget_current_temp_delta_observed_$widgetId") } returns true
        every { prefs.contains("widget_current_temp_delta_fetched_at_$widgetId") } returns true
        every { prefs.getString("widget_current_temp_delta_source_$widgetId", null) } returns WeatherSource.NWS.id
        every { prefs.getString("widget_current_temp_delta_lat_$widgetId", null) } returns "37.42"
        every { prefs.getString("widget_current_temp_delta_lon_$widgetId", null) } returns "-122.08"
        every { prefs.getFloat("widget_current_temp_delta_$widgetId", 0f) } returns -4f
        every { prefs.getFloat("widget_current_temp_delta_observed_$widgetId", 0f) } returns 82f
        every { prefs.getLong("widget_current_temp_delta_fetched_at_$widgetId", 0L) } returns 2000L
        every { prefs.getLong("widget_current_temp_delta_updated_at_$widgetId", 0L) } returns 3000L

        val migrated = stateManager.getCurrentTempDeltaState(widgetId, WeatherSource.NWS)

        assertNotNull(migrated)
        assertEquals(WeatherSource.NWS.id, migrated?.sourceId)
        verify { editor.putFloat("widget_current_temp_delta_${widgetId}_${WeatherSource.NWS.id}", -4f) }
        verify { editor.remove("widget_current_temp_delta_$widgetId") }
        verify { editor.remove("widget_current_temp_delta_observed_$widgetId") }
        verify { editor.remove("widget_current_temp_delta_fetched_at_$widgetId") }
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
        assertEquals(2L, ZoomLevel.NARROW.forwardHours)
        assertEquals(2, ZoomLevel.NARROW.navJump)
        assertEquals(1, ZoomLevel.NARROW.labelInterval)
        assertEquals(0, ZoomLevel.NARROW.precipSmoothIterations)
    }

    @Test
    fun `shouldRefreshMissingActuals respects cooldown`() {
        val widgetId = 5
        val source = "NWS"
        val now = System.currentTimeMillis()
        every { prefs.getLong(any(), any()) } returns now

        val result = stateManager.shouldRefreshMissingActuals(widgetId, source, 5_000L)

        assertFalse(result)
    }

    @Test
    fun `shouldRefreshMissingActuals returns true after cooldown expires`() {
        val widgetId = 7
        val source = "WEATHER_API"
        val past = System.currentTimeMillis() - 10_000L
        every { prefs.getLong(any(), any()) } returns past

        val result = stateManager.shouldRefreshMissingActuals(widgetId, source, 5_000L)

        assertTrue(result)
    }

    @Test
    fun `markMissingActualsRefreshRequested writes timestamp`() {
        val widgetId = 3
        val source = "OPEN_METEO"

        stateManager.markMissingActualsRefreshRequested(widgetId, source)

        verify { editor.putLong("widget_missing_data_refresh_${widgetId}_${source}_actuals", any()) }
    }
}
