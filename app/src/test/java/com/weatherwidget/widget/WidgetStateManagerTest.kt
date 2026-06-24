package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.util.SharedPreferencesUtil
import org.junit.Assert.*
import org.junit.After
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
class WidgetStateManagerTest {
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 1

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
        val prefs = context.getSharedPreferences("test_widget_state_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        stateManager = WidgetStateManager(context)
    }

    @After
    fun tearDown() {
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
    }

    @Test
    fun `toggleViewMode from DAILY to HOURLY resets offset and zoom`() {
        stateManager.cycleZoomLevel(testWidgetId)

        val newMode = stateManager.toggleViewMode(testWidgetId)

        assertEquals(ViewMode.TEMPERATURE, newMode)
        assertEquals(0, stateManager.getHourlyOffset(testWidgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun `history view center is frozen at the nav anchor across passing time`() {
        val w = testWidgetId
        // Far enough back that the WIDE window (+-12h) excludes the now point -> history view.
        stateManager.setHourlyOffset(w, -48)

        val t1 = java.time.LocalDateTime.of(2026, 6, 16, 10, 0)
        val t2 = t1.plusHours(3)
        val c1 = stateManager.resolveHourlyCenterTime(w, t1, ZoomLevel.WIDE)
        val c2 = stateManager.resolveHourlyCenterTime(w, t2, ZoomLevel.WIDE)

        // A later `now` (a periodic refresh) must NOT advance a history view.
        assertEquals(c1, c2)
    }

    @Test
    fun `live view center tracks now so the current view keeps advancing`() {
        val w = testWidgetId
        // Offset within +-12h keeps the now/fetch-dot point in the WIDE window -> live view.
        stateManager.setHourlyOffset(w, 0)

        val t1 = java.time.LocalDateTime.of(2026, 6, 16, 10, 0)
        val t2 = t1.plusHours(3)
        val c1 = stateManager.resolveHourlyCenterTime(w, t1, ZoomLevel.WIDE)
        val c2 = stateManager.resolveHourlyCenterTime(w, t2, ZoomLevel.WIDE)

        assertEquals(t1, c1)
        assertEquals(t2, c2)
        assertNotEquals(c1, c2)
    }

    @Test
    fun `toggleViewMode from PRECIPITATION to DAILY does not reset hourly offset`() {
        val w = testWidgetId
        stateManager.setViewMode(w, ViewMode.PRECIPITATION)
        stateManager.setHourlyOffset(w, 6)

        val modeBefore = stateManager.getViewMode(w)
        assertEquals(ViewMode.PRECIPITATION, modeBefore)

        val newMode = stateManager.toggleViewMode(w)

        assertEquals(ViewMode.DAILY, newMode)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(w))
    }

@Test
    fun `togglePrecipitationMode from DAILY to PRECIPITATION resets offset and zoom`() {
        stateManager.setDateOffset(testWidgetId, 3)

        val newMode = stateManager.togglePrecipitationMode(testWidgetId)

        assertEquals(ViewMode.PRECIPITATION, newMode)
        assertEquals(0, stateManager.getHourlyOffset(testWidgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun `getZoomLevel defaults to WIDE`() {
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun `cycleZoomLevel toggles WIDE to NARROW`() {
        val result = stateManager.cycleZoomLevel(testWidgetId)

        assertEquals(ZoomLevel.NARROW, result)
        assertEquals(ZoomLevel.NARROW, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun `cycleZoomLevel advances NARROW to THREE_DAY then back to WIDE`() {
        // 3-state cycle: WIDE -> NARROW -> THREE_DAY -> WIDE.
        stateManager.cycleZoomLevel(testWidgetId) // -> NARROW

        val second = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.THREE_DAY, second)
        assertEquals(ZoomLevel.THREE_DAY, stateManager.getZoomLevel(testWidgetId))

        val third = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomLevel.WIDE, third)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun `getNavJump returns zoom-appropriate value`() {
        assertEquals(6, stateManager.getNavJump(testWidgetId))

        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(2, stateManager.getNavJump(testWidgetId))
    }

    @Test
    fun `navigateHourlyRight uses zoom-aware nav jump`() {
        stateManager.cycleZoomLevel(testWidgetId)

        val result = stateManager.navigateHourlyRight(testWidgetId)

        assertEquals(2, result)
    }

    @Test
    fun `navigateHourlyLeft uses zoom-aware nav jump`() {
        stateManager.setHourlyOffset(testWidgetId, 6)
        stateManager.cycleZoomLevel(testWidgetId)

        val result = stateManager.navigateHourlyLeft(testWidgetId)

        assertEquals(4, result)
    }

    @Test
    fun `setHourlyOffset preserves day-click future offsets within range`() {
        stateManager.setHourlyOffset(testWidgetId, 129)

        assertEquals(129, stateManager.getHourlyOffset(testWidgetId))
    }

    @Test
    fun `toggleViewMode to DAILY resets zoom to WIDE`() {
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
        stateManager.cycleZoomLevel(testWidgetId)

        val newMode = stateManager.toggleViewMode(testWidgetId)

        assertEquals(ViewMode.DAILY, newMode)
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun `togglePrecipitationMode to DAILY resets zoom to WIDE`() {
        stateManager.togglePrecipitationMode(testWidgetId)

        stateManager.toggleViewMode(testWidgetId)

        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun `clearWidgetState removes zoom level`() {
        stateManager.cycleZoomLevel(testWidgetId)

        stateManager.clearWidgetState(testWidgetId)

        assertEquals(ZoomLevel.WIDE, stateManager.getZoomLevel(testWidgetId))
    }

    @Test
    fun `toggleDisplaySource cycles through visible sources in order`() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.VISUAL_CROSSING, WeatherSource.OPEN_METEO))

        val first = stateManager.getCurrentDisplaySource(testWidgetId)
        val second = stateManager.toggleDisplaySource(testWidgetId)
        val third = stateManager.toggleDisplaySource(testWidgetId)
        val fourth = stateManager.toggleDisplaySource(testWidgetId)

        assertEquals(WeatherSource.NWS, first)
        assertEquals(WeatherSource.VISUAL_CROSSING, second)
        assertEquals(WeatherSource.OPEN_METEO, third)
        assertEquals(WeatherSource.NWS, fourth)
    }

    @Test
    fun `getEffectiveVisibleSourcesOrder preserves Open-Meteo when enabled`() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))

        val sources = stateManager.getEffectiveVisibleSourcesOrder(37.42, -122.08)

        assertEquals(
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.WEATHER_API,
            ),
            sources
        )
    }

    @Test
    fun `getVisibleSourcesOrder uses tomorrow io default order on fresh install (debug build)`() {
        // Unit tests run against the debug variant (BuildConfig.DEBUG = true), where Tomorrow.io
        // IS in the default set. Release builds default to "NWS,OPEN_METEO,SILURIAN" (Tomorrow.io
        // off by default to spare its tight free-plan quota) — see DEFAULT_VISIBLE_SOURCES.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val freshPrefs = context.getSharedPreferences("fresh_test_prefs", Context.MODE_PRIVATE)
        freshPrefs.edit().clear().apply()
        WidgetStateManager.setPrefsNameOverrideForTesting("fresh_test_prefs")
        val freshManager = WidgetStateManager(context)

        val sources = freshManager.getVisibleSourcesOrder()

        assertEquals(
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
                WeatherSource.TOMORROW_IO,
            ),
            sources
        )
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `toggleDisplaySource keeps current temp delta state`() {
        val w = testWidgetId
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))
        val nwsState = CurrentTemperatureDeltaState(
            delta = -5f,
            lastObservedTemp = 83f,
            lastObservedAt = 1000L,
            updatedAtMs = 1000L,
            sourceId = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08,
        )
        stateManager.setCurrentTempDeltaState(w, WeatherSource.NWS, nwsState)

        stateManager.toggleDisplaySource(w)

        val restored = stateManager.getCurrentTempDeltaState(w, WeatherSource.NWS)
        assertNotNull(restored)
        assertEquals(-5f, restored!!.delta, 0.01f)
    }

