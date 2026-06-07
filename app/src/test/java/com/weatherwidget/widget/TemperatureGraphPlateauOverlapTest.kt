package com.weatherwidget.widget

import com.weatherwidget.shared.graph.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@Category(LongDuration::class)
class TemperatureGraphPlateauOverlapTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `identical values on a plateau should not produce multiple overlapping labels`() {
        val placements = mutableListOf<LabelPlacementDebug>()
        val start = LocalDateTime.of(2026, 4, 6, 0, 0)
        
        // 24 hour span. Plateau at 49 degrees from 4am to 8am.
        // We'll split actuals/forecast right in the middle of the plateau to try and trigger
        // both actualLowIndex and forecastLowIndex.
        val temps = (0..23).map { i ->
            when {
                i in 4..8 -> 49.0f
                i < 4 -> 60.0f - i * 2f
                else -> 49.0f + (i - 8) * 2f
            }
        }

        val hours = (0..23).map { i ->
            val time = start.plusHours(i.toLong())
            HourData(
                dateTime = time,
                temperature = temps[i],
                actualTemperature = if (i <= 5) temps[i] else null,
                isActual = i <= 5,
                label = "${time.hour}h"
            )
        }

        val observedAtMs = start.plusHours(5).plusMinutes(30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(6),
            observedAt = observedAtMs,
            lastObservedTemp = 49.0f,
            onLabelPlaced = { 
                placements.add(it) 
            }
        )

        // Find all 49 degree labels
        val plateauLabels = placements.filter { it.temperature == 49.0f }
        
        // We expect only ONE label for the 49 degree dip, or at least no two labels at the SAME X coordinate.
        val distinctX = plateauLabels.map { it.x }.distinct()
        
        assertEquals(
            "Expected only one unique X coordinate for 49 degree labels on this plateau. Placements: $plateauLabels",
            1,
            distinctX.size
        )

        assertEquals(
            "Expected only one 49 degree label for the plateau. Placements: $plateauLabels",
            1,
            plateauLabels.size
        )
    }
}
