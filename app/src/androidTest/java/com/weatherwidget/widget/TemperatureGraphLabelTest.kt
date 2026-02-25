package com.weatherwidget.widget

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
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
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun clearLogs() {
        runShellCommand("logcat -c")
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
        // Low near end of graph — previously END label would overlap with LOW and be skipped.
        // Now, they can take opposite sides (one BELOW, one ABOVE) and both be drawn.
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
        assertTrue("Expected END to be drawn (found other side)", placements.any { it.role == "END" })
    }

    @Test
    fun allFourLabels_drawnWhenWellSeparated() {
        // Clear separation: low in middle, high later, start and end far apart
        val temps = listOf(55f, 50f, 45f, 40f, 45f, 50f, 60f, 65f, 70f, 68f, 65f, 60f)
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

        // Use tall bitmap (400px) to ensure room above (12dp padding = 36-48px)
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 400,
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

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 400,
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
        assertTrue("Expected HIGH label to be drawn", highPlacement != null)
        // Should fall back to BELOW because ABOVE is off-screen
        assertFalse("Expected HIGH label to fall back BELOW when no room ABOVE", highPlacement!!.placedAbove)
    }

    private fun getHourlyGraphLogs(): String {
        // Small delay for log buffer to flush
        Thread.sleep(100)
        return runShellCommand("logcat -d -s HourlyGraph:D *:S")
    }

    private fun runShellCommand(command: String): String {
        val parcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(parcelFileDescriptor).use { input ->
            BufferedReader(InputStreamReader(input)).use { reader ->
                buildString {
                    while (true) {
                        val line = reader.readLine() ?: break
                        appendLine(line)
                    }
                }
            }
        }
    }
}
