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
    fun labelsReflectOriginalTemperature_ignoringDelta() {
        // Delta -1.0 means it is colder than forecast.
        // API says 70.
        // Labels should now show the API (70) and ignore the delta.
        val temps = listOf(70f, 75f, 80f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 500,
            currentTime = LocalDateTime.of(2026, 2, 26, 10, 0),
            appliedDelta = -1.0f,
            onLabelPlaced = { placements.add(it) }
        )

        val startLabel = placements.find { it.index == 0 }
        assertTrue("START label (index 0) should be drawn", startLabel != null)
        
        assertEquals("Raw temperature should be 70 (API)", 70f, startLabel!!.rawTemperature, 0.01f)
        
        // Labels should now represent the ORIGINAL temperature (smoothing removed)
        val expectedValue = 70f
        assertEquals("Label should be Original value, ignoring Delta", expectedValue, startLabel.temperature, 0.1f)
    }
}
