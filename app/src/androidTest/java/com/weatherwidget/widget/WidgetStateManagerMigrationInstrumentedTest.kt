package com.weatherwidget.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ConfigActivity
import com.weatherwidget.util.SharedPreferencesUtil
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-side coverage for SharedPreferences type compatibility and provider deletion cleanup.
 * Uses the instrumentation-only state file and synthetic widget IDs, leaving launcher widgets intact.
 */
@RunWith(AndroidJUnit4::class)
class WidgetStateManagerMigrationInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val widgetId = 9126
    private val otherWidgetId = 9127

    private lateinit var statePrefs: SharedPreferences
    private lateinit var locationPrefs: SharedPreferences

    @Before
    fun setUp() {
        WidgetStateManager.setPrefsNameOverrideForTesting(WidgetStateManager.DEFAULT_TEST_PREFS_NAME)
        statePrefs = SharedPreferencesUtil.getPrefs(context, WidgetStateManager.DEFAULT_TEST_PREFS_NAME)
        locationPrefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        statePrefs.edit().clear().commit()
        clearSyntheticLocations()
    }

    @After
    fun tearDown() {
        statePrefs.edit().clear().commit()
        clearSyntheticLocations()
    }

    @Test
    fun legacyBooleanDisplaySourceMigratesToStableId() {
        statePrefs.edit()
            .putBoolean("api_pref_migrated", true)
            .putBoolean("silurian_migration_done_v2", true)
            .putBoolean("hide_deprecated_sources_migration_done_v6", true)
            .putString("visible_sources_order", "NWS,OPEN_METEO")
            .putBoolean("widget_display_source_$widgetId", true)
            .commit()

        val source = WidgetStateManager(context).getCurrentDisplaySource(widgetId)

        assertEquals(WeatherSource.OPEN_METEO, source)
        assertEquals(WeatherSource.OPEN_METEO.id, statePrefs.all["widget_display_source_$widgetId"])
    }

    @Test
    fun sourceOrderMutationPreservesSurvivingSelectionByIdentity() {
        val manager = WidgetStateManager(context)
        manager.setVisibleSourcesOrderForSetup(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API),
            intArrayOf(widgetId),
        )
        manager.setCurrentDisplaySource(widgetId, WeatherSource.OPEN_METEO)

        manager.setVisibleSourcesOrderForSetup(
            listOf(WeatherSource.WEATHER_API, WeatherSource.OPEN_METEO, WeatherSource.NWS),
            intArrayOf(widgetId),
        )

        assertEquals(WeatherSource.OPEN_METEO, manager.getCurrentDisplaySource(widgetId))
        assertEquals(WeatherSource.OPEN_METEO.id, statePrefs.all["widget_display_source_$widgetId"])
    }

    @Test
    fun presentationTransitionsRemainCoherentAcrossModesAndNavigation() {
        val manager = WidgetStateManager(context)

        assertEquals(ViewMode.TEMPERATURE, manager.toggleViewMode(widgetId))
        assertEquals(0, manager.getHourlyOffset(widgetId))
        assertEquals(ZoomStage.WIDE, manager.getZoomStage(widgetId))

        manager.setZoomLevel(widgetId, ZoomStage.NARROW)
        assertEquals(ZoomStage.NARROW.window().navJump, manager.navigateHourlyRight(widgetId))
        assertEquals(ViewMode.PRECIPITATION, manager.togglePrecipitationMode(widgetId))
        assertEquals(ZoomStage.NARROW.window().navJump, manager.getHourlyOffset(widgetId))
        assertEquals(ZoomStage.NARROW, manager.getZoomStage(widgetId))

        assertEquals(ViewMode.DAILY, manager.togglePrecipitationMode(widgetId))
        assertEquals(ZoomStage.WIDE, manager.getZoomStage(widgetId))
        assertEquals(ViewMode.CLOUD_COVER, manager.toggleCloudCoverMode(widgetId))
        assertEquals(0, manager.getHourlyOffset(widgetId))
        assertEquals(ViewMode.TEMPERATURE, manager.toggleCloudCoverMode(widgetId))
    }

    /*
      Test covers the real launcher lifecycle when one widget instance is removed.

      Android calls WeatherWidgetProvider.onDeleted(..., widgetId). The deleted widget owns
      view/zoom/navigation state, transient messages, refresh cooldowns, current-temperature delta
      state, selected source, and its per-widget coordinates. Those values span two preference
      files. If any survive, they leak indefinitely and can affect a later widget if Android reuses
      that widget ID—for example, a newly added widget could inherit an old location or remain
      inside an old missing-data cooldown.

      The test uses synthetic widget ID 9126, seeds all representative state, calls the actual
      provider deletion callback, and verifies:
      - every state item for 9126 is gone;
      - its per-widget coordinates are gone;
      - another widget’s state and coordinates remain untouched;
      - global settings such as units/source ordering are not cleared.
    */
    @Test
    fun providerDeletionClearsOnlyDeletedWidgetStateIncludingPerWidgetLocation() {
        val manager = WidgetStateManager(context)
        manager.setViewMode(widgetId, ViewMode.PRECIPITATION)
        manager.setTransientMessage(widgetId, "remove", Long.MAX_VALUE)
        manager.markMissingDataRefreshRequested(widgetId, WeatherSource.NWS.id, "hourly_gaps")
        manager.setWidgetLocations(intArrayOf(widgetId), 37.42, -122.08)

        manager.setViewMode(otherWidgetId, ViewMode.CLOUD_COVER)
        manager.setTransientMessage(otherWidgetId, "keep", Long.MAX_VALUE)
        manager.setWidgetLocations(intArrayOf(otherWidgetId), 40.0, -105.0)

        WeatherWidgetProvider().onDeleted(context, intArrayOf(widgetId))

        assertEquals(ViewMode.DAILY, manager.getViewMode(widgetId))
        assertNull(manager.getActiveTransientMessage(widgetId))
        assertTrue(manager.shouldRefreshMissingData(widgetId, WeatherSource.NWS.id, "hourly_gaps", Long.MAX_VALUE))
        assertNull(manager.getStoredWidgetLocation(widgetId))
        assertFalse(statePrefs.all.keys.any { key -> key.contains("_${widgetId}_") || key.endsWith("_$widgetId") })

        assertEquals(ViewMode.CLOUD_COVER, manager.getViewMode(otherWidgetId))
        assertEquals("keep", manager.getActiveTransientMessage(otherWidgetId))
        assertEquals(40.0, manager.getStoredWidgetLocation(otherWidgetId)?.first ?: 0.0, 0.001)
    }

    private fun clearSyntheticLocations() {
        locationPrefs.edit()
            .remove("${ConfigActivity.KEY_LAT_PREFIX}$widgetId")
            .remove("${ConfigActivity.KEY_LON_PREFIX}$widgetId")
            .remove("${ConfigActivity.KEY_LAT_PREFIX}$otherWidgetId")
            .remove("${ConfigActivity.KEY_LON_PREFIX}$otherWidgetId")
            .commit()
    }
}
