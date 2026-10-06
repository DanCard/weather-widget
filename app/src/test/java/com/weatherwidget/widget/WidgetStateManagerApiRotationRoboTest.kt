package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
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
class WidgetStateManagerApiRotationRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager
    private val testWidgetId = 777

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        stateManager = WidgetStateManager(context)
        stateManager.clearWidgetState(testWidgetId)
    }

    @After
    fun cleanup() {
        stateManager.clearWidgetState(testWidgetId)
    }

    // NWS outside its coverage is unavailable, not removed (2026-09-29). These run the real
    // WidgetStateManager + WeatherSourcePreferences + ActiveLocationResolver together: the location
    // the filter reads is the one ConfigActivity/GpsResampler persist.
    private val mountainView = 37.4166 to -122.0889
    private val warsaw = 52.2334 to 21.0711

    private fun moveTo(site: Pair<Double, Double>) = ActiveLocationResolver.persist(context, site.first, site.second)

    @Test
    fun outsideCoverage_nwsIsFilteredButStaysEnabled() {
        val enabled = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN)
        stateManager.setVisibleSourcesOrder(enabled)
        moveTo(warsaw)

        assertEquals(listOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN), stateManager.getVisibleSourcesOrder())
        assertEquals(enabled, stateManager.getEnabledSourcesOrder())
        assertEquals(true, stateManager.isSourceUnavailableHere(WeatherSource.NWS))
        assertEquals(false, stateManager.isSourceUnavailableHere(WeatherSource.OPEN_METEO))
    }

    @Test
    fun widgetShowingNws_fallsBackAbroad_andShowsNwsAgainBackHome() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        moveTo(mountainView)
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.NWS)

        moveTo(warsaw)
        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))
        // Reading it again must not have persisted the fallback.
        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))

        moveTo(mountainView)
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }

    @Test
    fun toggleAbroad_cyclesOnlyUsableSources() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN))
        moveTo(warsaw)
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.OPEN_METEO)

        assertEquals(WeatherSource.SILURIAN, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.OPEN_METEO, stateManager.toggleDisplaySource(testWidgetId))
    }

    @Test
    fun settingsEditAbroad_keepsTheWidgetsStoredNwsChoice() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO))
        moveTo(mountainView)
        stateManager.setCurrentDisplaySource(testWidgetId, WeatherSource.NWS)

        moveTo(warsaw)
        // Reorder in Settings while abroad: selections are preserved against the ENABLED list.
        stateManager.setVisibleSourcesOrderForSetup(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
            intArrayOf(testWidgetId),
        )

        moveTo(mountainView)
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }

    @Test
    fun nwsReenableMigration_restoresNwsOnce_thenAnUntickSticks() {
        // The 2026-09-29 phone: NWS absent from the stored list with no reliable marker saying why.
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, "widget_state_prefs")
        prefs.edit()
            // Tests a different migration; the debug-only Google one (2026-10-06) has already run.
            .putBoolean("google_weather_debug_enabled_migration_done_v1", true)
            .putString("visible_sources_order", "OPEN_METEO,SILURIAN")
            .putBoolean("nws_auto_retired", true)
            .remove("nws_reenabled_migration_done_v1")
            .commit()

        assertEquals(
            listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
            WidgetStateManager(context).getEnabledSourcesOrder(),
        )
        assertEquals(false, prefs.contains("nws_auto_retired"))

        WidgetStateManager(context).setVisibleSourcesOrder(listOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN))
        assertEquals(
            listOf(WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
            WidgetStateManager(context).getEnabledSourcesOrder(),
        )
    }

    @Test
    fun nwsOnlyListAbroad_fallsBackToOpenMeteoWithoutStoringIt() {
        stateManager.setVisibleSourcesOrder(listOf(WeatherSource.NWS))
        moveTo(warsaw)

        assertEquals(listOf(WeatherSource.OPEN_METEO), stateManager.getVisibleSourcesOrder())
        assertEquals(listOf(WeatherSource.NWS), stateManager.getEnabledSourcesOrder())
    }

    @Test
    fun toggleDisplaySource_cyclesThroughVisibleSources() {
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.WEATHER_API)
        stateManager.setVisibleSourcesOrder(visibleSources)

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))

        assertEquals(WeatherSource.OPEN_METEO, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.OPEN_METEO, stateManager.getCurrentDisplaySource(testWidgetId))

        assertEquals(WeatherSource.WEATHER_API, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.WEATHER_API, stateManager.getCurrentDisplaySource(testWidgetId))

        assertEquals(WeatherSource.NWS, stateManager.toggleDisplaySource(testWidgetId))
        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))
    }

    @Test
    fun toggleDisplaySource_withTwoSources() {
        val visibleSources = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO)
        stateManager.setVisibleSourcesOrder(visibleSources)

        assertEquals(WeatherSource.NWS, stateManager.getCurrentDisplaySource(testWidgetId))

        assertEquals(WeatherSource.OPEN_METEO, stateManager.toggleDisplaySource(testWidgetId))

        assertEquals(WeatherSource.NWS, stateManager.toggleDisplaySource(testWidgetId))
    }
}