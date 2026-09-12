package com.weatherwidget.desktop

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import com.weatherwidget.test.category.LongDuration
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.experimental.categories.Category

/**
 * Pins the SettingsWindow refactor (Phase 3 of the desktop settings parity plan): the seven
 * section titles still render after the body was rewritten to use [com.weatherwidget.desktop.theme.SettingsCard]
 * instead of inline Text + Spacer boilerplate.
 *
 * Compose UI tests are LongDuration on :desktop per AGENTS.md (full Compose harness startup),
 * so this is bucketed accordingly. Assertions on sections below the scroll fold use
 * [assertExists] (semantics tree) rather than [assertIsDisplayed] (on-screen) so the test
 * doesn't depend on the window's pixel height vs. the form's natural length.
 */
@Category(LongDuration::class)
class SettingsWindowSectionsTest {

    /**
     * Android's `activity_settings.xml` section order, minus Language (the desktop has no
     * translations). Both tests below read from this one list so a section added to one platform
     * must be added here — and therefore to the other — before the suite goes green.
     */
    private val ANDROID_SECTION_ORDER = listOf(
        "Hourly Zoom",
        "Notifications",
        "Units",
        "Daily View — Today Column",
        "Personal Weather Stations",
        "Weather Data Sources",
        "Icon gallery",
        "Default Location",
        "Feedback & Bug Reports",
        "API Keys",
        "Support Development",
    )

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleConfig = DesktopConfig(
lat = 0.0,
lon = 0.0,
label = "Test Location",
)

    @Test
    fun allSectionTitlesArePresentAsCards() {
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = {},
                onExit = {},
            )
        }
        composeTestRule.waitForIdle()

        // Every SettingsCard title, each rendering exactly once. Titles are Android's
        // strings.xml values verbatim (icon_preview_title is "Icon gallery", lower-case g).
        // "Diagnostics" is deliberately absent: Android has no Observations entry in Settings.
        ANDROID_SECTION_ORDER.forEach { title ->
            composeTestRule.onAllNodesWithText(title).assertCountEquals(1)
        }
        composeTestRule.onAllNodesWithText("Diagnostics").assertCountEquals(0)
    }

    @Test
    fun sectionOrderMatchesAndroid() {
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = {},
                onExit = {},
            )
        }
        composeTestRule.waitForIdle()

        // positionInRoot, not boundsInRoot: boundsInRoot is clipped by the scroll viewport and
        // collapses to zero for a section below the fold, which would invert this comparison as
        // the form grows. Ordering is what's under test, not visibility.
        val order = ANDROID_SECTION_ORDER
        val tops = order.associateWith { title ->
            composeTestRule.onNodeWithText(title).fetchSemanticsNode().positionInRoot.y
        }

        // Pin the FULL section order to Android's activity_settings.xml. Notifications and
        // Personal Weather Stations were once swapped on both platforms together, and API Keys sat
        // under Weather Data Sources here while Android had it after Feedback; this assertion is
        // what keeps the two forms from drifting apart again.
        order.zipWithNext().forEach { (upper, lower) ->
            assertTrue(
                "\"$upper\" should precede \"$lower\", matching Android",
                tops.getValue(upper) < tops.getValue(lower),
            )
        }
    }

    @Test
    fun headerButtonsArePresentAfterRefactor() {
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = {},
                onExit = {},
            )
        }
        composeTestRule.waitForIdle()

        // Header chrome retained through the refactor.
        composeTestRule.onNodeWithText("Settings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Refresh Data").assertIsDisplayed()
        composeTestRule.onNodeWithText("View App Logs").assertIsDisplayed()
        // Footer chrome retained too.
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
        composeTestRule.onNodeWithText("Exit app").assertIsDisplayed()
    }

    @Test
    fun bodyInteriorsSurviveRefactor() {
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = {},
                onExit = {},
            )
        }
        composeTestRule.waitForIdle()

        // Body-interior content that lived inside the now-card-wrapped sections. Use assertExists
        // for items below the scroll fold (Icon gallery onward) so the test is independent
        // of the window's pixel height vs. the form's natural length.
        composeTestRule.onNodeWithText("Use Celsius").assertExists()
        composeTestRule.onNodeWithText("Set Location…").assertExists()
        composeTestRule.onNodeWithText("Widget Location: Test Location", substring = true).assertExists()
        composeTestRule.onNodeWithText("View Icon Gallery").assertExists()
        composeTestRule.onNodeWithText("Submit Bug Report").assertExists()
    }

    @Test
    fun getKeyButtonsRenderForEachKeyRequiringSource() {
        // Phase 4 item 2: each keyed source gets a "Get key…" button. Verify the count matches
        // ApiKeySignupUrls.sourcesRequiringKeys (4: TOMORROW_IO, SILURIAN, WEATHER_API,
        // OPEN_WEATHER_MAP).
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = {},
                onExit = {},
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Get key…").assertCountEquals(
            com.weatherwidget.shared.util.ApiKeySignupUrls.sourcesRequiringKeys.size,
        )
    }
}
