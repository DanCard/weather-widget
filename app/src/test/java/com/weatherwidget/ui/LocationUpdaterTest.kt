package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.WeatherWidgetProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAppWidgetManager

@Category(LongDuration::class)
class LocationUpdaterTest : RobolectricTest() {

    private lateinit var context: Context
    private lateinit var shadowAppWidgetManager: ShadowAppWidgetManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val appWidgetManager = AppWidgetManager.getInstance(context)
        shadowAppWidgetManager = shadowOf(appWidgetManager)

        // Clear prefs before each test
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit().clear().commit()
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit().clear().commit()
    }

    private fun bindWidget(widgetId: Int) {
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)
    }






    @Test
    fun `describeCurrentLocation says so when there is no location`() {
        bindWidget(206)

        val label = LocationUpdater.describeCurrentLocation(context)

        // Used to read "Default Location: 37.4220, -122.0841" -- coordinates the user never chose.
        assertTrue("expected no-location label in: $label", label.contains("No location set"))
        assertFalse("must not format a coordinate: $label", label.contains("37.42"))
    }

    /**
     * Settings must agree with the widget. With no active and no widget location the widget paints
     * "No location — tap to set"; this label used to reach past that into `historical_pois` and
     * announce "Default Location: Mountain View, California (37.4220, -122.0841)" — a coordinate the
     * app is not using, under the name of a concept that no longer exists. The POI list is still read
     * for *names* elsewhere, which is why seeding one here is not enough to make a location.
     */
    @Test
    fun `a saved place name is not a location`() {
        bindWidget(207)
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit()
            .putString("historical_pois", "Mountain View, California|37.4220|-122.0841")
            .commit()

        val label = LocationUpdater.describeCurrentLocation(context)

        assertTrue("expected no-location label in: $label", label.contains("No location set"))
        assertFalse("must not resurrect the POI coordinate: $label", label.contains("37.42"))
        assertFalse("must not name a place we are not using: $label", label.contains("Mountain View"))
    }


    @Test
    fun `describeCurrentLocation shows stored POI name next to widget coordinates`() {
        val widgetId = 210
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", 37.4220f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", -122.0841f)
            .commit()
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit()
            .putString("historical_pois", "Mountain View, California|37.4220|-122.0841")
            .commit()

        val label = LocationUpdater.describeCurrentLocation(context)

        assertTrue("expected friendly name in: $label", label.contains("Mountain View, California"))
        assertTrue("expected coordinates in: $label", label.contains("37.42"))
    }

    @Test
    fun `describeCurrentLocation without a known name still shows coordinates`() {
        val widgetId = 211
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", 40.7128f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", -74.0060f)
            .commit()

        val label = LocationUpdater.describeCurrentLocation(context)

        assertTrue("expected coordinates in: $label", label.contains("40.71"))
        assertFalse("unexpected parenthesised name in: $label", label.contains("("))
    }


    /**
     * A detected move takes effect at once. This replaces two tests that asserted the opposite —
     * that a candidate was held pending, and that a separate promotion step applied it — because the
     * handoff policy those described was removed 2026-08-28
     * (plans/260828-remove-the-location-handoff-policy.md).
     */
    @Test
    fun `a follow-device move replaces the active widget coordinates immediately`() {
        val widgetId = 204
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", 37.4168f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", -122.0890f)
            .commit()

        val applied = LocationUpdater.applyFollowDeviceLocation(
            context = context,
            lat = 37.3774,
            lon = -122.0749,
            label = "Away",
            enqueueRefresh = false,
            ids = intArrayOf(widgetId),
        )

        assertTrue(applied)
        assertEquals(37.3774f, prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", Float.NaN), 0.0001f)
        assertEquals(-122.0749f, prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", Float.NaN), 0.0001f)
        // The canonical record moves too, not just the per-widget compatibility copies.
        assertEquals(37.3774, ActiveLocationResolver.current(context)!!.first, 1e-5)
    }
}
