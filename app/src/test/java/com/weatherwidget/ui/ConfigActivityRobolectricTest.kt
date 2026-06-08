package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.EditText
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
class ConfigActivityRobolectricTest {
    private lateinit var context: Context
    private val widgetId = 5566

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
    }

    @After
    fun tearDown() {
        clearTestPrefs("weather_widget_prefs")
        clearTestPrefs("widget_state_prefs")
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
    }
}
