package com.weatherwidget.desktop

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The "Daily View — Today Column" overlay toggles: default off, and each switch updates the
 * matching [DesktopConfig] field in the draft. The card sits directly below Units (above the
 * fold), so no performScrollTo is needed — keep it that way or scroll BEFORE any
 * `mainClock.autoAdvance = false` (scroll animations run on the test clock).
 */
@Category(LongDuration::class)
class SettingsWindowOverlayTogglesTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleConfig = DesktopConfig(
lat = 0.0,
lon = 0.0,
label = "Test Location",
)

    @Test
    fun overlayTogglesDefaultOff() {
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = {},
                onExit = {},
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("today_overlay_delta_switch").assertIsOff()
        composeTestRule.onNodeWithTag("today_overlay_dominant_temp_switch").assertIsOff()
        composeTestRule.onNodeWithTag("today_overlay_dominant_age_switch").assertIsOff()
    }

    @Test
    fun overlayTogglesUpdateTheirOwnConfigFields() {
        val drafts = mutableListOf<DesktopConfig>()
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = {},
                onExit = {},
                // Long delay: auto-save must not fire mid-test; drafts come via onDraftChanged.
                autoSaveDelayMs = 60_000L,
                onDraftChanged = { drafts += it },
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("today_overlay_delta_switch").performClick()
        composeTestRule.waitForIdle()
        assertTrue(drafts.last().settings.todayOverlayDelta)
        assertFalse(drafts.last().settings.todayOverlayDominantTemp)
        assertFalse(drafts.last().settings.todayOverlayDominantAge)

        composeTestRule.onNodeWithTag("today_overlay_dominant_temp_switch").performClick()
        composeTestRule.waitForIdle()
        assertTrue(drafts.last().settings.todayOverlayDelta)
        assertTrue(drafts.last().settings.todayOverlayDominantTemp)
        assertFalse(drafts.last().settings.todayOverlayDominantAge)

        composeTestRule.onNodeWithTag("today_overlay_dominant_age_switch").performClick()
        composeTestRule.waitForIdle()
        assertTrue(drafts.last().settings.todayOverlayDominantAge)

        // Toggle delta back off: only that field changes.
        composeTestRule.onNodeWithTag("today_overlay_delta_switch").performClick()
        composeTestRule.waitForIdle()
        assertFalse(drafts.last().settings.todayOverlayDelta)
        assertTrue(drafts.last().settings.todayOverlayDominantTemp)
        assertTrue(drafts.last().settings.todayOverlayDominantAge)

        assertEquals("one draft per toggle", 4, drafts.size)
    }
}
