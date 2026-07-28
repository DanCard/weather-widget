package com.weatherwidget.desktop

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Phase 5: dirty-state indicator on the Save button, and the auto-save idle timer that flushes
 * edits back to the daemon after the last edit.
 *
 * Tests use the `autoSaveDelayMs` parameter on [SettingsWindow] to dial the delay:
 *   - The dirty-marker test uses a LONG delay so the auto-save doesn't fire mid-test (it
 *     just checks the marker appears, not when save fires).
 *   - The auto-save tests disable clock auto-advance and move Compose's test clock explicitly,
 *     proving both sides of the debounce threshold without sleeping.
 *
 * Production keeps the 5000ms default via [SettingsWindow]'s `autoSaveDelayMs` parameter.
 */
@Category(LongDuration::class)
class SettingsWindowPhase5Test {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleConfig = DesktopConfig(
        lat = 0.0,
        lon = 0.0,
        label = "Test Location",
        // TOMORROW_IO is in the default list of configurable sources, which is what the
        // source-list row toggles. Adding it here makes it visible so toggling it has effect.
        visibleSources = listOf("NWS", "OPEN_METEO", "SILURIAN", "TOMORROW_IO"),
        // Keep the Switch transition and assertion independent of the Gradle JVM's locale.
        useCelsius = false,
    )

    @Test
    fun saveButtonShowsDirtyMarker_afterTogglingASource() {
        // Long delay so the auto-save doesn't fire while we're asserting the marker is present.
        val longDelay = 60_000L
        var saveCount = 0
        val drafts = mutableListOf<DesktopConfig>()
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = { saveCount++ },
                onExit = {},
                autoSaveDelayMs = longDelay,
                onDraftChanged = { drafts += it },
            )
        }
        composeTestRule.waitForIdle()

        // Sanity: Save is clean before any edits.
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()

        // Uncheck TOMORROW_IO. The visible list goes from 4 to 3 sources, so currentConfig
        // diverges from the snapshot config and the Save button must show the dirty marker.
        // Use the testTag on the Checkbox (the click target) -- Compose Desktop's text nodes
        // don't expose a click action on their own.
        composeTestRule.onNodeWithTag("source_checkbox_TOMORROW_IO").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save •").assertIsDisplayed()
        assertEquals("no auto-save must fire during this test", 0, saveCount)
        assertEquals(
            "the owning Window receives the same draft for title-bar/Escape close",
            listOf("NWS", "OPEN_METEO", "SILURIAN"),
            drafts.single().visibleSources,
        )
    }

    @Test
    fun autoSave_firesAfterIdlePeriod_withoutClosingWindow() {
        val savedConfigs = mutableListOf<DesktopConfig>()
        val testDelay = 100L
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = { savedConfigs += it.copy() },
                onExit = {},
                autoSaveDelayMs = testDelay,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false

        // Advancing while clean must not save.
        composeTestRule.mainClock.advanceTimeBy(testDelay * 5L)
        composeTestRule.waitForIdle()
        assertEquals(0, savedConfigs.size)

        // Toggle a source to make currentConfig diverge.
        composeTestRule.onNodeWithTag("source_checkbox_TOMORROW_IO").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
        assertEquals(0, savedConfigs.size)

        // Pump just under the delay -- still no save.
        composeTestRule.mainClock.advanceTimeBy(testDelay / 2)
        composeTestRule.waitForIdle()
        assertEquals(0, savedConfigs.size)

        // Pump past the threshold -- auto-save fires exactly once.
        composeTestRule.mainClock.advanceTimeBy(testDelay + 50)
        composeTestRule.waitForIdle()
        assertEquals(
            "auto-save must fire exactly once after the idle window",
            1,
            savedConfigs.size,
        )
        assertEquals(
            "the auto-saved config is the post-edit one (TOMORROW_IO no longer visible)",
            listOf("NWS", "OPEN_METEO", "SILURIAN"),
            savedConfigs.single().visibleSources,
        )
    }

    @Test
    fun autoSave_resetsTimerOnEachEdit() {
        val savedConfigs = mutableListOf<DesktopConfig>()
        val testDelay = 1_000L
        composeTestRule.setContent {
            SettingsWindow(
                config = sampleConfig,
                onClose = {},
                onSave = { savedConfigs += it.copy() },
                onExit = {},
                autoSaveDelayMs = testDelay,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false

        // First edit: disable TOMORROW_IO.
        composeTestRule.onNodeWithTag("source_checkbox_TOMORROW_IO").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        // Pump part of the delay, then make a second edit. The generous remaining interval leaves
        // room for Material Switch's callback/recomposition frames before the first timer expires.
        composeTestRule.mainClock.advanceTimeBy(testDelay / 10)
        composeTestRule.waitForIdle()
        assertEquals(0, savedConfigs.size)
        composeTestRule.onNodeWithTag("use_celsius_switch").performClick()
        // The visible Material Switch applies its checked-state callback on one frame and
        // restarts LaunchedEffect on the next; advance both while clock auto-advance is disabled.
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        // After the second edit only the second timer is active. Pump past the full delay and
        // assert exactly one save with BOTH edits applied.
        composeTestRule.mainClock.advanceTimeBy(testDelay + 50)
        composeTestRule.waitForIdle()
        assertEquals(1, savedConfigs.size)
        val saved = savedConfigs.single()
        assertEquals(
            "TOMORROW_IO edit was preserved into the auto-saved config",
            listOf("NWS", "OPEN_METEO", "SILURIAN"),
            saved.visibleSources,
        )
        assertEquals(
            "Use Celsius edit was preserved into the auto-saved config",
            true,
            saved.useCelsius,
        )
    }

    @Test
    fun autoSave_clearsDirtyMarkerWhenPersistedConfigUpdates() {
        val testDelay = 100L
        val persistedConfig = mutableStateOf(sampleConfig)
        composeTestRule.setContent {
            SettingsWindow(
                config = persistedConfig.value,
                onClose = {},
                onSave = { persistedConfig.value = it },
                onExit = {},
                autoSaveDelayMs = testDelay,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.onNodeWithTag("source_checkbox_TOMORROW_IO").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save •").assertIsDisplayed()

        composeTestRule.mainClock.advanceTimeBy(testDelay + 50)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
        composeTestRule.onNodeWithText("Save •").assertDoesNotExist()
        assertEquals(
            listOf("NWS", "OPEN_METEO", "SILURIAN"),
            persistedConfig.value.visibleSources,
        )
    }

    @Test
    fun closeFlushesDirtyDraftButNotAnAlreadyPersistedDraft() {
        val dirtyDraft = sampleConfig.copy(useCelsius = !sampleConfig.useCelsius)
        val savedConfigs = mutableListOf<DesktopConfig>()

        flushSettingsDraft(sampleConfig, dirtyDraft) { savedConfigs += it }
        flushSettingsDraft(dirtyDraft, dirtyDraft) { savedConfigs += it }
        flushSettingsDraft(dirtyDraft, null) { savedConfigs += it }

        assertEquals(listOf(dirtyDraft), savedConfigs)
    }
}
