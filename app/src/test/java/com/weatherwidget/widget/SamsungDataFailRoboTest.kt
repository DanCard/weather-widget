package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SamsungDataFailRoboTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun samsungNwsData_labelsFiveAmPeak() {
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

        val plateauLabel = placements.find { it.index in 4..7 }

        assertNull(
            "Expected NO label in the 4am-7am plateau (91%) after removing 'Morning High' rule. Placements=${placements.map { "${it.hourLabel}=${it.probability}% (idx=${it.index})" }}",
            plateauLabel
        )

        val peakLabel = placements.find { it.index == 10 }
        assertNotNull("10am peak should be labeled", peakLabel)
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