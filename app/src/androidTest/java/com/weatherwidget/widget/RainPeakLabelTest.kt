package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Instrumented test to verify that peaks in the rain chance graph (like the 5 AM peak)
 * are correctly labeled according to the updated prominence thresholds.
 */
@RunWith(AndroidJUnit4::class)
class RainPeakLabelTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun fiveAmPeak_isCorrectlyLabeled() {
        // Create a 24-hour scenario starting at midnight
        val start = LocalDateTime.of(2026, 2, 17, 0, 0)
        
        // Scenario: A peak at 5 AM (index 5)
        // Values: 10, 15, 20, 30, 45, 60, 40, 30, 25, 20...
        // The peak is 60%, with a prominence of (60-10) on left and (60-20) on right.
        // Even with smoothing, this should stand out.
        val probs = listOf(
            10, 12, 15, 25, 40, 60, 45, 35, 30, 25,
            20, 15, 10, 5, 0, 0, 0, 10, 20, 40,
            60, 50, 40, 30, 20
        )
        
        val hours = probs.mapIndexed { i, p ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = p,
                label = formatHour(dt.hour),
                isCurrentHour = false,
                showLabel = i % 3 == 0 // Simulate normal interval labels
            )
        }

        val placements = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1080,
            heightPx = 400,
            currentTime = start.plusHours(2), // Now is 2am
            onLabelPlaced = { placements.add(it) }
        )

        // Find the 5 AM label (index 5)
        val fiveAmLabel = placements.find { it.index == 5 || it.hourLabel == "5a" }
        
        assertNotNull(
            "Expected a label at 5 AM for the precipitation peak. Placements=${placements.map { "${it.hourLabel}=${it.probability}%" }}",
            fiveAmLabel
        )
        
        assertTrue(
            "The 5 AM label should be identified as a peak. Placement=$fiveAmLabel",
            fiveAmLabel!!.isPeak
        )
    }

    @Test
    fun smallProminencePeak_isLabeled_underNewThresholds() {
        // Create a scenario with a smaller peak that would have been missed by the old 14% threshold.
        // Peak at 10 AM (index 10) with value 40%. 
        // Neighbors: 9 AM=31%, 11 AM=31%. Prominence = 9%.
        // Old threshold (14) would miss this. New threshold (10) should catch it if it's considered mandatory,
        // or the candidate threshold (8) should catch it if it's not dropped by de-cluttering.
        
        val start = LocalDateTime.of(2026, 2, 17, 0, 0)
        val probs = List(25) { i ->
            when (i) {
                10 -> 40
                in 7..9 -> 30 + (i - 7)
                in 11..13 -> 31 - (i - 11)
                else -> 10
            }
        }
        
        val hours = probs.mapIndexed { i, p ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = p,
                label = formatHour(dt.hour),
                isCurrentHour = false,
                showLabel = false
            )
        }

        val placements = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()
        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1080,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { placements.add(it) }
        )

        val tenAmLabel = placements.find { it.index == 10 || it.hourLabel == "10a" }
        
        assertNotNull(
            "Expected a label at 10 AM for the smaller precipitation peak. Placements=${placements.map { "${it.hourLabel}=${it.probability}%" }}",
            tenAmLabel
        )
    }

    private fun formatHour(hour24: Int): String {
        val h = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val suffix = if (hour24 < 12) "a" else "p"
        return "$h$suffix"
    }
}
