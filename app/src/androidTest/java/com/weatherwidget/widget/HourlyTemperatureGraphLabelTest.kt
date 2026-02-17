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
 * Instrumented tests for HourlyTemperatureGraphRenderer label placement.
 *
 * These tests verify that temperature labels (LOW, HIGH, START, END) are
 * correctly placed on the hourly graph by inspecting log output from the
 * renderer. Log lines are annotated in HourlyTemperatureGraphRenderer.kt.
 */
@RunWith(AndroidJUnit4::class)
class HourlyTemperatureGraphLabelTest {
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
    ): List<HourlyTemperatureGraphRenderer.HourData> {
        return temps.mapIndexed { index, temp ->
            val dt = startTime.plusHours(index.toLong())
            HourlyTemperatureGraphRenderer.HourData(
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

        HourlyTemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
        )

        val logs = getHourlyGraphLogs()
        assertTrue("Expected LOW label to be drawn at 44°\nLogs:\n$logs", logs.contains("DRAWN LOW") && logs.contains("temp=44.0"))
    }

    @Test
    fun highLabel_isDrawnAtMaximumTemperature() {
        val temps = listOf(44f, 46f, 48f, 51f, 51f, 49f, 47f, 44f)
        val hours = buildHours(temps)

        HourlyTemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
        )

        val logs = getHourlyGraphLogs()
        assertTrue("Expected HIGH label to be drawn at 51°\nLogs:\n$logs", logs.contains("DRAWN HIGH") && logs.contains("temp=51.0"))
    }

    @Test
    fun lowLabel_centeredOnConsecutiveMinPoints() {
        // Two consecutive 39° points at idx 6 and 7; label should center between them
        val temps = listOf(50f, 48f, 46f, 44f, 42f, 40f, 39f, 39f, 40f, 42f)
        val hours = buildHours(temps)

        HourlyTemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
        )

        val logs = getHourlyGraphLogs()

        // Extract LOW label x position
        val lowLine = logs.lines().find { it.contains("DRAWN LOW") }
        assertTrue("Expected LOW label to be drawn\nLogs:\n$logs", lowLine != null)

        // With 10 points at 700px width, indices 6-7 are in the right portion of the graph.
        // Use space-prefixed regex to avoid matching "idx=6" instead of the actual "x=..." field.
        val xMatch = Regex(" x=([\\d.]+)").find(lowLine!!)
        assertTrue("Expected x position in LOW log\nLine: $lowLine", xMatch != null)
        val x = xMatch!!.groupValues[1].toFloat()
        assertTrue(
            "LOW label x=$x should be in right half of graph (past 350px of 700px)",
            x > 350f,
        )
    }

    @Test
    fun overlappingEndLabel_isSkipped() {
        // Low near end of graph — END label should overlap with LOW and be skipped.
        // Use narrow width (200px) so adjacent labels' bounding boxes overlap.
        val temps = listOf(50f, 48f, 46f, 44f, 42f, 41f, 40f, 39f, 39f, 40f)
        val hours = buildHours(temps)

        HourlyTemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 200,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
        )

        val logs = getHourlyGraphLogs()
        assertTrue("Expected LOW to be drawn\nLogs:\n$logs", logs.contains("DRAWN LOW"))
        assertTrue("Expected END to be skipped due to overlap\nLogs:\n$logs", logs.contains("SKIPPED END"))
    }

    @Test
    fun allFourLabels_drawnWhenWellSeparated() {
        // Clear separation: low in middle, high later, start and end far apart
        val temps = listOf(55f, 50f, 45f, 40f, 45f, 50f, 60f, 65f, 70f, 68f, 65f, 60f)
        val hours = buildHours(temps)

        HourlyTemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
        )

        val logs = getHourlyGraphLogs()
        assertTrue("Expected LOW to be drawn\nLogs:\n$logs", logs.contains("DRAWN LOW"))
        assertTrue("Expected HIGH to be drawn\nLogs:\n$logs", logs.contains("DRAWN HIGH"))
        assertTrue("Expected START to be drawn\nLogs:\n$logs", logs.contains("DRAWN START"))
        assertTrue("Expected END to be drawn\nLogs:\n$logs", logs.contains("DRAWN END"))
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
