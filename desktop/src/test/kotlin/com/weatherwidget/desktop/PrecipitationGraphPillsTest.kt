package com.weatherwidget.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.test.category.LongDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.experimental.categories.Category

/**
 * The popup's precipitation graph carries two pills — "Radar" and "Sim loop" — placed by the shared
 * `SimLoopPill.placeRadarAndSimLoop`, each opening its own window, their click areas apart.
 * Android's half: `PrecipTouchRoutingRoboTest`. See `plans/261021-radar-button-on-precip-graph.md`.
 */
@Category(LongDuration::class)
class PrecipitationGraphPillsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val hourMs = 3_600_000L
    private val now = System.currentTimeMillis()
    private val topOfHour = now - (now % hourMs)

    private val dryDay = (-6..18).map { offset ->
        HourlyForecast(dateTime = topOfHour + offset * hourMs, temperature = 60f, condition = "Clear", precipProbability = 0)
    }

    private var radarOpened = 0
    private var simLoopOpened = 0

    private fun render(lat: Double, lon: Double, source: String = "OPEN_METEO") {
        composeTestRule.setContent {
            Box(Modifier.requiredSize(900.dp, 400.dp)) {
                PrecipitationGraph(
                    hourly = dryDay,
                    displaySourceId = source,
                    latitude = lat,
                    longitude = lon,
                    modifier = Modifier.requiredSize(900.dp, 400.dp),
                    onOpenSimLoop = { simLoopOpened++ },
                    onOpenRadar = { radarOpened++ },
                )
            }
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun `both pills open their own window and their click areas never overlap`() {
        render(37.42, -122.09)
        val pills = composeTestRule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertEquals("radar + sim loop", 2, pills.size)
        assertFalse(
            "click areas overlap: ${pills[0].boundsInRoot} / ${pills[1].boundsInRoot}",
            pills[0].boundsInRoot.overlaps(pills[1].boundsInRoot),
        )
        for (i in pills.indices) composeTestRule.onAllNodes(hasClickAction())[i].performClick()
        assertEquals(1, radarOpened)
        assertEquals(1, simLoopOpened)
    }

    @Test
    fun `radar is offered where sim loop is not`() {
        // Silurian in Kyiv: off WeatherNext's map, so no sim loop — radar still offered.
        render(50.45, 30.52, source = "SILURIAN")
        val pills = composeTestRule.onAllNodes(hasClickAction())
        pills.assertCountEquals(1)
        pills[0].performClick()
        assertEquals(1, radarOpened)
        assertEquals(0, simLoopOpened)
    }
}
