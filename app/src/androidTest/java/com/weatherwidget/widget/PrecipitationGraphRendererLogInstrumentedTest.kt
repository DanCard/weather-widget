package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.weatherwidget.R
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

        // Index 5 is our target. After 2 iterations of smoothing [0,0,0,0,0,46,60,70]:
        // Pass 1: [0, 0, 0, 0, 11.5, 29, 48.5, 60.5] (approx)
        // Pass 2: [0, 0, 0, 2.9, 12.8, 29.5, 46.8, 57.0] (approx)
        // Actually, let's look at the failure: "Expected:<36> but was:<37>"
        // So 37 is the value with 2 iterations.
        
        val labelAt5 = placements.find { it.index == 5 }
        
        assertNotNull("Should have placed a label at index 5", labelAt5)
        assertEquals("Label at index 5 should be 46% (raw value)", 46, labelAt5!!.probability)
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

        // Verify 4 am peak label - NOW PRESENT because smoothing doesn't melt it into the anchor
        val label4a = placements.find { it.hourLabel == "4a" }
        assertNotNull("4 am label should be present without smoothing", label4a)
        assertEquals(85, label4a!!.probability)
    }

    @Test
    fun renderGraph_drawsHourIconsForEveryHourInZoomedInSpacing() {
        val start = LocalDateTime.now().minusHours(3)
        val hours =
            (0..5).map { index ->
                PrecipitationGraphRenderer.PrecipHourData(
                    dateTime = start.plusHours(index.toLong()),
                    precipProbability = 30 + index,
                    label = "${index}h",
                    iconRes = R.drawable.ic_weather_clear,
                    isSunny = true,
                    showLabel = true,
                )
            }

        val iconDrawnIndices = mutableListOf<Int>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 320,
            currentTime = start,
            hourLabelSpacingDp = 18f, // narrow/zoomed-in spacing
            onHourIconDrawn = { iconDrawnIndices.add(it) },
        )

        assertEquals(
            "Expected one icon callback per hourly point in zoomed-in spacing",
            hours.indices.toList(),
            iconDrawnIndices,
        )
    }

    @Test
    fun renderGraph_skipsHourIconsWhenGraphWidthIsTooNarrow() {
        val start = LocalDateTime.now().minusHours(2)
        val hours =
            (0..3).map { index ->
                PrecipitationGraphRenderer.PrecipHourData(
                    dateTime = start.plusHours(index.toLong()),
                    precipProbability = 20 + index,
                    label = "${index}h",
                    iconRes = R.drawable.ic_weather_clear,
                    isSunny = true,
                    showLabel = true,
                )
            }

        val iconDrawnIndices = mutableListOf<Int>()

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 360, // below icon threshold
            heightPx = 320,
            currentTime = start,
            hourLabelSpacingDp = 18f,
            onHourIconDrawn = { iconDrawnIndices.add(it) },
        )

        assertTrue(
            "Expected no icon callbacks when graph width is below threshold",
            iconDrawnIndices.isEmpty(),
        )
    }
}
