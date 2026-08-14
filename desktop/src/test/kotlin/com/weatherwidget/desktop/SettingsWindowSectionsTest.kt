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

        // The 9 sections that became SettingsCard titles. Each must render exactly once.
        // Phase 5: "API Sources" was renamed to "Weather Data Sources" to match Android's
        // strings.xml R.string.api_sources_title = "Weather Data Sources".
        // "Daily View — Today Column" (overlay toggles) matches Android's
        // R.string.today_overlay_title and sits between Units and Weather Data Sources.
        listOf("Units", "Daily View — Today Column", "Weather Data Sources", "Personal Weather Stations", "API Keys", "Icon Gallery", "Location", "Diagnostics", "Feedback").forEach { title ->
            composeTestRule.onAllNodesWithText(title).assertCountEquals(1)
        }
    }

    @Test
    fun unitsSectionPrecedesWeatherSources_likeAndroid() {
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
        val unitsTop = composeTestRule.onNodeWithText("Units").fetchSemanticsNode().positionInRoot.y
        val sourcesTop = composeTestRule
            .onNodeWithText("Weather Data Sources")
            .fetchSemanticsNode()
            .positionInRoot
            .y

        assertTrue(
            "Units should be the first Settings section, matching Android",
            unitsTop < sourcesTop,
        )
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
        // for items below the scroll fold (Diagnostics and Feedback) so the test is independent
        // of the window's pixel height vs. the form's natural length.
        composeTestRule.onNodeWithText("Use Celsius").assertExists()
        composeTestRule.onNodeWithText("Change Location").assertExists()
        composeTestRule.onNodeWithText("Test Location").assertExists()
        composeTestRule.onNodeWithText("Stations / Observations").assertExists()
        composeTestRule.onNodeWithText("Submit Bug Report").assertExists()
    }

    @Test
    fun getKeyButtonsRenderForEachKeyRequiringSource() {
        // Phase 4 item 2: each keyed source gets a "Get key…" button. Verify the count matches
        // ApiKeySignupUrls.sourcesRequiringKeys (5: TOMORROW_IO, SILURIAN, WEATHER_API,
        // VISUAL_CROSSING, OPEN_WEATHER_MAP).
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
