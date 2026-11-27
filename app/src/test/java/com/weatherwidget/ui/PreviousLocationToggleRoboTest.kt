package com.weatherwidget.ui

import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.R
import com.weatherwidget.data.model.RecentLocation
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.util.LocationMode
import com.weatherwidget.widget.ActiveLocationResolver
import com.weatherwidget.widget.WidgetStateManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings' previous-location toggle end to end on Android: the real [WidgetStateManager] recents,
 * [SettingsActivity]'s button, and [ConfigActivity]'s save path the button hands off to
 * (plans/261126-previous-location-toggle.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class PreviousLocationToggleRoboTest {
    private lateinit var context: Context
    private lateinit var stateManager: WidgetStateManager

    private val day = 24L * 60 * 60 * 1000
    private val homeLabel = "860 Avery Dr, Mountain View, CA 94043"
    private val home = RecentLocation(37.416794, -122.088936, homeLabel)
    private val sf = RecentLocation(37.7749, -122.4194, "San Francisco, CA")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WidgetStateManager.setPrefsNameOverrideForTesting(null)
        clearAll()
        stateManager = WidgetStateManager(context)
        ConfigActivity.setupSourceSelectorForTesting = { current, _, _ ->
            SetupSourceSelection(sources = current, nwsCoverage = SetupNwsCoverage.SUPPORTED)
        }
    }

    @After
    fun tearDown() {
        clearAll()
        ConfigActivity.setupSourceSelectorForTesting = null
    }

    private fun clearAll() {
        for (name in listOf("weather_widget_prefs", "widget_state_prefs", "weather_prefs")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        ActiveLocationResolver.clearForTesting(context)
    }

    private fun seed(recents: List<RecentLocation>, active: RecentLocation, mode: String = LocationMode.FIXED) {
        for (entry in recents.reversed()) stateManager.addRecentLocation(entry)
        ActiveLocationResolver.persist(context, active.lat, active.lon, active.label)
        LocationMode.set(context, mode)
    }

    private fun toggleButton(activity: SettingsActivity): Button =
        activity.findViewById(R.id.previous_location_button)

    @Test
    fun `fresh previous place shows the button and the tap switches back through ConfigActivity`() {
        val now = System.currentTimeMillis()
        seed(listOf(sf.copy(timestamp = now), home.copy(timestamp = now - 2 * day)), active = sf)

        var handoff: Intent? = null
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = toggleButton(activity)
                println("button visible=${button.visibility == View.VISIBLE} text=${button.text}")
                assertEquals(View.VISIBLE, button.visibility)
                assertEquals("Toggle to previous location: Mountain View, CA 94043", button.text.toString())
                button.performClick()
                handoff = shadowOf(activity).nextStartedActivity
            }
        }
        val intent = handoff
        assertNotNull("Settings handed off to ConfigActivity", intent)
        intent!!
        assertEquals(ConfigActivity::class.java.name, intent.component?.className)
        assertTrue(intent.getBooleanExtra(ConfigActivity.EXTRA_GLOBAL_CONFIG, false))
        assertEquals(home.lat, intent.getDoubleExtra(ConfigActivity.EXTRA_TOGGLE_LAT, 0.0), 1e-9)

        ActivityScenario.launch<ConfigActivity>(intent).use { scenario ->
            shadowOf(context.mainLooper).idle()
            println("after toggle save: state=${scenario.state}")
            // The save needs no UI: the screen finishes (and is destroyed) during launch.
            assertEquals("toggle save finishes the screen", androidx.lifecycle.Lifecycle.State.DESTROYED, scenario.state)
        }

        val active = ActiveLocationResolver.current(context)
        println("active after toggle=$active recents=${stateManager.getRecentLocations()}")
        // Active coordinates round-trip through Float prefs.
        assertEquals(home.lat, active!!.first, 1e-4)
        assertEquals(home.lon, active.second, 1e-4)
        assertEquals(LocationMode.FIXED, LocationMode.get(context))
        val toggledAt = stateManager.getLastLocationToggleAt()
        assertNotNull("toggle stamp written", toggledAt)
        toggledAt!!
        assertTrue(toggledAt >= now)
        val recents = stateManager.getRecentLocations()
        assertEquals(listOf(homeLabel, "San Francisco, CA"), recents.map { it.label })
        assertTrue("the place left is stamped last-used now", recents[1].timestamp >= now)

        // Back on Settings, the button now offers the place just left.
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Toggle to previous location: San Francisco, CA", toggleButton(activity).text.toString())
            }
        }
    }

    @Test
    fun `stale previous place with no recent toggle use hides the button`() {
        val now = System.currentTimeMillis()
        seed(listOf(sf.copy(timestamp = now), home.copy(timestamp = now - 15 * day)), active = sf)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(View.GONE, toggleButton(activity).visibility) }
        }
        // A toggle press 13 days ago keeps it.
        stateManager.markLocationToggleUsed(now - 13 * day)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> assertEquals(View.VISIBLE, toggleButton(activity).visibility) }
        }
    }

    @Test
    fun `follow-device entry offers Follow device and the tap asks for a device fix`() {
        val now = System.currentTimeMillis()
        val homeFix = RecentLocation(37.39, -122.08, "Mountain View, California", timestamp = now - day, followDevice = true)
        seed(listOf(sf.copy(timestamp = now), homeFix), active = sf)

        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val button = toggleButton(activity)
                assertEquals(View.VISIBLE, button.visibility)
                assertEquals("Toggle to previous location: Follow device", button.text.toString())
                button.performClick()
                val intent = shadowOf(activity).nextStartedActivity
                assertTrue(intent.getBooleanExtra(ConfigActivity.EXTRA_TOGGLE_FOLLOW_DEVICE, false))
                assertTrue(!intent.hasExtra(ConfigActivity.EXTRA_TOGGLE_LAT))
            }
        }
    }

    @Test
    fun `while following the device the follow-device entry is not offered`() {
        val now = System.currentTimeMillis()
        val sfFix = RecentLocation(sf.lat, sf.lon, "San Francisco, California", timestamp = now, followDevice = true)
        seed(listOf(sfFix, home.copy(timestamp = now - day)), active = sf, mode = LocationMode.FOLLOW_DEVICE)
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("Toggle to previous location: Mountain View, CA 94043", toggleButton(activity).text.toString())
            }
        }
    }
}
