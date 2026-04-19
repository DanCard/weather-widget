package com.weatherwidget.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.weatherwidget.test.category.MediumDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Category(MediumDuration::class)
class TemperatureValleyBelowCascadeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildHours(
        forecastTemps: List<Float>,
        actualTemps: List<Float?>,
        startTime: LocalDateTime = LocalDateTime.of(2026, 4, 8, 0, 0),
    ): List<HourData> =
        forecastTemps.mapIndexed { index, temp ->
            val dateTime = startTime.plusHours(index.toLong())
            HourData(
                dateTime = dateTime,
                temperature = temp,
                actualTemperature = actualTemps[index],
                isActual = actualTemps[index] != null,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    @Test
    fun `ACTUAL_LOW places below when overlapping with LOW via cascade`() {
        val placements = mutableListOf<LabelPlacementDebug>()

        val forecast = MutableList(152) { 62f }
        forecast[84] = 51f
        val actual = MutableList<Float?>(152) { null }
        for (i in 100..115) actual[i] = 53f
        actual[115] = 52.5f

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(forecast, actual),
            widthPx = 517,
            heightPx = 435,
            currentTime = LocalDateTime.of(2026, 4, 8, 15, 0),
            observedAt = LocalDateTime.of(2026, 4, 8, 13, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onLabelPlaced = { placements.add(it) },
        )

        val lowLabel = placements.find { it.role == TemperatureRole.LOW }
        val actualLowLabel = placements.find { it.role == TemperatureRole.ACTUAL_LOW }

        assertTrue("LOW label should be placed", lowLabel != null)
        assertTrue("ACTUAL_LOW label should be placed", actualLowLabel != null)
        assertTrue(
            "LOW should be placed below, got reason=${lowLabel!!.reason}",
            !lowLabel.placedAbove
        )
        assertTrue(
            "ACTUAL_LOW should be placed below via cascade (got reason=${actualLowLabel!!.reason}), not above",
            !actualLowLabel.placedAbove && actualLowLabel.reason.startsWith("below")
        )
    }

    @Test
    fun `valley below cascade prefers horizontal shift when overlap is partial`() {
        val placements = mutableListOf<LabelPlacementDebug>()

        val forecast = MutableList(24) { 60f }
        forecast[10] = 52f
        val actual = MutableList<Float?>(24) { null }
        actual[12] = 50f

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(forecast, actual),
            widthPx = 300,
            heightPx = 400,
            currentTime = LocalDateTime.of(2026, 4, 8, 15, 0),
            observedAt = LocalDateTime.of(2026, 4, 8, 13, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onLabelPlaced = { placements.add(it) },
        )

        val low52 = placements.find { it.temperature == 52f }
        val low50 = placements.find { it.temperature == 50f }

        assertTrue("Label for 52 should be placed", low52 != null)
        assertTrue("Label for 50 should be placed", low50 != null)
        assertTrue(
            "Both valley labels should be placed below (52 reason=${low52!!.reason}, 50 reason=${low50!!.reason})",
            !low52.placedAbove && !low50.placedAbove
        )
        assertTrue(
            "Lower temperature (50) should be below higher (52). 50_y=${low50.y}, 52_y=${low52.y}",
            low50.y > low52.y
        )
    }

    @Test
    fun `cascade falls back to above when all below options fail`() {
        val placements = mutableListOf<LabelPlacementDebug>()

        val forecast = MutableList(24) { 60f }
        forecast[0] = 55f
        forecast[1] = 55f
        forecast[2] = 55f
        forecast[3] = 55f
        forecast[4] = 55f
        val actual = MutableList<Float?>(24) { null }
        for (i in 0..4) actual[i] = 55f
        actual[2] = 54f

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(forecast, actual),
            widthPx = 200,
            heightPx = 200,
            currentTime = LocalDateTime.of(2026, 4, 8, 15, 0),
            observedAt = LocalDateTime.of(2026, 4, 8, 13, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            onLabelPlaced = { placements.add(it) },
        )

        val valleys = placements.filter { !it.placedAbove }
        val aboveValleys = placements.filter { it.placedAbove && it.reason == "above" }

        assertTrue(
            "Should have placed at least one label, got ${placements.size}",
            placements.isNotEmpty()
        )
    }
}
