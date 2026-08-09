package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.ui.ConfigActivity
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

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
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun `history view center is frozen at the nav anchor across passing time`() {
        val w = testWidgetId
        // Far enough back that the WIDE window (+-12h) excludes the now point -> history view.
        stateManager.setHourlyOffset(w, -48)

        val t1 = java.time.LocalDateTime.of(2026, 6, 16, 10, 0)
        val t2 = t1.plusHours(3)
        val c1 = stateManager.resolveHourlyCenterTime(w, t1, ZoomStage.WIDE.window())
        val c2 = stateManager.resolveHourlyCenterTime(w, t2, ZoomStage.WIDE.window())

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
        val c1 = stateManager.resolveHourlyCenterTime(w, t1, ZoomStage.WIDE.window())
        val c2 = stateManager.resolveHourlyCenterTime(w, t2, ZoomStage.WIDE.window())

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
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(w))
    }

@Test
    fun `togglePrecipitationMode from DAILY to PRECIPITATION resets offset and zoom`() {
        stateManager.setDateOffset(testWidgetId, 3)

        val newMode = stateManager.togglePrecipitationMode(testWidgetId)

        assertEquals(ViewMode.PRECIPITATION, newMode)
        assertEquals(0, stateManager.getHourlyOffset(testWidgetId))
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun `getZoomLevel defaults to WIDE`() {
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun `cycleZoomLevel toggles WIDE to NARROW`() {
        val result = stateManager.cycleZoomLevel(testWidgetId)

        assertEquals(ZoomStage.NARROW, result)
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun `cycleZoomLevel advances NARROW to THREE_DAY then back to WIDE`() {
        // 3-state cycle: WIDE -> NARROW -> THREE_DAY -> WIDE.
        stateManager.cycleZoomLevel(testWidgetId) // -> NARROW

        val second = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.THREE_DAY, second)
        assertEquals(ZoomStage.THREE_DAY, stateManager.getZoomStage(testWidgetId))

        val third = stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(ZoomStage.WIDE, third)
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun `getNavJump returns zoom-appropriate value`() {
        assertEquals(6, stateManager.getNavJump(testWidgetId))

        stateManager.cycleZoomLevel(testWidgetId)
        assertEquals(1, stateManager.getNavJump(testWidgetId))
    }

    @Test
    fun `navigateHourlyRight uses zoom-aware nav jump`() {
        stateManager.cycleZoomLevel(testWidgetId)

        val result = stateManager.navigateHourlyRight(testWidgetId)

        assertEquals(1, result)
    }

    @Test
    fun `navigateHourlyLeft uses zoom-aware nav jump`() {
        stateManager.setHourlyOffset(testWidgetId, 6)
        stateManager.cycleZoomLevel(testWidgetId)

        val result = stateManager.navigateHourlyLeft(testWidgetId)

        assertEquals(5, result)
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
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun `togglePrecipitationMode to DAILY resets zoom to WIDE`() {
        stateManager.togglePrecipitationMode(testWidgetId)

        stateManager.toggleViewMode(testWidgetId)

        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun `clearWidgetState removes zoom level`() {
        stateManager.cycleZoomLevel(testWidgetId)

        stateManager.clearWidgetState(testWidgetId)

        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
    }

    @Test
    fun `toggleDisplaySource cycles through visible sources in order`() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.SILURIAN, WeatherSource.OPEN_METEO))

        val first = stateManager.getCurrentDisplaySource(testWidgetId)
        val second = stateManager.toggleDisplaySource(testWidgetId)
        val third = stateManager.toggleDisplaySource(testWidgetId)
        val fourth = stateManager.toggleDisplaySource(testWidgetId)

        assertEquals(WeatherSource.NWS, first)
        assertEquals(WeatherSource.SILURIAN, second)
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
    fun `setup source change preserves surviving widget selections by source identity`() {
        val secondWidget = 2
        val thirdWidget = 3
        stateManager.setVisibleSourcesOrder(
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
            ),
        )
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)
        stateManager.setCurrentDisplaySource(secondWidget, WeatherSource.SILURIAN)

        val changed =
            stateManager.setVisibleSourcesOrderForSetup(
                sources =
                    listOf(
                        WeatherSource.OPEN_METEO,
                        WeatherSource.SILURIAN,
                        WeatherSource.WEATHER_API,
                    ),
                widgetIds = intArrayOf(testWidgetId, secondWidget, thirdWidget),
            )

        assertTrue(changed)
        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
        assertEquals(WeatherSource.SILURIAN, stateManager.getCurrentDisplaySource(secondWidget))
        assertEquals(
            "A widget displaying removed NWS must move to the first surviving source",
            WeatherSource.OPEN_METEO,
            stateManager.getCurrentDisplaySource(thirdWidget),
        )
    }

    @Test
    fun `setup source no-op does not reset widget selection`() {
        val order = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        stateManager.setVisibleSourcesOrder(order)
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)

        val changed =
            stateManager.setVisibleSourcesOrderForSetup(
                sources = order,
                widgetIds = intArrayOf(testWidgetId),
            )

        assertFalse(changed)
        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
    }

    @Test
    fun `regular source reorder preserves selected source identity`() {
        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
        )
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)

        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.SILURIAN, WeatherSource.NWS, WeatherSource.OPEN_METEO),
        )

        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
    }

    @Test
    fun `source removal moves removed selection to first survivor`() {
        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
        )
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)

        stateManager.setVisibleSourcesOrder(
            listOf(WeatherSource.SILURIAN, WeatherSource.NWS),
        )

        assertEquals(WeatherSource.SILURIAN, stateManager.getCurrentDisplaySource(testWidgetId))
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
    fun `ZoomWindow enum has correct parameters`() {
        assertEquals(12L, ZoomStage.WIDE.window().backHours)
        assertEquals(12L, ZoomStage.WIDE.window().forwardHours)
        assertEquals(6, ZoomStage.WIDE.window().navJump)
        assertEquals(4, ZoomStage.WIDE.window().labelInterval)
        assertEquals(3, ZoomStage.WIDE.window().smoothIterations)

        // The default NARROW span is 5h, split back-heavy.
        assertEquals(3L, ZoomStage.NARROW.window().backHours)
        assertEquals(2L, ZoomStage.NARROW.window().forwardHours)
        assertEquals(1, ZoomStage.NARROW.window().navJump)
        assertEquals(1, ZoomStage.NARROW.window().labelInterval)
        assertEquals(1, ZoomStage.NARROW.window().smoothIterations)
    }

    @Test
    fun `narrow zoom span defaults to five hours`() {
        assertEquals(5, stateManager.getNarrowZoomSpanHours())

        val w = 71
        stateManager.setZoomLevel(w, ZoomStage.NARROW)
        assertEquals(3L, stateManager.getZoomWindow(w).backHours)
        assertEquals(2L, stateManager.getZoomWindow(w).forwardHours)
        assertEquals(1, stateManager.getNavJump(w))
    }

    @Test
    fun `narrow zoom span setter clamps to the four to eight range`() {
        stateManager.setNarrowZoomSpanHours(3)
        assertEquals(4, stateManager.getNarrowZoomSpanHours())

        stateManager.setNarrowZoomSpanHours(9)
        assertEquals(8, stateManager.getNarrowZoomSpanHours())

        stateManager.setNarrowZoomSpanHours(7)
        assertEquals(7, stateManager.getNarrowZoomSpanHours())
    }

    @Test
    fun `changing the span setting retunes zoom window and nav jump without restart`() {
        val w = 72
        stateManager.setZoomLevel(w, ZoomStage.NARROW)

        stateManager.setNarrowZoomSpanHours(5)
        assertEquals(5L, stateManager.getZoomWindow(w).totalSpanHours)
        assertEquals(1, stateManager.getNavJump(w))

        stateManager.setNarrowZoomSpanHours(7)
        assertEquals(7L, stateManager.getZoomWindow(w).totalSpanHours)
        assertEquals("7h span scrolls 2h per tap", 2, stateManager.getNavJump(w))
    }

    @Test
    fun `hourly navigation steps by the span-derived nav jump`() {
        // The persisted step must follow the setting end-to-end through SharedPreferences, not just
        // the resolved window: this is what the arrow buttons actually move by.
        val w = 73
        stateManager.setZoomLevel(w, ZoomStage.NARROW)
        stateManager.setHourlyOffset(w, 0)

        stateManager.setNarrowZoomSpanHours(6)
        assertEquals(1, stateManager.navigateHourlyRight(w))

        stateManager.setHourlyOffset(w, 0)
        stateManager.setNarrowZoomSpanHours(8)
        assertEquals(2, stateManager.navigateHourlyRight(w))
        assertEquals(0, stateManager.navigateHourlyLeft(w))
    }

    @Test
    fun `span setting does not affect wide or three day zoom`() {
        val w = 74
        stateManager.setNarrowZoomSpanHours(8)

        stateManager.setZoomLevel(w, ZoomStage.WIDE)
        assertEquals(24L, stateManager.getZoomWindow(w).totalSpanHours)
        assertEquals(6, stateManager.getNavJump(w))

        stateManager.setZoomLevel(w, ZoomStage.THREE_DAY)
        assertEquals(72L, stateManager.getZoomWindow(w).totalSpanHours)
        assertEquals(12, stateManager.getNavJump(w))
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
    fun `getVisibleSourcesOrder migrates existing order to append silurian and remove deprecated sources`() {
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
            .putString("visible_sources_order", "NWS,VISUAL_CROSSING,WEATHER_API,OPEN_METEO")
            .apply()

        val sources = migrationManager.getVisibleSourcesOrder()

        assertEquals(
            listOf(
                WeatherSource.NWS,
                WeatherSource.WEATHER_API,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
            ),
            sources
        )
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `getVisibleSourcesOrder strips open weather map and visual crossing`() {
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
            .putString("visible_sources_order", "NWS,OPEN_WEATHER_MAP,VISUAL_CROSSING,OPEN_METEO,SILURIAN")
            .apply()

        val sources = migrationManager.getVisibleSourcesOrder()

        assertEquals(
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
            ),
            sources
        )
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `getVisibleSourcesOrder repairs deprecated-only order to debug defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrationPrefs = context.getSharedPreferences("deprecated_only_test_prefs", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        WidgetStateManager.setPrefsNameOverrideForTesting("deprecated_only_test_prefs")
        val migrationManager = WidgetStateManager(context)
        migrationPrefs.edit()
            .putBoolean("api_pref_migrated", true)
            .putString("visible_sources_order", "VISUAL_CROSSING,OPEN_WEATHER_MAP")
            .commit()

        assertEquals(
            listOf(
                WeatherSource.NWS,
                WeatherSource.OPEN_METEO,
                WeatherSource.SILURIAN,
                WeatherSource.TOMORROW_IO,
            ),
            migrationManager.getVisibleSourcesOrder(),
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
    fun `getCurrentDisplaySource migrates legacy boolean toggle without crashing`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val togglePrefs = context.getSharedPreferences("legacy_toggle_test_prefs", Context.MODE_PRIVATE)
        togglePrefs.edit().clear().commit()
        WidgetStateManager.setPrefsNameOverrideForTesting("legacy_toggle_test_prefs")
        val toggleManager = WidgetStateManager(context)
        toggleManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        togglePrefs.edit()
            .putBoolean("widget_display_source_$testWidgetId", true)
            .putBoolean("widget_display_source_2", false)
            .commit()

        val trueSource = toggleManager.getCurrentDisplaySource(testWidgetId)
        val falseSource = toggleManager.getCurrentDisplaySource(2)

        assertEquals(WeatherSource.OPEN_METEO, trueSource)
        assertEquals(WeatherSource.NWS, falseSource)
        assertEquals(WeatherSource.OPEN_METEO.id, togglePrefs.all["widget_display_source_$testWidgetId"])
        assertEquals(WeatherSource.NWS.id, togglePrefs.all["widget_display_source_2"])
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `getCurrentDisplaySource migrates integer step to stable source id`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val togglePrefs = context.getSharedPreferences("integer_toggle_test_prefs", Context.MODE_PRIVATE)
        togglePrefs.edit().clear().commit()
        WidgetStateManager.setPrefsNameOverrideForTesting("integer_toggle_test_prefs")
        val toggleManager = WidgetStateManager(context)
        toggleManager.setVisibleSourcesOrder(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
        )
        togglePrefs.edit().putInt("widget_display_source_$testWidgetId", 5).commit()

        val source = toggleManager.getCurrentDisplaySource(testWidgetId)

        assertEquals(WeatherSource.SILURIAN, source)
        assertEquals(WeatherSource.SILURIAN.id, togglePrefs.all["widget_display_source_$testWidgetId"])
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `getCurrentDisplaySource normalizes unknown stored type to first source`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val togglePrefs = context.getSharedPreferences("unknown_toggle_test_prefs", Context.MODE_PRIVATE)
        togglePrefs.edit().clear().commit()
        WidgetStateManager.setPrefsNameOverrideForTesting("unknown_toggle_test_prefs")
        val toggleManager = WidgetStateManager(context)
        toggleManager.setVisibleSourcesOrder(listOf(WeatherSource.OPEN_METEO, WeatherSource.NWS))
        togglePrefs.edit()
            .putStringSet("widget_display_source_$testWidgetId", setOf(WeatherSource.NWS.id))
            .commit()

        val source = toggleManager.getCurrentDisplaySource(testWidgetId)

        assertEquals(WeatherSource.OPEN_METEO, source)
        assertEquals(WeatherSource.OPEN_METEO.id, togglePrefs.all["widget_display_source_$testWidgetId"])
        WidgetStateManager.setPrefsNameOverrideForTesting("test_widget_state_prefs")
    }

    @Test
    fun `deprecated selected source migrates to first surviving source`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val migrationPrefs = context.getSharedPreferences("deprecated_selection_test_prefs", Context.MODE_PRIVATE)
        migrationPrefs.edit().clear().commit()
        WidgetStateManager.setPrefsNameOverrideForTesting("deprecated_selection_test_prefs")
        val migrationManager = WidgetStateManager(context)
        migrationPrefs.edit()
            .putBoolean("api_pref_migrated", true)
            .putBoolean("silurian_migration_done_v2", true)
            .putString("visible_sources_order", "VISUAL_CROSSING,NWS,OPEN_METEO")
            .putString("widget_display_source_$testWidgetId", WeatherSource.VISUAL_CROSSING.id)
            .commit()

        val source = migrationManager.getCurrentDisplaySource(testWidgetId)

        assertEquals(WeatherSource.NWS, source)
        assertEquals(WeatherSource.NWS.id, migrationPrefs.all["widget_display_source_$testWidgetId"])
        assertEquals(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO),
            migrationManager.getVisibleSourcesOrder(),
        )
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

    @Test
    fun `clearWidgetState removes every target widget key and preserves other widget and global state`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val otherWidgetId = 12
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        stateManager.setUseCelsius(true)
        stateManager.setViewMode(testWidgetId, ViewMode.TEMPERATURE)
        stateManager.setHourlyOffset(testWidgetId, -48)
        stateManager.setTransientMessage(testWidgetId, "stale", Long.MAX_VALUE)
        stateManager.markMissingDataRefreshRequested(testWidgetId, WeatherSource.NWS.id, "hourly_gaps")
        stateManager.setWidgetLocations(intArrayOf(testWidgetId), 37.42, -122.08)
        stateManager.setViewMode(otherWidgetId, ViewMode.PRECIPITATION)
        stateManager.setTransientMessage(otherWidgetId, "keep", Long.MAX_VALUE)
        stateManager.markMissingDataRefreshRequested(otherWidgetId, WeatherSource.NWS.id, "hourly_gaps")
        stateManager.setWidgetLocations(intArrayOf(otherWidgetId), 40.0, -75.0)

        stateManager.clearWidgetState(testWidgetId)

        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertNull(stateManager.getActiveTransientMessage(testWidgetId))
        assertTrue(stateManager.shouldRefreshMissingData(testWidgetId, WeatherSource.NWS.id, "hourly_gaps", Long.MAX_VALUE))
        assertNull(stateManager.getStoredWidgetLocation(testWidgetId))

        assertEquals(ViewMode.PRECIPITATION, stateManager.getViewMode(otherWidgetId))
        assertEquals("keep", stateManager.getActiveTransientMessage(otherWidgetId))
        assertFalse(stateManager.shouldRefreshMissingData(otherWidgetId, WeatherSource.NWS.id, "hourly_gaps", Long.MAX_VALUE))
        assertEquals(40.0, stateManager.getStoredWidgetLocation(otherWidgetId)?.first ?: 0.0, 0.001)

        assertTrue(stateManager.useCelsius())
        assertEquals(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO),
            stateManager.getVisibleSourcesOrder(),
        )

        val widgetPrefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        assertFalse(widgetPrefs.contains("${ConfigActivity.KEY_LAT_PREFIX}$testWidgetId"))
        assertFalse(widgetPrefs.contains("${ConfigActivity.KEY_LON_PREFIX}$testWidgetId"))
    }

    @Test
    fun `view mode and zoom migrate ordinal values to stable names`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SharedPreferencesUtil.getPrefs(context, "test_widget_state_prefs")
        prefs.edit()
            .putInt("widget_view_mode_$testWidgetId", ViewMode.CLOUD_COVER.ordinal)
            .putInt("widget_zoom_level_$testWidgetId", ZoomStage.NARROW.ordinal)
            .commit()

        assertEquals(ViewMode.CLOUD_COVER, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomStage.NARROW, stateManager.getZoomStage(testWidgetId))
        assertEquals(ViewMode.CLOUD_COVER.name, prefs.all["widget_view_mode_$testWidgetId"])
        assertEquals(ZoomStage.NARROW.name, prefs.all["widget_zoom_level_$testWidgetId"])
    }

    @Test
    fun `unknown persisted view mode and zoom normalize to defaults`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SharedPreferencesUtil.getPrefs(context, "test_widget_state_prefs")
        prefs.edit()
            .putString("widget_view_mode_$testWidgetId", "FUTURE_MODE")
            .putString("widget_zoom_level_$testWidgetId", "FUTURE_ZOOM")
            .commit()

        assertEquals(ViewMode.DAILY, stateManager.getViewMode(testWidgetId))
        assertEquals(ZoomStage.WIDE, stateManager.getZoomStage(testWidgetId))
        assertEquals(ViewMode.DAILY.name, prefs.all["widget_view_mode_$testWidgetId"])
        assertEquals(ZoomStage.WIDE.name, prefs.all["widget_zoom_level_$testWidgetId"])
    }

    @Test
    fun `fetch cooldown allows retry after wall clock rollback and at exact boundary`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SharedPreferencesUtil.getPrefs(context, "test_widget_state_prefs")
        val zone = ZoneId.of("UTC")
        val initial = WidgetFetchStateStore(prefs, Clock.fixed(Instant.ofEpochMilli(10_000L), zone))
        initial.markMissingDataRefreshRequested(testWidgetId, "NWS", "clock")

        val rolledBack = WidgetFetchStateStore(prefs, Clock.fixed(Instant.ofEpochMilli(9_000L), zone))
        val exactBoundary = WidgetFetchStateStore(prefs, Clock.fixed(Instant.ofEpochMilli(15_000L), zone))

        assertTrue(rolledBack.shouldRefreshMissingData(testWidgetId, "NWS", "clock", 5_000L))
        assertTrue(exactBoundary.shouldRefreshMissingData(testWidgetId, "NWS", "clock", 5_000L))
    }

    @Test
    fun `transient message expires at exact boundary`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SharedPreferencesUtil.getPrefs(context, "test_widget_state_prefs")
        val store = WidgetPresentationStateStore(prefs)
        store.setTransientMessage(testWidgetId, "refresh complete", 10_000L)

        assertEquals("refresh complete", store.activeTransientMessage(testWidgetId, 9_999L))
        assertNull(store.activeTransientMessage(testWidgetId, 10_000L))
        assertFalse(prefs.contains("widget_transient_msg_$testWidgetId"))
        assertFalse(prefs.contains("widget_transient_msg_expires_$testWidgetId"))
    }

    @Test
    fun `hourly anchor preserves elapsed hours across daylight saving transition`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = SharedPreferencesUtil.getPrefs(context, "test_widget_state_prefs")
        val zone = ZoneId.of("America/Los_Angeles")
        var activeZone = zone
        val clock = Clock.fixed(Instant.parse("2026-03-08T09:30:00Z"), zone)
        val store = WidgetPresentationStateStore(prefs, clock = clock) { activeZone }
        store.setHourlyOffset(testWidgetId, 24)

        val center = store.resolveHourlyCenterTime(
            widgetId = testWidgetId,
            now = LocalDateTime.of(2026, 3, 8, 1, 30),
            zoom = ZoomStage.NARROW.window(),
        )

        assertEquals(LocalDateTime.of(2026, 3, 9, 2, 30), center)

        activeZone = ZoneId.of("America/New_York")
        val afterZoneChange = store.resolveHourlyCenterTime(
            widgetId = testWidgetId,
            now = LocalDateTime.of(2026, 3, 8, 4, 30),
            zoom = ZoomStage.NARROW.window(),
        )
        assertEquals(LocalDateTime.of(2026, 3, 9, 5, 30), afterZoneChange)
    }
}