    @Test
    fun `set and get current temp delta state is source scoped`() {
        val w = 7
        val nwsState = CurrentTemperatureDeltaState(
            delta = -5f,
            lastObservedTemp = 83f,
            lastObservedAt = 1000L,
            updatedAtMs = 1000L,
            sourceId = WeatherSource.NWS.id,
            locationLat = 37.42,
            locationLon = -122.08,
        )

        stateManager.setCurrentTempDeltaState(w, WeatherSource.NWS, nwsState)

        val restoredNws = stateManager.getCurrentTempDeltaState(w, WeatherSource.NWS)
        val restoredMeteo = stateManager.getCurrentTempDeltaState(w, WeatherSource.OPEN_METEO)

        assertNotNull(restoredNws)
        assertEquals(WeatherSource.NWS.id, restoredNws?.sourceId)
        assertEquals(-5f, restoredNws?.delta ?: 0f, 0.01f)
        assertNull(restoredMeteo)
    }

    @Test
    fun `ZoomLevel enum has correct parameters`() {
        assertEquals(12L, ZoomLevel.WIDE.backHours)
        assertEquals(12L, ZoomLevel.WIDE.forwardHours)
        assertEquals(6, ZoomLevel.WIDE.navJump)
        assertEquals(4, ZoomLevel.WIDE.labelInterval)
        assertEquals(3, ZoomLevel.WIDE.smoothIterations)

        assertEquals(2L, ZoomLevel.NARROW.backHours)
        assertEquals(2L, ZoomLevel.NARROW.forwardHours)
        assertEquals(2, ZoomLevel.NARROW.navJump)
        assertEquals(1, ZoomLevel.NARROW.labelInterval)
        assertEquals(1, ZoomLevel.NARROW.smoothIterations)
    }

