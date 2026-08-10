package com.weatherwidget.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The reported bug, driven through the real window: *"sometimes when I change the hourly settings
 * amount and hit save, the setting reverts."*
 *
 * The Settings window is not the only writer of `DesktopConfig`. The popup persists its own state
 * constantly — window move/resize on a 1s debounce, zoom scroll, pan, view switches, day clicks —
 * and each save pushes a NEW baseline `config` down into this window. `currentConfig` used to be
 * `remember(config) { ... }`, keyed on that baseline, so every such save made Compose throw the
 * user's in-progress draft away and re-seed from the persisted value. The observable result was
 * exactly the report: the control snapped back, the "Save •" dirty marker cleared itself, and the
 * subsequent Save click was a silent no-op.
 *
 * These tests push a new baseline while an edit is pending and assert the edit survives.
 */
@Category(LongDuration::class)
class SettingsWindowBaselineChangeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sampleConfig = DesktopConfig(
        lat = 0.0,
        lon = 0.0,
        label = "Test Location",
        visibleSources = listOf("NWS", "OPEN_METEO", "SILURIAN", "TOMORROW_IO"),
        useCelsius = false,
    )

    /** Long enough that auto-save never fires mid-test; every save here is an explicit click. */
    private val noAutoSave = 600_000L

    @Test
    fun pendingEdit_survivesAPopupConfigSave_andSaveStillPersistsIt() {
        val baseline = mutableStateOf(sampleConfig)
        val saved = mutableListOf<DesktopConfig>()
        composeTestRule.setContent {
            val current by baseline
            SettingsWindow(
                config = current,
                onClose = {},
                onSave = { saved += it },
                onExit = {},
                autoSaveDelayMs = noAutoSave,
            )
        }
        composeTestRule.waitForIdle()

        // 1. The user edits a setting. Use the source list: it is a deterministic click target,
        //    unlike dragging a Slider to an exact value.
        composeTestRule.onNodeWithTag("source_checkbox_TOMORROW_IO").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save •").assertIsDisplayed()

        // 2. Before they hit Save, the popup persists something of its own — the window was nudged.
        //    Main writes it off the PERSISTED config, which has no knowledge of the pending edit.
        baseline.value = sampleConfig.copy(windowX = 640f, windowY = 480f)
        composeTestRule.waitForIdle()

        // 3. The edit must still be pending. Before the fix the marker cleared itself here.
        composeTestRule.onNodeWithText("Save •").assertIsDisplayed()

        // 4. Save must persist the edit — and must not rewind the window the popup just moved.
        composeTestRule.onNodeWithTag("save_settings").performClick()
        composeTestRule.waitForIdle()

        assertEquals("exactly one save", 1, saved.size)
        assertEquals(
            "the pending edit must be what gets written",
            listOf("NWS", "OPEN_METEO", "SILURIAN"),
            saved.single().visibleSources,
        )
        assertEquals("the popup's newer window position must not be rewound", 640f, saved.single().windowX)
        assertEquals(480f, saved.single().windowY)
    }

    @Test
    fun repeatedPopupSaves_doNotAccumulateOverTheDraft() {
        // The popup writes on a 1s debounce while a window is being dragged, so a real user gets a
        // burst of new baselines, not one. The draft has to survive all of them.
        val baseline = mutableStateOf(sampleConfig)
        val saved = mutableListOf<DesktopConfig>()
        composeTestRule.setContent {
            val current by baseline
            SettingsWindow(
                config = current,
                onClose = {},
                onSave = { saved += it },
                onExit = {},
                autoSaveDelayMs = noAutoSave,
            )
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("source_checkbox_TOMORROW_IO").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        repeat(8) { step ->
            baseline.value = sampleConfig.copy(windowX = 100f + step * 10f, zoomFactor = 0.2f + step * 0.05f)
            composeTestRule.waitForIdle()
        }

        composeTestRule.onNodeWithText("Save •").assertIsDisplayed()
        composeTestRule.onNodeWithTag("save_settings").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, saved.size)
        assertEquals(
            listOf("NWS", "OPEN_METEO", "SILURIAN"),
            saved.single().visibleSources,
        )
        assertEquals("the last popup zoom must win", 0.55f, saved.single().zoomFactor, 0.0001f)
        assertEquals("the last popup position must win", 170f, saved.single().windowX)
    }

    @Test
    fun aPopupOnlySave_withNoPendingEdit_leavesTheWindowClean() {
        // The other direction: popup churn must not make the Settings window look dirty, or the user
        // gets a permanent "Save •" and a click writes stale popup state back.
        val baseline = mutableStateOf(sampleConfig)
        val saved = mutableListOf<DesktopConfig>()
        composeTestRule.setContent {
            val current by baseline
            SettingsWindow(
                config = current,
                onClose = {},
                onSave = { saved += it },
                onExit = {},
                autoSaveDelayMs = noAutoSave,
            )
        }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Save").assertIsDisplayed()

        baseline.value = sampleConfig.copy(windowX = 900f, viewMode = ViewMode.HOURLY, hourlyOffset = 6)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Save").assertIsDisplayed()

        composeTestRule.onNodeWithTag("save_settings").performClick()
        composeTestRule.waitForIdle()
        assertTrue("a clean Save must not write anything", saved.isEmpty())
    }
}
