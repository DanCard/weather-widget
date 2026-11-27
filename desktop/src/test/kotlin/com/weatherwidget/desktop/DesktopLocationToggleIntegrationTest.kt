package com.weatherwidget.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.weatherwidget.data.model.RecentLocation
import com.weatherwidget.test.category.LongDuration
import com.weatherwidget.widget.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Settings' previous-location toggle through the real [SettingsWindow] button, [DesktopLocationToggle]
 * and the config save policy Main applies ([resolveDesktopConfigSave])
 * (plans/261126-previous-location-toggle.md).
 */
@Category(LongDuration::class)
class DesktopLocationToggleIntegrationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val day = 24L * 60 * 60 * 1000
    private val now = System.currentTimeMillis()
    private val homeLabel = "860 Avery Dr, Mountain View, CA 94043"
    private val home = RecentLocation(37.416794, -122.088936, homeLabel, timestamp = now - 2 * day)
    private val sf = RecentLocation(37.7749, -122.4194, "San Francisco, CA", timestamp = now)

    private val inSf = DesktopConfig(
        lat = sf.lat,
        lon = sf.lon,
        label = sf.label,
        recentLocations = listOf(sf, home),
        windowX = 640f,
        windowY = 480f,
        zoomFactor = 0.4f,
        viewMode = ViewMode.TEMPERATURE,
    )

    @Test
    fun `toggle button switches back and keeps the rest of the config`() {
        val persisted = mutableStateOf(inSf)
        val sources = mutableListOf<String>()
        composeTestRule.setContent {
            val current by persisted
            SettingsWindow(
                config = current,
                onClose = {},
                onSave = {},
                onExit = {},
                autoSaveDelayMs = 600_000L,
                // What Main's onTogglePreviousLocation does.
                onTogglePreviousLocation = { target ->
                    val before = persisted.value
                    val draft = DesktopLocationToggle.switched(before, target, System.currentTimeMillis())
                    val source = DesktopLocationChangeFeedback.TOGGLE_SOURCE
                    sources += source
                    val resolution = resolveDesktopConfigSave(before, draft, source)
                    println("toggle save: top-level=${resolution.topLevelChanges} banner=${DesktopLocationChangeFeedback.pendingPlaceName(source, before, resolution.config)}")
                    persisted.value = resolution.config
                },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("previous_location_btn").assertExists()
        composeTestRule.onNodeWithText("Toggle to previous location: Mountain View, CA 94043").assertExists()
        composeTestRule.onNodeWithTag("previous_location_btn").performClick()
        composeTestRule.waitForIdle()

        val after = persisted.value
        println("after toggle: lat=${after.lat} lon=${after.lon} label=${after.label} recents=${after.recentLocations} stamp=${after.locationToggleUsedAtMs}")
        assertEquals(listOf(DesktopLocationChangeFeedback.TOGGLE_SOURCE), sources)
        assertEquals(home.lat, after.lat, 1e-9)
        assertEquals(home.lon, after.lon, 1e-9)
        assertEquals(homeLabel, after.label)
        assertTrue((after.locationToggleUsedAtMs ?: 0) >= now)
        assertEquals(listOf(homeLabel, "San Francisco, CA"), after.recentLocations.map { it.label })
        assertTrue("SF, the place left, is stamped last-used now", after.recentLocations[1].timestamp >= now)
        // Only the location fields moved.
        assertEquals(640f, after.windowX)
        assertEquals(480f, after.windowY)
        assertEquals(0.4f, after.zoomFactor)
        assertEquals(ViewMode.TEMPERATURE, after.viewMode)

        // The button now offers the place just left.
        composeTestRule.onNodeWithText("Toggle to previous location: San Francisco, CA").assertExists()
    }

    @Test
    fun `stale previous place hides the button until the toggle was used recently`() {
        val stale = inSf.copy(recentLocations = listOf(sf, home.copy(timestamp = now - 15 * day)))
        val persisted = mutableStateOf(stale)
        composeTestRule.setContent {
            val current by persisted
            SettingsWindow(config = current, onClose = {}, onSave = {}, onExit = {}, autoSaveDelayMs = 600_000L)
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("previous_location_btn").assertDoesNotExist()

        persisted.value = stale.copy(locationToggleUsedAtMs = now - 13 * day)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("previous_location_btn").assertExists()
    }

    @Test
    fun `toggle save cannot clobber a newer window position and keeps the stamp on later picker saves`() {
        val persisted = inSf.copy(windowX = 100f)
        // A toggle draft built from an older snapshot (windowX 640) must not rewind the window.
        val draft = DesktopLocationToggle.switched(inSf, home, now)
        val toggled = resolveDesktopConfigSave(persisted, draft, DesktopLocationChangeFeedback.TOGGLE_SOURCE).config
        assertEquals(100f, toggled.windowX)
        assertEquals(now, toggled.locationToggleUsedAtMs)
        assertEquals(homeLabel, DesktopLocationChangeFeedback.pendingPlaceName(DesktopLocationChangeFeedback.TOGGLE_SOURCE, persisted, toggled))

        // The picker builds its draft from defaults (stamp null): the stamp survives.
        val pickerDraft = DesktopConfig(lat = 40.7128, lon = -74.0060, label = "New York, NY", recentLocations = toggled.recentLocations)
        val picked = resolveDesktopConfigSave(toggled, pickerDraft, DesktopLocationChangeFeedback.PICKER_SOURCE).config
        assertEquals(now, picked.locationToggleUsedAtMs)
    }

    @Test
    fun `desktop skips a follow-device entry`() {
        val fix = RecentLocation(37.39, -122.08, "Mountain View, California", timestamp = now, followDevice = true)
        val config = inSf.copy(recentLocations = listOf(sf, fix))
        assertNull(DesktopLocationToggle.target(config, now))
    }
}
