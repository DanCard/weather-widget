package com.weatherwidget.ui

import android.content.ComponentName
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    // Samsung regression: the widget launches this activity with FLAG_ACTIVITY_NEW_TASK. If it shared
    // MainActivity's (default) task affinity, One UI Home would foreground the MainActivity-rooted task
    // and back/finish here would reveal the "Welcome to Weather Widget" screen instead of the home
    // screen. A distinct task affinity keeps it in its own task. Same fix as
    // WeatherObservationsActivityRobolectricTest's analogous test.
    @Test
    fun `settings activity does not share a task with the welcome MainActivity`() {
        val pm = context.packageManager
        val settingsInfo = pm.getActivityInfo(
            ComponentName(context, SettingsActivity::class.java), 0)
        val mainInfo = pm.getActivityInfo(
            ComponentName(context, MainActivity::class.java), 0)
        assertNotEquals(
            "Settings must live in its own task so back/close cannot reveal the Welcome screen",
            mainInfo.taskAffinity,
            settingsInfo.taskAffinity,
        )
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
    fun `location label says no location is set, with follow mode, when nothing is set`() {
        val intent = Intent(context, SettingsActivity::class.java)
        ActivityScenario.launch<SettingsActivity>(intent).onActivity { activity ->
            val locationLabel = activity.findViewById<TextView>(R.id.current_location_label)
            val text = locationLabel.text.toString()
            // Used to read "Default Location: 37.4220, -122.0841" — Google HQ presented as the user's
            // own. With no location there is nothing to format, so the label says exactly that.
            assertTrue("expected no-location label in: $text", text.contains("No location set"))
            assertFalse("must not format a coordinate: $text", text.contains("37.42"))
            assertTrue("expected follow-mode suffix in: $text", text.contains("Follows device"))
        }
    }

    @Test
    fun `celsius toggle persists the unit and repaints widgets via direct broadcast`() {
        val intent = Intent(context, SettingsActivity::class.java)
        ActivityScenario.launch<SettingsActivity>(intent).onActivity { activity ->
            val switch = activity.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.use_celsius_switch)
            switch.performClick()

            assertTrue(WidgetStateManager(activity).useCelsius())

            // Must be the direct ACTION_REFRESH repaint, not a WorkManager job — expedited work
            // degrades to deferred under quota/Doze and the unit change then takes minutes to show.
            val broadcast = shadowOf(activity.application).broadcastIntents.lastOrNull {
                it.action == com.weatherwidget.widget.WidgetActions.ACTION_REFRESH
            }
            assertNotNull("expected ACTION_REFRESH broadcast after toggling units", broadcast)
            assertTrue(broadcast!!.getBooleanExtra(com.weatherwidget.widget.WidgetActions.EXTRA_UI_ONLY, false))
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

    @Test
    fun `today overlay switches default to unchecked`() {
        val intent = Intent(context, SettingsActivity::class.java)
        ActivityScenario.launch<SettingsActivity>(intent).onActivity { activity ->
            listOf(
                R.id.today_overlay_delta_switch,
                R.id.today_overlay_dominant_temp_switch,
                R.id.today_overlay_dominant_age_switch,
            ).forEach { id ->
                val switch = activity.findViewById<androidx.appcompat.widget.SwitchCompat>(id)
                assertNotNull(switch)
                assertFalse("switch $id must default to off", switch.isChecked)
            }
        }
    }

    @Test
    fun `today overlay switches persist their pref and repaint widgets`() {
        val cases =
            listOf(
                Triple(R.id.today_overlay_delta_switch,
                    { m: WidgetStateManager -> m.showTodayOverlayDelta() }, "delta"),
                Triple(R.id.today_overlay_dominant_temp_switch,
                    { m: WidgetStateManager -> m.showTodayOverlayDominantTemp() }, "dominant temp"),
                Triple(R.id.today_overlay_dominant_age_switch,
                    { m: WidgetStateManager -> m.showTodayOverlayDominantAge() }, "dominant age"),
            )
        val intent = Intent(context, SettingsActivity::class.java)
        ActivityScenario.launch<SettingsActivity>(intent).onActivity { activity ->
            cases.forEach { (id, getter, label) ->
                val app = shadowOf(activity.application)
                val broadcastsBefore =
                    app.broadcastIntents.count {
                        it.action == com.weatherwidget.widget.WidgetActions.ACTION_REFRESH
                    }

                activity.findViewById<androidx.appcompat.widget.SwitchCompat>(id).performClick()

                assertTrue("toggling $label must persist its pref", getter(WidgetStateManager(activity)))
                val broadcastsAfter =
                    app.broadcastIntents.count {
                        it.action == com.weatherwidget.widget.WidgetActions.ACTION_REFRESH
                    }
                assertTrue(
                    "toggling $label must fire the direct ACTION_REFRESH repaint",
                    broadcastsAfter > broadcastsBefore,
                )
            }
        }
    }
}
