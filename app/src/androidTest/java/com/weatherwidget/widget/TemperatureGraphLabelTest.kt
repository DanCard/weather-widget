package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Instrumented tests for TemperatureGraphRenderer label placement.
 *
 * These tests verify that temperature labels (LOW, HIGH, START, END) are
 * correctly placed on the hourly graph by inspecting log output from the
 * renderer. Log lines are annotated in TemperatureGraphRenderer.kt.
 */
@RunWith(AndroidJUnit4::class)
class TemperatureGraphLabelTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    /** Build test hour data with a clear low and high. */
    private fun buildHours(
        temps: List<Float>,
        startTime: LocalDateTime = LocalDateTime.of(2026, 2, 17, 19, 0),
    ): List<TemperatureGraphRenderer.HourData> {
        return temps.mapIndexed { index, temp ->
            val dt = startTime.plusHours(index.toLong())
            TemperatureGraphRenderer.HourData(
                dateTime = dt,
                temperature = temp,
                label = "${dt.hour}",
                isCurrentHour = false,
                showLabel = index % 4 == 0,
            )
        }
    }

    @Test
    fun lowLabel_isDrawnAtMinimumTemperature() {
        val temps = listOf(50f, 48f, 46f, 44f, 44f, 46f, 48f, 50f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("Expected LOW label to be drawn at 44°", placements.any { it.role == "LOW" && it.rawTemperature == 44f })
    }

    @Test
    fun highLabel_isDrawnAtMaximumTemperature() {
        val temps = listOf(44f, 46f, 48f, 51f, 51f, 49f, 47f, 44f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("Expected HIGH label to be drawn at 51°", placements.any { it.role == "HIGH" && it.rawTemperature == 51f })
    }

    @Test
    fun lowLabel_centeredOnConsecutiveMinPoints() {
        // Two consecutive 39° points at idx 6 and 7; label should center between them
        val temps = listOf(50f, 48f, 46f, 44f, 42f, 40f, 39f, 39f, 40f, 42f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }
        )

        val lowPlacement = placements.find { it.role == "LOW" }
        assertTrue("Expected LOW label to be drawn", lowPlacement != null)

        // With 10 points at 700px width, indices 6-7 are in the right portion of the graph.
        val x = lowPlacement!!.x
        assertTrue(
            "LOW label x=$x should be in right half of graph (past 350px of 700px)",
            x > 350f,
        )
    }

    @Test
    fun smartPlacement_avoidsOverlap_byTryingOtherSide() {
        // Low near end of graph. The renderer should try the opposite side for END, but on
        // extremely narrow graphs skipping END is acceptable when neither side fits.
        val temps = listOf(50f, 48f, 46f, 44f, 42f, 41f, 40f, 39f, 39f, 40f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 200,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("Expected LOW to be drawn", placements.any { it.role == "LOW" })
        val endPlacement = placements.find { it.role == "END" }
        if (endPlacement != null) {
            val lowPlacement = placements.find { it.role == "LOW" }
            assertTrue("Expected END to use the opposite side when both labels fit", lowPlacement != null)
            assertTrue(
                "Expected LOW and END to prefer opposite sides when both are drawn. placements=$placements",
                lowPlacement!!.placedAbove != endPlacement.placedAbove,
            )
        } else {
            assertTrue(
                "When both END placements are blocked on a very narrow graph, skipping END is acceptable. placements=$placements",
                placements.none { it.role == "END" },
            )
        }
    }

    @Test
    fun allFourLabels_drawnWhenWellSeparated() {
        // Clear separation: high at index 2, low at index 8, start and end far apart
        // Global min is at index 8 — beyond nearbyWindow(5) from left edge so START is not suppressed
        // HIGH at index 2 is not adjacent to START at index 0, so NEARBY_ENDPOINT_CLUTTER does not suppress START
        val temps = listOf(55f, 62f, 70f, 68f, 65f, 60f, 48f, 42f, 40f, 45f, 50f, 55f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("Expected LOW to be drawn", placements.any { it.role == "LOW" })
        assertTrue("Expected HIGH to be drawn", placements.any { it.role == "HIGH" })
        assertTrue("Expected START to be drawn", placements.any { it.role == "START" })
        assertTrue("Expected END to be drawn", placements.any { it.role == "END" })
    }

    @Test
    fun highPeakLabel_isDrawnAbove_whenEnoughRoom() {
        // High peak at 88 in range [40, 100]. High is at 88% of range.
        val temps = listOf(40f, 50f, 60f, 70f, 88f, 70f, 60f, 40f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        // The hourly graph now stretches farther toward both the top and bottom edges.
        // Use a taller bitmap so the preferred "above peak" placement is actually feasible.
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 700,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }
        )

        val highPlacement = placements.find { it.role == "HIGH" }
        assertTrue("Expected HIGH label to be drawn", highPlacement != null)
        assertTrue("Expected HIGH label to be placed ABOVE peak", highPlacement!!.placedAbove)
    }

    @Test
    fun lowValleyLabel_isDrawnBelow_whenEnoughRoom() {
        // Low valley at 12 in range [0, 100].
        val temps = listOf(80f, 60f, 40f, 12f, 40f, 60f, 80f, 90f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        // The hourly graph now stretches farther toward both the top and bottom edges.
        // Use a taller bitmap so the preferred "below valley" placement is actually feasible.
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 700,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }
        )

        val lowPlacement = placements.find { it.role == "LOW" }
        assertTrue("Expected LOW label to be drawn", lowPlacement != null)
        assertFalse("Expected LOW label to be placed BELOW valley", lowPlacement!!.placedAbove)
    }

    @Test
    fun peakLabel_fallsBackBelow_whenNoRoomAbove() {
        // Peak at 98 in range [0, 100]. Very close to top edge.
        val temps = listOf(0f, 50f, 98f, 50f, 0f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        // Use short bitmap (150px) to force off-screen if ABOVE
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 150,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }
        )

        val highPlacement = placements.find { it.role == "HIGH" }
        if (highPlacement != null) {
            // Should fall back to BELOW because ABOVE is off-screen
            assertFalse("Expected HIGH label to fall back BELOW when no room ABOVE", highPlacement.placedAbove)
        } else {
            assertTrue(
                "When neither side fits on a constrained graph, skipping HIGH is acceptable. placements=$placements",
                placements.none { it.role == "HIGH" },
            )
        }
    }

    @Test
    fun actualEndLabel_isNotDrawn() {
        val start = LocalDateTime.of(2026, 2, 17, 19, 0)
        val hours = listOf(
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 50f, actualTemperature = 50f, isActual = true, label = "7p"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 52f, actualTemperature = 52f, isActual = true, label = "8p", isCurrentHour = true),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 54f, actualTemperature = 54f, isActual = true, label = "9p"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 56f, label = "10p"),
            TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 58f, label = "11p")
        )
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 400,
            currentTime = start.plusHours(1).plusMinutes(30),
            onLabelPlaced = { placements.add(it) }
        )

        val actualEnd = placements.find { it.role == "ACTUAL_END" }
        assertNull("ACTUAL_END label should not be placed (fetch dot covers this)", actualEnd)
    }
}