    @Test
    fun `shouldRefreshMissingActuals respects cooldown`() {
        val w = 5
        val source = "NWS"
        stateManager.markMissingActualsRefreshRequested(w, source)

        val result = stateManager.shouldRefreshMissingActuals(w, source, 5_000L)

        assertFalse(result)
    }

    @Test
    fun `shouldRefreshMissingActuals returns true after cooldown expires`() {
        val w = 7
        val source = "WEATHER_API"
        stateManager.markMissingActualsRefreshRequested(w, source)
        val cooldownMs = 0L

        val result = stateManager.shouldRefreshMissingActuals(w, source, cooldownMs)

        assertTrue(result)
    }

    @Test
    fun `markMissingActualsRefreshRequested writes timestamp`() {
        val w = 3
        val source = "OPEN_METEO"

        stateManager.markMissingActualsRefreshRequested(w, source)

        assertFalse(stateManager.shouldRefreshMissingActuals(w, source, 5_000L))
    }

    @Test
    fun `getVisibleSourcesOrder migrates existing stored order to append silurian remove owm and insert visual crossing second`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrationPrefs = context.getSharedPreferences("migration_test_prefs_1", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().apply()
        WidgetStateManager.setPrefsNameOverrideForTesting("migration_test_prefs_1")
        val migrationManager = WidgetStateManager(context)

        migrationPrefs.edit()
            .putBoolean("api_pref_migrated", true)
            .putBoolean("silurian_migration_done_v2", false)
            .putBoolean("hide_open_weather_map_migration_done_v4", false)
            .putBoolean("visual_crossing_migration_done_v5", false)
            .putString("visible_sources_order", "NWS,WEATHER_API,OPEN_METEO")
            .apply()

        val sources = migrationManager.getVisibleSourcesOrder()

        assertEquals(
            listOf(
                WeatherSource.NWS,
                WeatherSource.VISUAL_CROSSING,
                WeatherSource.WEATHER_API,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
            ),
            sources
        )
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `getVisibleSourcesOrder strips open weather map and inserts visual crossing second`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrationPrefs = context.getSharedPreferences("migration_test_prefs_2", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().apply()
        WidgetStateManager.setPrefsNameOverrideForTesting("migration_test_prefs_2")
        val migrationManager = WidgetStateManager(context)

        migrationPrefs.edit()
            .putBoolean("api_pref_migrated", true)
            .putBoolean("silurian_migration_done_v2", true)
            .putBoolean("hide_open_weather_map_migration_done_v4", false)
            .putBoolean("visual_crossing_migration_done_v5", false)
            .putString("visible_sources_order", "NWS,OPEN_WEATHER_MAP,OPEN_METEO,SILURIAN")
            .apply()

        val sources = migrationManager.getVisibleSourcesOrder()

        assertEquals(
            listOf(
                WeatherSource.NWS,
                WeatherSource.VISUAL_CROSSING,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
            ),
            sources
        )
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `getCurrentDisplaySource returns second source when toggle step is 1`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val togglePrefs = context.getSharedPreferences("toggle_test_prefs", Context.MODE_PRIVATE)
        togglePrefs.edit().clear().apply()
        WidgetStateManager.setPrefsNameOverrideForTesting("toggle_test_prefs")
        val toggleManager = WidgetStateManager(context)

        toggleManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API))
        // Set toggle step to 1 (second source)
        togglePrefs.edit().putInt("widget_display_source_$testWidgetId", 1).apply()

        val source = toggleManager.getCurrentDisplaySource(testWidgetId)

