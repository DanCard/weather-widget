package com.weatherwidget.widget

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TemperatureGraphLabelPlacementRobolectricTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun buildHours(
        temps: List<Float>,
        startTime: LocalDateTime = LocalDateTime.of(2026, 3, 19, 10, 0),
    ): List<TemperatureGraphRenderer.HourData> =
        temps.mapIndexed { index, temp ->
            val dateTime = startTime.plusHours(index.toLong())
            TemperatureGraphRenderer.HourData(
                dateTime = dateTime,
                temperature = temp,
                label = "${dateTime.hour}",
                showLabel = true,
            )
        }

    @Test
    fun `peak falls back below when above placement would leave the screen`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(listOf(0f, 50f, 98f, 50f, 0f)),
            widthPx = 700,
            heightPx = 24,
            currentTime = LocalDateTime.of(2026, 3, 19, 12, 0),
            onLabelPlaced = { placements.add(it) },
        )

        val highPlacement = placements.find { it.role == "HIGH" }
        if (highPlacement != null) {
            assertFalse(
                "Expected constrained HIGH label to avoid above-placement when it would be off-screen. placement=$highPlacement",
                highPlacement.placedAbove,
            )
        } else {
            assertTrue(
                "When neither side fits, skipping the constrained HIGH label is acceptable. placements=$placements",
                placements.none { it.role == "HIGH" },
            )
        }
    }

    @Test
    fun `peak label above stays close to the forecast line`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        var points: TemperatureGraphRenderer.PointsDebug? = null

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = buildHours(listOf(40f, 55f, 70f, 89f, 70f, 55f, 40f)),
            widthPx = 700,
            heightPx = 420,
            currentTime = LocalDateTime.of(2026, 3, 19, 12, 0),
            onLabelPlaced = { placements.add(it) },
            onPointsResolved = { points = it },
        )

        val highPlacement = placements.find { it.role == "HIGH" }
        assertNotNull("Expected HIGH label to be drawn. placements=$placements", highPlacement)
        assertTrue("Expected HIGH label to prefer above when room exists. placement=$highPlacement", highPlacement!!.placedAbove)

        val highPoint = requireNotNull(points).forecast[3]
        val fontMetrics =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 19.5f, context.resources.displayMetrics)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }.fontMetrics
        val labelBottom = highPlacement.y + fontMetrics.descent
        val gap = highPoint.second - labelBottom
        val expectedGap = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, context.resources.displayMetrics)

        assertEquals(
            "Expected above-label bottom edge to sit close to the curve. pointY=${highPoint.second} labelBottom=$labelBottom placement=$highPlacement",
            expectedGap,
            gap,
            2f,
        )
    }

    @Test
    fun `actual and forecast highs are both labeled when peaks differ`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 84.2f, actualTemperature = 84.2f, isActual = true, label = "3p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 89.0f, actualTemperature = 85.1f, isActual = true, label = "4p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 87.2f, actualTemperature = 86.2f, isActual = true, label = "5p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 84.3f, actualTemperature = 84.0f, isActual = true, label = "6p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 81.0f, label = "7p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 450,
            currentTime = start.plusHours(3),
            onLabelPlaced = { placements.add(it) },
        )

        val actualHigh = placements.find { it.role == "HIGH" }
        val forecastHigh = placements.find { it.role == "FORECAST_HIGH" }

        assertNotNull("Expected actual-series HIGH label. placements=$placements", actualHigh)
        assertNotNull("Expected forecast-series FORECAST_HIGH label. placements=$placements", forecastHigh)
        assertEquals(86.2f, actualHigh!!.temperature, 0.01f)
        assertEquals(89.0f, forecastHigh!!.temperature, 0.01f)
        assertEquals("actual", actualHigh.series)
        assertEquals("forecast", forecastHigh.series)
        assertEquals("actual", actualHigh.colorFamily)
        assertEquals("forecast", forecastHigh.colorFamily)
    }

    @Test
    fun `actuals end is labeled with ACTUAL_END role`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        val start = LocalDateTime.of(2026, 3, 19, 15, 0)
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 84.0f, actualTemperature = 84.0f, isActual = true, label = "3p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 85.0f, actualTemperature = 85.0f, isActual = true, label = "4p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 86.0f, actualTemperature = 86.0f, isActual = true, label = "5p"), // ACTUAL_END
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 87.0f, label = "6p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 88.0f, label = "7p"),
            )

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 500,
            heightPx = 450,
            currentTime = start.plusHours(2),
            onLabelPlaced = { placements.add(it) },
        )

        val actualEnd = placements.find { it.role == "ACTUAL_END" }

        assertNotNull("Expected ACTUAL_END label. placements=$placements", actualEnd)
        assertEquals("Expected ACTUAL_END at index 2 (last actual index matches transitionX).", 2, actualEnd!!.index)
        assertEquals(86.0f, actualEnd.temperature, 0.01f)
        assertEquals("actual", actualEnd.series)
        assertEquals("actual", actualEnd.colorFamily)
    }

    @Test
    fun `actuals end label shifts to effective end when line is clipped by nowX`() {
        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        val start = LocalDateTime.of(2026, 3, 19, 10, 0)
        val hours =
            listOf(
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(0), temperature = 84.0f, actualTemperature = 84.0f, isActual = true, label = "10a"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(1), temperature = 85.0f, actualTemperature = 85.0f, isActual = true, label = "11a", isCurrentHour = true),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(2), temperature = 86.0f, actualTemperature = 86.0f, isActual = true, label = "12p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(3), temperature = 87.0f, actualTemperature = 87.0f, isActual = true, label = "1p"),
                TemperatureGraphRenderer.HourData(dateTime = start.plusHours(4), temperature = 88.0f, label = "2p"),
            )

        // Clip at 11:30a (halfway between 11a and 12p) using currentTime
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 400, // 80px per hour
            heightPx = 400,
            currentTime = start.plusHours(1).plusMinutes(30),
            onLabelPlaced = { placements.add(it) },
        )

        val actualEnd = placements.find { it.role == "ACTUAL_END" }
        assertNotNull("Expected ACTUAL_END label. placements=$placements", actualEnd)
        assertEquals("Expected ACTUAL_END at index 1 because line is clipped by nowX.", 1, actualEnd!!.index)
        assertEquals(85.0f, actualEnd.temperature, 0.01f)
    }
}
