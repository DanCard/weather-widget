package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RainPeakLabelRoboTest {
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun fiveAmPeak_isCorrectlyLabeled() {
        val start = LocalDateTime.of(2026, 2, 17, 0, 0)

        val probs = listOf(
            10, 12, 15, 25, 40, 60, 45, 35, 30, 25,
            20, 15, 10, 5, 0, 0, 0, 10, 20, 40,
            60, 50, 40, 30, 20
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
            currentTime = start.plusHours(2),
            onLabelPlaced = { placements.add(it) }
        )

        val fiveAmLabel = placements.find { it.index == 5 || it.hourLabel == "5a" }

        assertNotNull(
            "Expected a label at 5 AM for the precipitation peak. Placements=${placements.map { "${it.hourLabel}=${it.probability}%" }}",
            fiveAmLabel
        )

        assertTrue(
            "The 5 AM label should be identified as a peak. Placement=$fiveAmLabel",
            fiveAmLabel!!.isPeak
        )
    }

    @Test
    fun smallProminencePeak_isLabeled_underNewThresholds() {
        val start = LocalDateTime.of(2026, 2, 17, 0, 0)
        val probs = List(25) { i ->
            when (i) {
                10 -> 40
                in 7..9 -> 30 + (i - 7)
                in 11..13 -> 31 - (i - 11)
                else -> 10
            }
        }

        val hours = probs.mapIndexed { i, p ->
            val dt = start.plusHours(i.toLong())
            PrecipitationGraphRenderer.PrecipHourData(
                dateTime = dt,
                precipProbability = p,
                label = formatHour(dt.hour),
                isCurrentHour = false,
                showLabel = false
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

        val tenAmLabel = placements.find { it.index == 10 || it.hourLabel == "10a" }

        assertNotNull(
            "Expected a label at 10 AM for the smaller precipitation peak. Placements=${placements.map { "${it.hourLabel}=${it.probability}%" }}",
            tenAmLabel
        )
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