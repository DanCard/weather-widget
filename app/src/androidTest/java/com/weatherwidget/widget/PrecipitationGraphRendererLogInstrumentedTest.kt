package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
}
