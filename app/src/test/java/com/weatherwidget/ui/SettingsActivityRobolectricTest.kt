package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.ResolvedLocation
import com.weatherwidget.data.repository.SharedLocationResolver
import com.weatherwidget.util.SharedPreferencesUtil
import com.weatherwidget.widget.WidgetStateManager
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class SettingsActivityRobolectricTest {
    private lateinit var context: Context
    private val widgetId1 = 8801
    private val widgetId2 = 8802

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
    fun `manual coordinates entry in Settings saves location globally`() {
        val intent = Intent(context, SettingsActivity::class.java)
        val scenario = ActivityScenario.launch<SettingsActivity>(intent)

        val mockResolver = mockk<SharedLocationResolver>()
        val testLocation = ResolvedLocation(
            lat = 40.7128,
            lon = -74.0060,
            label = "New York, NY",
            source = "Manual coordinates"
        )
        coEvery { mockResolver.fromCoordinates(40.7128, -74.0060) } returns testLocation

        scenario.onActivity { activity ->
            activity.sharedLocationResolver = mockResolver

            val latInput = activity.findViewById<EditText>(R.id.lat_input)
            val lonInput = activity.findViewById<EditText>(R.id.lon_input)
            val saveBtn = activity.findViewById<Button>(R.id.save_location_button)

            latInput.setText("40.7128")
            lonInput.setText("-74.0060")
            saveBtn.performClick()
        }

        // Wait for coroutines to complete
        shadowOf(context.mainLooper).idle()

        // Check default location POIs in weather_prefs
        val weatherPrefs = SharedPreferencesUtil.getPrefs(context, "weather_prefs")
        val historicalPois = weatherPrefs.getString("historical_pois", "") ?: ""
        assert(historicalPois.contains("New York, NY|40.7128|-74.006"))

        // Check if Toast showing updated widgets was shown
        val latestToast = ShadowToast.getLatestToast()
        assertNotNull(latestToast)
        val toastText = ShadowToast.getTextOfLatestToast()
        assertEquals("Location updated for all widgets", toastText)
    }

    @Test
    fun `settings pre-fills Mountain View when no location exists`() {
        // Ensure all prefs are clear
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("weather_prefs")

        val intent = Intent(context, SettingsActivity::class.java)
        ActivityScenario.launch<SettingsActivity>(intent).onActivity { activity ->
            val latInput = activity.findViewById<EditText>(R.id.lat_input)
            val lonInput = activity.findViewById<EditText>(R.id.lon_input)
            val locationLabel = activity.findViewById<TextView>(R.id.current_location_label)

            assertEquals("37.422", latInput.text.toString())
            assertEquals("-122.0841", lonInput.text.toString())
            assert(locationLabel.text.toString().contains("Default Location"))
        }
    }
}
