package com.weatherwidget.widget

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class PrecipitationGraphRendererLogInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun renderGraph_logsEndLabelPlacement() {
        runShellCommand("logcat -c")

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

        PrecipitationGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 700,
            heightPx = 320,
            currentTime = LocalDateTime.now(),
        )

        val precipLogs = runShellCommand("logcat -d -s PrecipGraph:D *:S")
        assertTrue(
            "Expected end label placement log in PrecipGraph, but did not find it.\nLogs:\n$precipLogs",
            precipLogs.contains("PLACED end label: 0%"),
        )
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
