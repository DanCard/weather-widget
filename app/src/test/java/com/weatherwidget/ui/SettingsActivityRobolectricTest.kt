package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.util.LocationMode
import com.weatherwidget.widget.WidgetStateManager
import org.junit.After
import org.junit.Assert.assertEquals
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(LongDuration::class)
class SettingsActivityRobolectricTest {
    private lateinit var context: Context

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
    fun `set location button opens the shared setup screen in global mode`() {
        val intent = Intent(context, SettingsActivity::class.java)
        ActivityScenario.launch<SettingsActivity>(intent).onActivity { activity ->
            activity.findViewById<Button>(R.id.set_location_button).performClick()

            val next = shadowOf(activity).nextStartedActivity
            assertNotNull(next)
            assertEquals(ConfigActivity::class.java.name, next.component?.className)
            assertTrue(next.getBooleanExtra(ConfigActivity.EXTRA_GLOBAL_CONFIG, false))
        }
    }

    @Test
    fun `location label shows default location and follow mode when nothing is set`() {
        val intent = Intent(context, SettingsActivity::class.java)
        ActivityScenario.launch<SettingsActivity>(intent).onActivity { activity ->
            val locationLabel = activity.findViewById<TextView>(R.id.current_location_label)
            val text = locationLabel.text.toString()
            assertTrue("expected default location in: $text", text.contains("Default Location"))
            assertTrue("expected follow-mode suffix in: $text", text.contains("Follows device"))
        }
    }

    @Test
    fun `location label shows pinned suffix when location mode is fixed`() {
        LocationMode.set(context, LocationMode.FIXED)

        val intent = Intent(context, SettingsActivity::class.java)
        ActivityScenario.launch<SettingsActivity>(intent).onActivity { activity ->
            val text = activity.findViewById<TextView>(R.id.current_location_label).text.toString()
            assertTrue("expected pinned suffix in: $text", text.contains("Pinned"))
        }
    }
}