        assertEquals(WeatherSource.OPEN_METEO, source)
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `get current temp delta state migrates matching legacy widget scoped state`() {
        val w = 9
        val context = ApplicationProvider.getApplicationContext<Context>()
        WidgetStateManager.setPrefsNameOverrideForTesting("delta_migration_prefs")
        val migrationPrefs = SharedPreferencesUtil.getPrefs(context, "delta_migration_prefs")
        migrationPrefs.edit().clear().apply()
        val migrationManager = WidgetStateManager(context)

        migrationPrefs.edit()
            .putFloat("widget_current_temp_delta_$w", -4f)
            .putFloat("widget_current_temp_delta_observed_$w", 82f)
            .putLong("widget_current_temp_delta_fetched_at_$w", 2000L)
            .putLong("widget_current_temp_delta_updated_at_$w", 3000L)
            .putString("widget_current_temp_delta_source_$w", WeatherSource.NWS.id)
            .putString("widget_current_temp_delta_lat_$w", "37.42")
            .putString("widget_current_temp_delta_lon_$w", "-122.08")
            .apply()

        val migrated = migrationManager.getCurrentTempDeltaState(w, WeatherSource.NWS)

        assertNotNull(migrated)
        assertEquals(WeatherSource.NWS.id, migrated?.sourceId)
        assertEquals(-4f, migrated?.delta ?: 0f, 0.01f)
        assertFalse(migrationPrefs.contains("widget_current_temp_delta_$w"))
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `isSourceErrored becomes true only after threshold consecutive failures`() {
        assertFalse(stateManager.isSourceErrored(WeatherSource.TOMORROW_IO))

        repeat(WidgetStateManager.SOURCE_FAILURE_WATERMARK_THRESHOLD - 1) {
            stateManager.recordSourceFetchFailure(WeatherSource.TOMORROW_IO)
        }
        // One short of the threshold: no watermark yet.
        assertFalse(stateManager.isSourceErrored(WeatherSource.TOMORROW_IO))

        stateManager.recordSourceFetchFailure(WeatherSource.TOMORROW_IO)
        assertTrue(stateManager.isSourceErrored(WeatherSource.TOMORROW_IO))
    }

    @Test
    fun `recordSourceFetchSuccess resets the failure count`() {
        repeat(WidgetStateManager.SOURCE_FAILURE_WATERMARK_THRESHOLD + 2) {
            stateManager.recordSourceFetchFailure(WeatherSource.TOMORROW_IO)
        }
        assertTrue(stateManager.isSourceErrored(WeatherSource.TOMORROW_IO))

        stateManager.recordSourceFetchSuccess(WeatherSource.TOMORROW_IO)

        assertEquals(0, stateManager.getSourceFailureCount(WeatherSource.TOMORROW_IO))
        assertFalse(stateManager.isSourceErrored(WeatherSource.TOMORROW_IO))
    }

    @Test
    fun `source failure counts are tracked independently per source`() {
        repeat(WidgetStateManager.SOURCE_FAILURE_WATERMARK_THRESHOLD) {
            stateManager.recordSourceFetchFailure(WeatherSource.TOMORROW_IO)
        }

        assertTrue(stateManager.isSourceErrored(WeatherSource.TOMORROW_IO))
        assertFalse(stateManager.isSourceErrored(WeatherSource.NWS))
    }

    @Test
    fun `getLastGraphRender returns null when no state stored`() {
        assertNull(stateManager.getLastGraphRender(testWidgetId))
    }

    @Test
    fun `setLastGraphRender round-trips correctly`() {
        val state = WidgetStateManager.LastGraphRenderState(
            renderMs = 123456789L,
            displayedTemp = "72.3°",
        )

        stateManager.setLastGraphRender(testWidgetId, state)
        val loaded = stateManager.getLastGraphRender(testWidgetId)

        assertNotNull(loaded)
        assertEquals(123456789L, loaded!!.renderMs)
        assertEquals("72.3°", loaded.displayedTemp)
    }

    @Test
    fun `setLastGraphRender with null temp round-trips correctly`() {
        val state = WidgetStateManager.LastGraphRenderState(
            renderMs = 99999L,
            displayedTemp = null,
        )

        stateManager.setLastGraphRender(testWidgetId, state)
        val loaded = stateManager.getLastGraphRender(testWidgetId)

        assertNotNull(loaded)
        assertEquals(99999L, loaded!!.renderMs)
        assertNull(loaded.displayedTemp)
    }

    @Test
    fun `clearWidgetState removes last graph render`() {
        val state = WidgetStateManager.LastGraphRenderState(
            renderMs = 42L,
            displayedTemp = "65°",
        )
        stateManager.setLastGraphRender(testWidgetId, state)
        assertNotNull(stateManager.getLastGraphRender(testWidgetId))

        stateManager.clearWidgetState(testWidgetId)

        assertNull(stateManager.getLastGraphRender(testWidgetId))
    }
}