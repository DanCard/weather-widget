package com.weatherwidget.widget

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

@RunWith(AndroidJUnit4::class)
class HourlyGraphLabelTest {

    @Test
    fun verifyLocalMinLabelIsDrawn() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val now = LocalDateTime.of(2026, 2, 17, 4, 0) // 4 AM

        // Data pattern mimicking the issue:
        // Start (04:00): 44.8 (Smoothed ~45)
        // ...
        // Local Min (07:00): 44.0
        // ...
        val hours = listOf(
            createHour(now.plusHours(0), 46.0f), // 46 -> 45.75
            createHour(now.plusHours(1), 45.0f), // 45 -> 45.25
            createHour(now.plusHours(2), 45.0f), // 45 -> 44.75
            createHour(now.plusHours(3), 44.0f), // 44 -> 44.25
            createHour(now.plusHours(4), 44.0f), // 44 -> 44.0 (Min)
            createHour(now.plusHours(5), 44.0f), // 44 -> 44.0 (Min)
            createHour(now.plusHours(6), 44.0f), // 44 -> 44.5
            createHour(now.plusHours(7), 46.0f),
            createHour(now.plusHours(8), 47.0f)
        )

        val drawnLabels = mutableListOf<String>()

        HourlyTemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1080, // Typical width
            heightPx = 400, // Typical height
            currentTime = now,
            onLabelDrawn = { drawnLabels.add(it) }
        )

        println("Labels: $drawnLabels")

        // We expect the Local Min (44) to be DRAWN
        // We expect the Start (approx 46/45) to be SKIPPED or drawn if space allows
        // Given priority change, Local Min (44) MUST be drawn.

        // Check for 44
        val drawn44 = drawnLabels.any { it.contains("DRAWN") && it.contains("val=44") }
        assertTrue("Local minimum (44) should be drawn. Actual: $drawnLabels", drawn44)
    }

    private fun createHour(time: LocalDateTime, temp: Float): HourlyTemperatureGraphRenderer.HourData {
        return HourlyTemperatureGraphRenderer.HourData(
            dateTime = time,
            temperature = temp,
            label = "${time.hour}h"
        )
    }
}
