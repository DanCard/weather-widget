package com.weatherwidget.widget

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.weatherwidget.testutil.IsolatedIntegrationTest
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime

/**
 * Instrumented tests for TemperatureGraphRenderer label clutter.
 *
 * Verifies that minor humps/peaks (e.g., 1 degree changes) don't trigger labels.
 */
@RunWith(AndroidJUnit4::class)
class TemperatureGraphClutterTest : IsolatedIntegrationTest("temp_graph_clutter") {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Before
    override fun setup() {
        super.setup()
        runShellCommand("logcat -c")
    }

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
    fun minorHump_oneDegree_isNotLabeled() {
        // Hump: 60 -> 61 -> 60. This is a local maximum at idx 5, 
        // but it's only 1 degree and likely not worth labeling.
        val temps = listOf(55f, 56f, 58f, 60f, 60f, 61f, 60f, 60f, 58f, 56f, 54f)
        val hours = buildHours(temps)
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000, // Wide enough to avoid overlap skipping
            heightPx = 300,
            currentTime = LocalDateTime.of(2026, 2, 17, 22, 0),
            onLabelPlaced = { placements.add(it) }
        )

        // We expect it to NOT be drawn as "OTHER" (which is the role for local extrema)
        assertFalse("Expected minor 1° hump NOT to be drawn", placements.any { it.role == "OTHER" && it.index == 5 })
    }

    private fun getHourlyGraphLogs(): String {
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
