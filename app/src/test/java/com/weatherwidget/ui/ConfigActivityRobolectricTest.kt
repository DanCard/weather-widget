package com.weatherwidget.ui

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.util.LocationMode
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class ConfigActivityRobolectricTest {
    private lateinit var context: Context
    private val widgetId = 5566

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
        clearTestPrefs("weather_prefs")
    }

    @After
    fun tearDown() {
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
        clearTestPrefs("weather_prefs")
    }

    private fun clearTestPrefs(name: String) {
        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `manual coordinates entry geocodes and saves location`() {
        val intent = Intent(context, ConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val scenario = ActivityScenario.launch<ConfigActivity>(intent)

        val mockResolver = mockk<SharedLocationResolver>()
        val testLocation = ResolvedLocation(
            lat = 34.0522,
            lon = -118.2437,
            label = "Los Angeles, CA",
            source = "Manual coordinates"
        )
        coEvery { mockResolver.fromCoordinates(34.0522, -118.2437) } returns testLocation

        scenario.onActivity { activity ->
            activity.sharedLocationResolver = mockResolver

            val latInput = activity.findViewById<EditText>(R.id.lat_input)
            val lonInput = activity.findViewById<EditText>(R.id.lon_input)
            val useBtn = activity.findViewById<Button>(R.id.use_coordinates_button)

            latInput.setText("34.0522")
            lonInput.setText("-118.2437")
            useBtn.performClick()
        }

        // Wait for coroutine inside ConfigActivity to finish and save location
        shadowOf(context.mainLooper).idle()

        // Check if location was saved to widget preferences
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        val savedLat = prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", Float.NaN)
        val savedLon = prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", Float.NaN)

        assertEquals(34.0522f, savedLat, 0.0001f)
        assertEquals(-118.2437f, savedLon, 0.0001f)

        // Verify that a Toast showing the resolved location label was shown
        val latestToast = ShadowToast.getLatestToast()
        assertNotNull(latestToast)
        val toastText = ShadowToast.getTextOfLatestToast()
        assertEquals("Location: Los Angeles, CA", toastText)

        // A deliberate manual choice pins the location against the GPS auto-heal
        assertEquals(LocationMode.FIXED, LocationMode.get(context))
    }

    @Test
    fun `launch without widget id and without global extra finishes immediately`() {
        val intent = Intent(context, ConfigActivity::class.java)
        val scenario = ActivityScenario.launch<ConfigActivity>(intent)
        shadowOf(context.mainLooper).idle()
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    @Test
    fun `global mode saves to all widgets and never sets RESULT_OK`() {
        bindWidget(9001, 34.0522, -118.2437)
        bindWidget(9002, 40.7128, -74.0060)

        val intent = Intent(context, ConfigActivity::class.java).apply {
            putExtra(ConfigActivity.EXTRA_GLOBAL_CONFIG, true)
        }
        val scenario = ActivityScenario.launch<ConfigActivity>(intent)

        val mockResolver = mockk<SharedLocationResolver>()
        coEvery { mockResolver.fromCoordinates(30.2672, -97.7431) } returns ResolvedLocation(
            lat = 30.2672,
            lon = -97.7431,
            label = "Austin, TX",
            source = "Manual coordinates"
        )

        scenario.onActivity { activity ->
            assertFalse("global mode must not early-finish", activity.isFinishing)
            activity.sharedLocationResolver = mockResolver

            activity.findViewById<EditText>(R.id.lat_input).setText("30.2672")
            activity.findViewById<EditText>(R.id.lon_input).setText("-97.7431")
            activity.findViewById<Button>(R.id.use_coordinates_button).performClick()
        }

        shadowOf(context.mainLooper).idle()

        // Every bound widget's location was rewritten
        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        for (id in intArrayOf(9001, 9002)) {
            assertEquals(30.2672f, prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", Float.NaN), 0.0001f)
            assertEquals(-97.7431f, prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$id", Float.NaN), 0.0001f)
        }

        // POI history recorded and mode pinned
        val weatherPrefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        assertTrue(weatherPrefs.getString("historical_pois", "")!!.contains("Austin, TX|30.2672|-97.7431"))
        assertEquals(LocationMode.FIXED, LocationMode.get(context))

        // The widget-host handshake must not fire without a widget id
        scenario.onActivity { activity ->
            assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        }
    }

    @Test
    fun `search result choice saves location and pins mode`() {
        val intent = Intent(context, ConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        val scenario = ActivityScenario.launch<ConfigActivity>(intent)

        val mockResolver = mockk<SharedLocationResolver>()
        coEvery { mockResolver.searchText("Austin") } returns listOf(
            ResolvedLocation(lat = 30.2672, lon = -97.7431, label = "Austin, TX", source = "Nominatim")
        )

        scenario.onActivity { activity ->
            activity.sharedLocationResolver = mockResolver
            activity.findViewById<EditText>(R.id.location_search_input).setText("Austin")
            activity.findViewById<Button>(R.id.search_location_button).performClick()
        }

        shadowOf(context.mainLooper).idle()

        scenario.onActivity { activity ->
            val dialog = ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.listView.performItemClick(dialog.listView, 0, 0)
        }

        shadowOf(context.mainLooper).idle()

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        assertEquals(30.2672f, prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", Float.NaN), 0.0001f)
        assertEquals(-97.7431f, prefs.getFloat("${ConfigActivity.KEY_LON_PREFIX}$widgetId", Float.NaN), 0.0001f)
        assertEquals(LocationMode.FIXED, LocationMode.get(context))
    }

    @Test
    fun `initial widget setup auto-starts the precise location flow`() {
        val intent = Intent(context, ConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        ActivityScenario.launch<ConfigActivity>(intent).onActivity { activity ->
            val requested = shadowOf(activity).lastRequestedPermission
            assertNotNull("expected auto-started permission request", requested)
            assertTrue(requested.requestedPermissions.contains(android.Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    @Test
    fun `settings entry does not auto-start the precise location flow`() {
        val intent = Intent(context, ConfigActivity::class.java).apply {
            putExtra(ConfigActivity.EXTRA_GLOBAL_CONFIG, true)
        }
        ActivityScenario.launch<ConfigActivity>(intent).onActivity { activity ->
            assertEquals(null, shadowOf(activity).lastRequestedPermission)
        }
    }

    @Test
    fun `back button exits without saving and keeps RESULT_CANCELED`() {
        val intent = Intent(context, ConfigActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        }
        ActivityScenario.launch<ConfigActivity>(intent).onActivity { activity ->
            activity.findViewById<android.widget.ImageButton>(R.id.config_back_button).performClick()
            assertTrue(activity.isFinishing)
            assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
        }

        val prefs = SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME)
        assertTrue(prefs.getFloat("${ConfigActivity.KEY_LAT_PREFIX}$widgetId", Float.NaN).isNaN())
    }

    @Test
    fun `screen shows the current location and mode`() {
        val intent = Intent(context, ConfigActivity::class.java).apply {
            putExtra(ConfigActivity.EXTRA_GLOBAL_CONFIG, true)
        }
        ActivityScenario.launch<ConfigActivity>(intent).onActivity { activity ->
            val text = activity.findViewById<android.widget.TextView>(R.id.current_location_label).text.toString()
            assertTrue("expected location summary in: $text", text.contains("Default Location"))
            assertTrue("expected mode suffix in: $text", text.contains("Follows device"))
        }
    }

    private fun bindWidget(id: Int, lat: Double, lon: Double) {
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName(context, WeatherWidgetProvider::class.java)
        }
        shadowOf(AppWidgetManager.getInstance(context)).addBoundWidget(id, info)
        SharedPreferencesUtil.getPrefs(context, ConfigActivity.PREFS_NAME).edit()
            .putFloat("${ConfigActivity.KEY_LAT_PREFIX}$id", lat.toFloat())
            .putFloat("${ConfigActivity.KEY_LON_PREFIX}$id", lon.toFloat())
            .commit()
    }
}
