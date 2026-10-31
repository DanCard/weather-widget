package com.weatherwidget.desktop

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.weatherwidget.shared.util.WeatherSourceOrdering
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Settings → Weather Data Sources in two groups, matching Android: In rotation (≡ drags, ✕ removes)
 * and Available (+ adds). plans/261015-settings-sources-drag-reorder-and-two-groups.md, tests 9–11.
 */
@Category(LongDuration::class)
class SettingsWindowSourceListTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val enabled = listOf("NWS", "OPEN_METEO", "SILURIAN")
    private val drafts = mutableListOf<DesktopConfig>()

    private fun launch() {
        composeTestRule.setContent {
            SettingsWindow(
                config = DesktopConfig(
                    lat = 37.42, lon = -122.08, label = "Test Location",
                    settings = DesktopSettings(visibleSources = enabled, useCelsius = false),
                ),
                onClose = {},
                onSave = {},
                onExit = {},
                autoSaveDelayMs = 60_000L,
                onDraftChanged = { drafts += it },
            )
        }
        composeTestRule.waitForIdle()
    }

    private fun draftSources(): List<String> = drafts.last().settings.visibleSources

    private fun tag(t: String): SemanticsNodeInteraction = composeTestRule.onNodeWithTag(t, useUnmergedTree = true)

    // 9
    @Test
    fun enabledSourcesHaveRemove_theRestHaveAdd() {
        launch()
        enabled.forEach {
            tag("source_drag_$it").assertExists()
            tag("source_remove_$it").assertExists()
            tag("source_add_$it").assertDoesNotExist()
        }
        WeatherSourceOrdering.available(enabled).forEach {
            tag("source_add_${it.id}").assertExists()
            tag("source_remove_${it.id}").assertDoesNotExist()
        }
    }

    @Test
    fun removeThenAdd_movesASourceBetweenGroups() {
        launch()
        tag("source_remove_OPEN_METEO").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf("NWS", "SILURIAN"), draftSources())

        tag("source_add_OPEN_METEO").performScrollTo().performClick()
        composeTestRule.waitForIdle()
        assertEquals("added sources append", listOf("NWS", "SILURIAN", "OPEN_METEO"), draftSources())
    }

    @Test
    fun draggingTheRowTextDown_reorders() {
        launch()
        val text = tag("source_text_NWS").performScrollTo()
        val rowHeight = tag("source_row_NWS").fetchSemanticsNode().size.height.toFloat()
        text.performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(0f, rowHeight * 2.2f / 20)) }
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(listOf("OPEN_METEO", "SILURIAN", "NWS"), draftSources())
    }

    // 10
    @Test
    fun draggingTheHandleDown_reordersOnceOnRelease() {
        launch()
        val handle = tag("source_drag_NWS").performScrollTo()
        val rowHeight = tag("source_row_NWS").fetchSemanticsNode().size.height.toFloat()
        val draftsBefore = drafts.size
        handle.performTouchInput {
            down(center)
            // Past the touch slop, then two rows down, in steps like a real drag.
            repeat(20) { moveBy(Offset(0f, rowHeight * 2.2f / 20)) }
        }
        composeTestRule.waitForIdle()
        assertEquals("not saved mid-drag", draftsBefore, drafts.size)
        handle.performTouchInput { up() }
        composeTestRule.waitForIdle()

        assertEquals(listOf("OPEN_METEO", "SILURIAN", "NWS"), draftSources())
        assertEquals("one draft per drag", draftsBefore + 1, drafts.size)
    }

    // 11
    @Test
    fun moveUpAndDownAccessibilityActions_reorder() {
        launch()
        fun labels(id: String): List<String> =
            tag("source_row_$id").fetchSemanticsNode().config
                .getOrNull(SemanticsActions.CustomActions)
                ?.map { it.label }.orEmpty()

        assertEquals(listOf("Move down"), labels("NWS"))
        assertEquals(listOf("Move up", "Move down"), labels("OPEN_METEO"))
        assertEquals(listOf("Move up"), labels("SILURIAN"))

        val moveUp = tag("source_row_SILURIAN").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .first { it.label == "Move up" }
        composeTestRule.runOnIdle { assertTrue(moveUp.action()) }
        composeTestRule.waitForIdle()
        assertEquals(listOf("NWS", "SILURIAN", "OPEN_METEO"), draftSources())
    }
}
