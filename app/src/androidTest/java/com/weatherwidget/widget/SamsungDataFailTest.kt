package com.weatherwidget.widget

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weatherwidget.testutil.IsolatedIntegrationTest
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDateTime

/**
 * Instrumented test based on actual Samsung NWS data from Feb 17, 2026.
 * The 4-5am transition (77% -> 91%) was not being labeled.
 */
@RunWith(AndroidJUnit4::class)
class SamsungDataFailTest : IsolatedIntegrationTest("samsung_data_fail") {

    @Test
    fun samsungNwsData_labelsFiveAmPeak() {
        // Data from Samsung NWS Feb 17, 2026
        // 00:00 | 77
        // 01:00 | 77
        // 02:00 | 77
        // 03:00 | 77
        // 04:00 | 91
        // 05:00 | 91
        // 06:00 | 91
        // 07:00 | 91
        // 08:00 | 93
        // 09:00 | 98
        // 10:00 | 99  (Global Max)
        // 11:00 | 88
        
        val start = LocalDateTime.of(2026, 2, 17, 0, 0)
        val probs = listOf(
            77, 77, 77, 77, 91, 91, 91, 91, 93, 98, 99, 88, 58, 68, 59, 42, 55, 80, 93, 70, 75, 61, 57, 54, 50
        )
        
        val hours = probs.mapIndexed { i, p ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = p,
                label = formatHour(dt.hour),
                isCurrentHour = false,
                showLabel = i % 3 == 0
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

        // Find a label in the 4 AM to 7 AM range (the plateau at 91%).
        // After removing the 'Morning High' rule, this plateau should NOT be labeled
        // unless it's a mathematical peak.
        val plateauLabel = placements.find { it.index in 4..7 }
        
        org.junit.Assert.assertNull(
            "Expected NO label in the 4am-7am plateau (91%) after removing 'Morning High' rule. Placements=${placements.map { "${it.hourLabel}=${it.probability}% (idx=${it.index})" }}",
            plateauLabel
        )

        // The next label should be the 9am peak (idx=9)
        val peakLabel = placements.find { it.index == 9 }
        assertNotNull("9am peak should still be labeled", peakLabel)
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
