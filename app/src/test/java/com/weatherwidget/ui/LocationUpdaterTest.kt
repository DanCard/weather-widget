package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.LocationChangeBanner
import com.weatherwidget.widget.WidgetStateManager
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
        ActiveLocationResolver.clear(context)
        WidgetStateManager(context).clearPendingLocationFetch()
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

    /**
     * The setup path is the user-initiated one, so it — and only it — gives feedback. Moving from a
     * site already on screen floats "Getting weather for {place}…" over that render (user's call,
     * 2026-09-28) and sets no interstitial mark: nothing should replace the render under the banner.
     */
    @Test
    fun `a setup change away from a shown site raises the banner over it`() {
        bindWidget(301)
        ActiveLocationResolver.persist(context, 37.4168, -122.0890)

        LocationUpdater.applyActiveLocationToAllWidgets(
            context = context,
            lat = 37.7749,
            lon = -122.4194,
            label = "San Francisco, CA, USA",
            ids = intArrayOf(301),
            displayName = "San Francisco",
        )

        val state = WidgetStateManager(context)
        assertEquals(null, state.getPendingLocationFetch())
        assertEquals(
            LocationChangeBanner.message(context, "San Francisco"),
            state.getActiveTransientMessage(301),
        )
    }

    /** With nothing on screen there is no render to keep: the full-screen interstitial is marked. */
    @Test
    fun `a first-ever setup location marks the interstitial, not the banner`() {
        bindWidget(304)

        LocationUpdater.applyActiveLocationToAllWidgets(
            context = context,
            lat = 37.7749,
            lon = -122.4194,
            label = "San Francisco, CA, USA",
            ids = intArrayOf(304),
            displayName = "San Francisco",
        )

        val state = WidgetStateManager(context)
        assertEquals("San Francisco", state.getPendingLocationFetch())
        assertEquals(null, state.getActiveTransientMessage(304))
    }

    @Test
    fun `the banner clears only its own message`() {
        bindWidget(305)
        bindWidget(306)
        val state = WidgetStateManager(context)
        LocationChangeBanner.show(context, "San Francisco", intArrayOf(305, 306))
        state.setTransientMessage(306, "Hourly data missing", System.currentTimeMillis() + 60_000L)

        val cleared = LocationChangeBanner.clear(context, "San Francisco", intArrayOf(305, 306))

        assertEquals(1, cleared)
        assertEquals(null, state.getActiveTransientMessage(305))
        assertEquals("Hourly data missing", state.getActiveTransientMessage(306))
    }

    @Test
    fun `re-saving the site already on screen marks nothing and clears a stale wait`() {
        bindWidget(302)
        ActiveLocationResolver.persist(context, 37.4168, -122.0890)
        WidgetStateManager(context).setPendingLocationFetch("Somewhere Else")

        LocationUpdater.applyActiveLocationToAllWidgets(
            context = context,
            lat = 37.4170,
            lon = -122.0892,
            label = "Mountain View",
            ids = intArrayOf(302),
        )

        assertEquals(null, WidgetStateManager(context).getPendingLocationFetch())
    }

    @Test
    fun `a follow-device move never marks a pending location fetch`() {
        bindWidget(303)
        ActiveLocationResolver.persist(context, 37.4168, -122.0890)

        LocationUpdater.applyFollowDeviceLocation(
            context = context,
            lat = 37.7749,
            lon = -122.4194,
            label = "Away",
            enqueueRefresh = false,
            ids = intArrayOf(303),
        )

        assertEquals(null, WidgetStateManager(context).getPendingLocationFetch())
    }

    /**
     * 2026-09-30, Pixel 7 Pro: a search for "860 Avery dr. 94043" saved that address, and Settings
     * read "Mountain View, California" — the reverse-geocode cache for the site outranked the name the
     * user had just picked. The picked name is what the card must show.
     */
    @Test
    fun `the name picked on the setup screen outranks the reverse-geocoded city`() {
        bindWidget(307)
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit()
            .putString("geo_name_37.417_-122.089", "Mountain View, California")
            .commit()

        LocationUpdater.applyActiveLocationToAllWidgets(
            context = context,
            lat = 37.4166014,
            lon = -122.0888722,
            label = "860 Avery Drive, Mountain View, CA 94043",
            ids = intArrayOf(307),
        )

        val label = LocationUpdater.describeCurrentLocation(context)
        assertTrue("expected the picked address in: $label", label.contains("860 Avery Drive, Mountain View, CA 94043"))
        assertTrue("expected coordinates in: $label", label.contains("37.4166"))
    }

    /** The picked name belongs to the picked site: once the device moves on, the card names the new site. */
    @Test
    fun `a follow-device move drops the picked name`() {
        bindWidget(308)
        LocationUpdater.applyActiveLocationToAllWidgets(
            context = context,
            lat = 37.4166014,
            lon = -122.0888722,
            label = "860 Avery Drive, Mountain View, CA 94043",
            ids = intArrayOf(308),
        )

        LocationUpdater.applyFollowDeviceLocation(
            context = context,
            lat = 37.7749,
            lon = -122.4194,
            label = "San Francisco, California",
            enqueueRefresh = false,
            ids = intArrayOf(308),
        )

        assertEquals(null, ActiveLocationResolver.chosenLabel(context))
        val label = LocationUpdater.describeCurrentLocation(context)
        assertFalse("must not name the site we left: $label", label.contains("Avery"))
        assertTrue("expected the new site's name in: $label", label.contains("San Francisco"))
    }

    /** A coordinate-shaped label (offline save) is not a name and must not be kept as one. */
    @Test
    fun `a coordinate label is not kept as the picked name`() {
        bindWidget(309)

        LocationUpdater.applyActiveLocationToAllWidgets(
            context = context,
            lat = 37.4166,
            lon = -122.0889,
            label = "37.4166, -122.0889",
            ids = intArrayOf(309),
        )

        assertEquals(null, ActiveLocationResolver.chosenLabel(context))
    }
}
