package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Instrumented tests for TemperatureGraphRenderer ghost line and delta logic.
 *
 * Verifies that the relationship between "Official Forecast" (solid line)
 * and "Expected Truth" (ghost line) matches the redesigned UX:
 * labels reflect the corrected temperature (API + Delta).
 */
@RunWith(AndroidJUnit4::class)
class TemperatureGhostLineTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun buildHours(temps: List<Float>): List<TemperatureGraphRenderer.HourData> {
        val startTime = LocalDateTime.of(2026, 2, 26, 10, 0)
        return temps.mapIndexed { index, temp ->
            val dt = startTime.plusHours(index.toLong())
            TemperatureGraphRenderer.HourData(
                dateTime = dt,
                temperature = temp,
                label = "${dt.hour}",
                isCurrentHour = index == 0,
                showLabel = true,
            )
        }
    }

    @Test
    fun labelsReflectCorrectedTemperature_whenNegativeDeltaApplied() {
        // Delta -1.0 means it is colder than forecast.
        // If API says 70, reality (ghost) is 69.
        val temps = listOf(70f, 75f, 80f) // Fewer points, more spread out
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800, // Wider
            heightPx = 500, // Much taller
            currentTime = LocalDateTime.of(2026, 2, 26, 10, 0),
            appliedDelta = -1.0f,
            onLabelPlaced = { placements.add(it) }
        )

        // START label is index 0
        val startLabel = placements.find { it.index == 0 }
        assertTrue("START label (index 0) should be drawn. Found: ${placements.map { it.role }}", startLabel != null)
        
        assertEquals("Raw temperature should be 70 (API)", 70f, startLabel!!.rawTemperature, 0.01f)
        
        // With smoothing [0.25, 0.5, 0.25], smoothed 70 would be (70*0.75 + 75*0.25) = 71.25
        // Corrected would be 71.25 - 1.0 = 70.25
        // Let's just check that it is indeed Corrected = Smoothed + Delta
        val expectedSmoothedValue = 70f * 0.75f + 75f * 0.25f
        assertEquals("Label should be Smoothed + Delta", expectedSmoothedValue - 1.0f, startLabel.temperature, 0.1f)
    }

    @Test
    fun labelsReflectCorrectedTemperature_whenPositiveDeltaApplied() {
        val temps = listOf(70f, 75f, 80f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 500,
            currentTime = LocalDateTime.of(2026, 2, 26, 10, 0),
            appliedDelta = 2.0f,
            onLabelPlaced = { placements.add(it) }
        )

        val startLabel = placements.find { it.index == 0 }
        assertTrue("START label (index 0) should be drawn", startLabel != null)
        
        assertEquals(70f, startLabel!!.rawTemperature, 0.01f)
        val expectedSmoothedValue = 70f * 0.75f + 75f * 0.25f
        assertEquals(expectedSmoothedValue + 2.0f, startLabel.temperature, 0.1f)
    }
}
