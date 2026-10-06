package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.util.WeatherSourceOrdering
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Real [WeatherSourcePreferences] + SharedPreferences + bound widgets: enabling Google makes it
 * primary and every widget follows; the debug migration does that once and a later untick sticks;
 * builds whose defaults lack Google (release) never migrate. User's calls 2026-10-06.
 */
@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
class GoogleWeatherPrimaryIntegrationTest {
    private lateinit var context: Context
    private val widgetA = 101
    private val widgetB = 202
    private val debugDefaults = listOf(
        WeatherSource.GOOGLE_WEATHER, WeatherSource.NWS, WeatherSource.OPEN_METEO,
        WeatherSource.SILURIAN, WeatherSource.TOMORROW_IO,
    )
    private val releaseDefaults = listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN)

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, WeatherWidgetProvider::class.java)
        }
        val shadow = shadowOf(AppWidgetManager.getInstance(context))
        shadow.addBoundWidget(widgetA, info)
        shadow.addBoundWidget(widgetB, info)
    }

    private fun prefsStore() = context.getSharedPreferences("google_primary_test", Context.MODE_PRIVATE)

    private fun preferences(defaults: List<WeatherSource>) = WeatherSourcePreferences(
        context = context,
        prefs = prefsStore(),
        defaultVisibleSources = defaults,
        activeLocation = { 37.4166 to -122.0889 },
    )

    /** An existing install: a stored list without Google, widgets showing NWS and Open-Meteo. */
    private fun seedExistingInstall() {
        prefsStore().edit()
            .clear()
            .putString("visible_sources_order", "NWS,OPEN_METEO,SILURIAN")
            .putString("widget_display_source_$widgetA", "NWS")
            .putString("widget_display_source_$widgetB", "OPEN_METEO")
            // Earlier one-time migrations already ran on a real install.
            .putBoolean("silurian_migration_done_v2", true)
            .putBoolean("hide_deprecated_sources_migration_done_v6", true)
            .putBoolean("owm_position_bottom_migration_done_v1", true)
            .putBoolean("nws_reenabled_migration_done_v1", true)
            .putBoolean("api_pref_migrated", true)
            .commit()
    }

    @Test
    fun `fresh debug install shows Google on every widget`() {
        prefsStore().edit().clear().commit()
        val prefs = preferences(debugDefaults)
        assertEquals(WeatherSource.GOOGLE_WEATHER, prefs.primarySource())
        assertEquals(WeatherSource.GOOGLE_WEATHER, prefs.currentDisplaySource(widgetA))
    }

    @Test
    fun `debug migration enables Google as primary once, switches widgets, and an untick sticks`() {
        seedExistingInstall()
        val prefs = preferences(debugDefaults)

        assertEquals(
            listOf(WeatherSource.GOOGLE_WEATHER, WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN),
            prefs.enabledSources(),
        )
        assertEquals(WeatherSource.GOOGLE_WEATHER, prefs.currentDisplaySource(widgetA))
        assertEquals(WeatherSource.GOOGLE_WEATHER, prefs.currentDisplaySource(widgetB))

        // The user turns it off in Settings; the migration must not bring it back.
        val off = WeatherSourceOrdering.toggle(prefs.enabledSources().map { it.id }, WeatherSource.GOOGLE_WEATHER, false)!!
        prefs.setVisibleSources(off.map(WeatherSource::fromId))
        val reread = preferences(debugDefaults)
        assertEquals(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN), reread.enabledSources())
    }

    @Test
    fun `release defaults never migrate Google in`() {
        seedExistingInstall()
        val prefs = preferences(releaseDefaults)
        assertEquals(listOf(WeatherSource.NWS, WeatherSource.OPEN_METEO, WeatherSource.SILURIAN), prefs.enabledSources())
        assertEquals(WeatherSource.NWS, prefs.currentDisplaySource(widgetA))
    }

    @Test
    fun `enabling Google in Settings makes it primary and switches every widget`() {
        seedExistingInstall()
        val prefs = preferences(releaseDefaults)

        val on = WeatherSourceOrdering.toggle(prefs.enabledSources().map { it.id }, WeatherSource.GOOGLE_WEATHER, true)!!
        prefs.setVisibleSources(on.map(WeatherSource::fromId))

        assertEquals(WeatherSource.GOOGLE_WEATHER, prefs.primarySource())
        assertEquals(WeatherSource.GOOGLE_WEATHER, prefs.currentDisplaySource(widgetA))
        assertEquals(WeatherSource.GOOGLE_WEATHER, prefs.currentDisplaySource(widgetB))
    }

    @Test
    fun `enabling another source keeps each widget's choice and appends it last`() {
        seedExistingInstall()
        val prefs = preferences(releaseDefaults)

        val on = WeatherSourceOrdering.toggle(prefs.enabledSources().map { it.id }, WeatherSource.OPEN_WEATHER_MAP, true)!!
        prefs.setVisibleSources(on.map(WeatherSource::fromId))

        assertEquals(WeatherSource.OPEN_WEATHER_MAP, prefs.enabledSources().last())
        assertEquals(WeatherSource.NWS, prefs.currentDisplaySource(widgetA))
        assertEquals(WeatherSource.OPEN_METEO, prefs.currentDisplaySource(widgetB))
    }

    @Test
    fun `moving Google down after enabling is allowed`() {
        seedExistingInstall()
        val prefs = preferences(debugDefaults)
        val down = WeatherSourceOrdering.moveDown(prefs.enabledSources().map { it.id }, WeatherSource.GOOGLE_WEATHER)
        prefs.setVisibleSources(down.map(WeatherSource::fromId))
        assertEquals(WeatherSource.NWS, preferences(debugDefaults).primarySource())
    }
}
