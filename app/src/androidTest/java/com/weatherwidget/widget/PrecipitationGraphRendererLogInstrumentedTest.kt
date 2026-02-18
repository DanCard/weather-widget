package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class PrecipitationGraphRendererLogInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun renderGraph_logsEndLabelPlacement() {
        // No need to clear logcat anymore

        val start = LocalDateTime.now().minusHours(24)
        val hours =
            (0..24).map { index ->
                val probability =
                    when {
                        index < 6 -> 20
                        index < 10 -> 5
                        else -> 0
                    }
                PrecipitationGraphRenderer.PrecipHourData(
                    dateTime = start.plusHours(index.toLong()),
                    precipProbability = probability,
                    label = "${index}h",
                    isCurrentHour = false,
                    showLabel = false,
                )
            }

        val debugLogs = mutableListOf<String>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 320,
            currentTime = LocalDateTime.now(),
            onDebugLog = { debugLogs.add(it) }
        )

        // Check the callback logs instead of logcat
        val logFound = debugLogs.any { it.contains("PLACED end label: 0%") }
        
        assertTrue(
            "Expected end label placement log in PrecipGraph, but did not find it.\nCaptured logs: $debugLogs",
            logFound,
        )
    }

    @Test
    fun renderGraph_placesFirstRisingLabelBelow() {
        val start = LocalDateTime.now().minusHours(12)
        val hours = listOf(
            PrecipitationGraphRenderer.PrecipHourData(start, 0, "12a", showLabel = false),
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(1), 0, "1a", showLabel = false),
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(2), 0, "2a", showLabel = false),
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(3), 0, "3a", showLabel = false),
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(4), 0, "4a", showLabel = false),
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(5), 46, "5a", showLabel = true), // First positive
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(6), 60, "6a", showLabel = true), // Rising
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(7), 70, "7a", showLabel = true)
        )

        val placements = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { placements.add(it) }
        )

        // Index 5 is our target. After 3 iterations of smoothing [0,0,0,0,0,46,60,70]:
        // Round:  [0, 0, 1, 5, 18, 36, 53, 63]
        // firstPositive should be index 2 (value 1)
        // firstLabeledPositive should be index 5 (value 36)
        
        val labelAt5 = placements.find { it.index == 5 }
        
        assertNotNull("Should have placed a label at index 5", labelAt5)
        assertEquals("Label at index 5 should be 36% after smoothing", 36, labelAt5!!.probability)
        assertTrue("Label at index 5 should have 'firstLabelBelowRuleApplied' set", labelAt5.firstLabelBelowRuleApplied)
        assertFalse("Label at index 5 (first rising labeled) should be placed BELOW (placedAbove=false)", labelAt5.placedAbove)
    }

    @Test
    fun renderGraph_placesFirstRisingAndNearbyPeak() {
        val start = LocalDateTime.now().withHour(0).withMinute(0)
        val hours = listOf(
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(0), 0, "12a", showLabel = false),
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(1), 0, "1a", showLabel = false),
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(2), 0, "2a", showLabel = false),
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(3), 20, "3a", showLabel = true), // First positive, rising
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(4), 85, "4a", showLabel = true), // Sharp Peak at 4 am
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(5), 10, "5a", showLabel = true), // Deep Valley
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(6), 60, "6a", showLabel = true), // Rising
            PrecipitationGraphRenderer.PrecipHourData(start.plusHours(7), 80, "7a", showLabel = true)  // Global Max
        )

        val placements = mutableListOf<PrecipitationGraphRenderer.LabelPlacementDebug>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { placements.add(it) }
        )

        // Verify 3 am label (first positive)
        val label3a = placements.find { it.hourLabel == "3a" }
        assertNotNull("3 am label (first positive) should be present", label3a)
        assertFalse("3 am label should be BELOW", label3a!!.placedAbove)

        // Verify 4 am peak label
        val label4a = placements.find { it.hourLabel == "4a" }
        assertNotNull("4 am peak label should be present", label4a)
        assertTrue("4 am label (peak) should be ABOVE", label4a!!.placedAbove)
    }
}
