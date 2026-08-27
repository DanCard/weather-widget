package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * Desktop's half of the glyph-obstacle wiring, mirroring
 * `CloudCoverGraphGlyphObstacleRobolectricTest` on Android.
 *
 * The shared geometry test builds its own obstacle list and so cannot notice a renderer failing to
 * pass one — the defect that shipped 2026-08-27, where the glyph pass and the placement search were
 * each correct in isolation and simply never introduced. This drives the real composable and
 * asserts the annotation clears the boxes the composable itself fed to the search.
 *
 * Unlike the Android test, this one runs against a real font engine (Skia), so the annotation is its
 * true width and the scene is to scale.
 */
@Category(LongDuration::class)
class CloudCoverGraphGlyphObstacleTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val hourMs = 3_600_000L

    /** An afternoon of cirrus over a clear low deck: heavy mid/high, near-zero low. */
    private fun cirrusOverClearDeck(nowMs: Long): List<HourlyForecast> {
        val topOfHour = nowMs - (nowMs % hourMs)
        return (-8..12).map { offset ->
            HourlyForecast(
                dateTime = topOfHour + offset * hourMs,
                temperature = 64f,
                condition = "Cloudy",
                cloudCover = 100,
                cloudCoverLow = 3,
                cloudCoverMid = 85,
                cloudCoverHigh = 100,
            )
        }
    }

    /** Observed low cloud for the elapsed hours, so the annotation is not suppressed. */
    private fun retroActual(nowMs: Long): Map<Long, Int> {
        val topOfHour = nowMs - (nowMs % hourMs)
        return (-8..0).associate { (topOfHour + it * hourMs) to 5 }
    }

    private fun renderAndCapture(): CloudGraphPlacementDebug? {
        var debug: CloudGraphPlacementDebug? = null
        val now = System.currentTimeMillis()
        composeTestRule.setContent {
            Box(Modifier.requiredSize(1200.dp, 700.dp)) {
                CloudCoverGraph(
                    hourly = cirrusOverClearDeck(now),
                    retroCloudActual = retroActual(now),
                    // SILURIAN is forecast-only, so its actuals are borrowed and the annotation
                    // naming the borrowed provider is drawn. No preference is installed, so the
                    // resolver falls back to its default and the test leaks no global state.
                    displaySourceId = "SILURIAN",
                    modifier = Modifier.requiredSize(1200.dp, 700.dp),
                    onPlacementDebug = { debug = it },
                )
            }
        }
        composeTestRule.waitForIdle()
        return debug
    }

    @Test
    fun `the composable hands the glyph trails to the placement search`() {
        // Guards the assertion below from vacuity: with no obstacles reaching the search, "clears
        // every glyph" is true of any placement at all.
        val debug = renderAndCapture()
        assertNotNull("expected the cloud graph to emit placement debug", debug)
        assertTrue(
            "expected glyph obstacles for a heavy mid/high scene, got ${debug!!.layerGlyphBounds.size}",
            debug.layerGlyphBounds.size > 20,
        )
        assertTrue(
            "glyph boxes must have area",
            debug.layerGlyphBounds.all { it.right > it.left && it.bottom > it.top },
        )
    }

    @Test
    fun `the actuals source annotation is placed clear of every glyph`() {
        val debug = renderAndCapture()!!
        val placement = debug.actualsSourcePlacement
        assertNotNull("expected the annotation to be placed on a plot with room below the trails", placement)

        val hit = debug.layerGlyphBounds.filter { it.intersects(placement!!.box) }
        assertTrue(
            "annotation overlaps ${hit.size} glyph box(es): box=${placement!!.box} first=${hit.firstOrNull()}",
            hit.isEmpty(),
        )
    }
}
