package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.RobolectricTest
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker
import com.weatherwidget.widget.CandidateProposal
import com.weatherwidget.widget.LocationHandoffStore
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
    fun `shouldHealTo returns false when no widgets are placed`() {
        val result = LocationUpdater.shouldHealTo(context, 37.4220, -122.0841)
        assertFalse(result)
    }

    /**
     * The placeholder for "GPS never resolved" is now the absence of coordinates, so an unset widget
     * must stay heal-eligible. It used to be recognised by equalling the Google-HQ constant.
     */
    @Test
    fun `shouldHealTo returns true when a widget has no location at all`() {
        bindWidget(201)

        assertTrue(LocationUpdater.shouldHealTo(context, 40.7128, -74.0060))
    }

    /**
     * `shouldHealTo` reads the *stored* coordinates, not the resolved ones. Resolution falls back
     * through the historical-POI list, so a never-configured widget would otherwise inherit a POI and
     * read as "already located" — silently disabling the heal for the widget that most needs it.
     */
    @Test
    fun `shouldHealTo ignores the historical-POI fallback when nothing is stored`() {
        bindWidget(203)
        SharedPreferencesUtil.getPrefs(context, "weather_prefs").edit()
            .putString("historical_pois", "Somewhere|40.7128|-74.0060")
            .commit()

        assertTrue(LocationUpdater.shouldHealTo(context, 40.7128, -74.0060))
    }

    @Test
    fun `allWidgetsAtDefault is true for an unset widget and false once one is configured`() {
        bindWidget(204)
        assertTrue(LocationUpdater.allWidgetsAtDefault(context))

        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}204", 40.7128f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}204", -74.0060f)
            .commit()

        assertFalse(LocationUpdater.allWidgetsAtDefault(context))
    }

    /**
     * Proximity must never mean "unset" in the steady-state heal check. A user who genuinely lives
     * near Google HQ chose that location; treating it as a placeholder would let the heal overwrite a
     * deliberate choice. Clearing the retired sentinel is the one-time migration's job instead.
     */
    @Test
    fun `allWidgetsAtDefault is false for a real location near the retired default`() {
        bindWidget(205)
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}205", 37.4220f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}205", -122.0841f)
            .commit()

        assertFalse(LocationUpdater.allWidgetsAtDefault(context))
    }

    @Test
    fun `describeCurrentLocation says so when there is no location`() {
        bindWidget(206)

        val label = LocationUpdater.describeCurrentLocation(context)

        // Used to read "Default Location: 37.4220, -122.0841" -- coordinates the user never chose.
        assertTrue("expected no-location label in: $label", label.contains("No location set"))
        assertFalse("must not format a coordinate: $label", label.contains("37.42"))
    }

    @Test
    fun `shouldHealTo returns false when widgets are already sameSite with fresh coordinates`() {
        val widgetId = 202
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", 37.4220f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", -122.0841f)
            .commit()

        // 37.4221 is sameSite with 37.4220 (difference < 0.002)
        val result = LocationUpdater.shouldHealTo(context, 37.4221, -122.0840)
        assertFalse(result)
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

    @Test
    fun `shouldHealTo returns true when widgets are at stale coordinates and fresh coordinates are different`() {
        val widgetId = 203
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", 34.0522f) // Los Angeles
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", -118.2437f)
            .commit()

        // Fresh coordinate is Googleplex (different site)
        val result = LocationUpdater.shouldHealTo(context, 37.4220, -122.0841)
        assertTrue(result)
    }

    @Test
    fun `follow-device candidate does not replace active widget coordinates before promotion`() {
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

        val proposal = LocationUpdater.proposeFollowDeviceLocation(
            context = context,
            lat = 37.3774,
            lon = -122.0749,
            label = "Away",
            enqueueRefresh = false,
            nowMs = 100L,
            ids = intArrayOf(widgetId),
        )

        assertEquals(CandidateProposal.UPDATED, proposal)
        assertEquals(37.4168f, prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", Float.NaN), 0.0001f)
        assertEquals(-122.0890f, prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", Float.NaN), 0.0001f)
        assertNotNull(LocationHandoffStore.getCandidate(context))
    }

    @Test
    fun `promoting evaluated candidate atomically updates active widget coordinates`() {
        val widgetId = 205
        val info = android.appwidget.AppWidgetProviderInfo().apply {
            provider = android.content.ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowAppWidgetManager.addBoundWidget(widgetId, info)
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        prefs.edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", 37.4168f)
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", -122.0890f)
            .commit()
        LocationUpdater.proposeFollowDeviceLocation(
            context = context,
            lat = 37.3774,
            lon = -122.0749,
            label = "Away",
            enqueueRefresh = false,
            nowMs = 100L,
            ids = intArrayOf(widgetId),
        )
        val candidate = LocationHandoffStore.getCandidate(context)!!

        assertTrue(LocationUpdater.promoteCandidateIfMatches(context, candidate, intArrayOf(widgetId)))
        assertEquals(37.3774f, prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", Float.NaN), 0.0001f)
        assertEquals(-122.0749f, prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", Float.NaN), 0.0001f)
    }
}
