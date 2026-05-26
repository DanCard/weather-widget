package com.weatherwidget.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.DisplayMetrics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
class TemperatureLabelSuppressionTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `ACTUAL_HIGH is suppressed when redundant near HIGH`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)
        
        // Setup: HIGH is at index 4 (70.0f)
        // ACTUAL_HIGH is at index 3 (70.2f)
        // Distance is 1, value diff is 0.2f. Should be suppressed.
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 4) 70.0f else 60.0f,
                label = "${dt.hour}h",
                isActual = offset <= 10,
                actualTemperature = if (offset == 3) 70.2f else 60.0f
            )
        }

        val placements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(12),
            onLabelPlaced = { placements.add(it) }
        )

        // Verify HIGH (at index 4) is placed, but ACTUAL_HIGH (at index 3) is NOT.
        assertTrue("HIGH should be placed at index 4", placements.any { it.index == 4 && it.role == TemperatureRole.HIGH })
        assertFalse("ACTUAL_HIGH at index 3 should be suppressed", placements.any { it.index == 3 && it.role == TemperatureRole.ACTUAL_HIGH })
    }

    @Test
    fun `ACTUAL_HIGH is suppressed when redundant near global HIGH`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)
        
        // Setup: ACTUAL_HIGH is at index 2 (75.0f)
        // FORECAST_HIGH is at index 3 (75.1f)
        // Distance is 1, value diff is 0.1f. Should be suppressed.
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 3) 75.1f else 65.0f,
                label = "${dt.hour}h",
                isActual = offset <= 10,
                actualTemperature = if (offset == 2) 75.0f else 65.0f
            )
        }

        val placements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(12),
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("HIGH should be placed at index 3", placements.any { it.index == 3 && it.role == TemperatureRole.HIGH })
        assertFalse("ACTUAL_HIGH at index 2 should be suppressed by HIGH at index 3", placements.any { it.index == 2 && it.role == TemperatureRole.ACTUAL_HIGH })
    }

    @Test
    fun `FORECAST_HIGH is suppressed when redundant near ACTUAL_HIGH`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)
        
        // Setup:
        // Global HIGH is at index 9 (80.0f)
        // ACTUAL_HIGH is at index 2 (75.0f)
        // FORECAST_HIGH is at index 3 (75.1f)
        // effectiveActualEndIndex must be at least 2.
        // Let's set it at index 2 (12:00).
        val transitionX = 250f // index 2 approx.
        val hours = (0 until 10).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = when(offset) {
                    3 -> 75.1f
                    9 -> 80.0f
                    else -> 60.0f + offset
                },
                label = "${dt.hour}h",
                isActual = offset <= 2,
                actualTemperature = if (offset == 2) 75.0f else 60.0f + offset
            )
        }

        val placements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(2).plusMinutes(30), // Now is 12:30 (idx 2.5)
            onLabelPlaced = { placements.add(it) }
        )

        // ACTUAL_HIGH (at index 2) should be placed.
        // FORECAST_HIGH (at index 3) should be suppressed by ACTUAL_HIGH.
        assertTrue("ACTUAL_HIGH should be placed at index 2", placements.any { it.index == 2 && it.role == TemperatureRole.ACTUAL_HIGH })
        assertFalse("FORECAST_HIGH at index 3 should be suppressed by ACTUAL_HIGH at index 2", placements.any { it.index == 3 && it.role == TemperatureRole.FORECAST_HIGH })
        assertTrue("HIGH should be placed at index 9", placements.any { it.index == 9 && it.role == TemperatureRole.HIGH })
    }

    @Test
    fun `PAST_FORECAST_HIGH is suppressed when redundant near ACTUAL_HIGH`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)
        
        // Setup:
        // Global HIGH is at index 9 (80.0f)
        // ACTUAL_HIGH is at index 4 (75.0f)
        // PAST_FORECAST_HIGH is at index 3 (75.1f)
        // Both are in the "past" (offset <= effectiveActualEndIndex)
        val hours = (0 until 10).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = when(offset) {
                    3 -> 75.1f
                    9 -> 80.0f
                    else -> 60.0f + offset
                },
                label = "${dt.hour}h",
                isActual = offset <= 5,
                actualTemperature = if (offset == 4) 75.0f else 60.0f + offset
            )
        }

        val placements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(6), 
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("ACTUAL_HIGH should be placed at index 4", placements.any { it.index == 4 && it.role == TemperatureRole.ACTUAL_HIGH })
        assertFalse("PAST_FORECAST_HIGH at index 3 should be suppressed by ACTUAL_HIGH at index 4", placements.any { it.index == 3 && it.role == TemperatureRole.PAST_FORECAST_HIGH })
    }

    @Test
    fun `ACTUAL_LOW is retained when near daily low`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)

        // Daily/global LOW lives on the forecast curve at index 10 (52.0).
        // ACTUAL_LOW is at index 8 (52.5) — distance 2, value diff 0.5°.
        // Under the old rule this tripped the 1°/4-index redundancy and was dropped;
        // now the observed low must always be retained.
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 10) 52.0f else 60.0f,
                label = "${dt.hour}h",
                isActual = offset <= 9,
                actualTemperature = if (offset == 8) 52.5f else 60.0f
            )
        }

        val placements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(9),
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("LOW should be placed at index 10", placements.any { it.index == 10 && it.role == TemperatureRole.LOW })
        assertTrue("ACTUAL_LOW at index 8 should be retained, not suppressed", placements.any { it.index == 8 && it.role == TemperatureRole.ACTUAL_LOW })
    }

    @Test
    fun `nearby low pair is ordered by value with higher actual low above`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)

        // ACTUAL_LOW (higher value, 56.0) sits just before a strictly-lower forecast/daily
        // LOW (52.0). The warmer low's label should be placed ABOVE its point, the colder
        // low's label BELOW — matching the points' vertical order.
        val hours = (0 until 24).map { offset ->
            val dt = start.plusHours(offset.toLong())
            HourData(
                dateTime = dt,
                temperature = if (offset == 10) 52.0f else 65.0f,
                label = "${dt.hour}h",
                isActual = offset <= 9,
                actualTemperature = if (offset == 8) 56.0f else 65.0f
            )
        }

        val placements = mutableListOf<LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(9),
            onLabelPlaced = { placements.add(it) }
        )

        val actualLow = placements.firstOrNull { it.index == 8 && it.role == TemperatureRole.ACTUAL_LOW }
        val dailyLow = placements.firstOrNull { it.index == 10 && it.role == TemperatureRole.LOW }
        assertTrue("ACTUAL_LOW should be placed", actualLow != null)
        assertTrue("LOW should be placed", dailyLow != null)
        assertTrue("Higher actual low (56°) should be placed above its point", actualLow!!.placedAbove)
        assertFalse("Lower forecast low (52°) should be placed below its point", dailyLow!!.placedAbove)
    }

    private fun mockContext(): Context {
        mockkStatic(Bitmap::class)
        mockkConstructor(Canvas::class)
        mockkConstructor(Paint::class)

        val bitmap = mockk<Bitmap>(relaxed = true)
        every { Bitmap.createBitmap(any<Int>(), any<Int>(), any<Bitmap.Config>()) } returns bitmap
        every { anyConstructed<Canvas>().drawPath(any(), any()) } returns Unit
        every { anyConstructed<Canvas>().drawText(any<String>(), any(), any(), any()) } returns Unit
        every { anyConstructed<Canvas>().drawLine(any(), any(), any(), any(), any()) } returns Unit
        every { anyConstructed<Canvas>().drawCircle(any(), any(), any(), any()) } returns Unit
        every { anyConstructed<Canvas>().save() } returns 0
        every { anyConstructed<Canvas>().restore() } returns Unit
        every { anyConstructed<Canvas>().clipRect(any<Float>(), any<Float>(), any<Float>(), any<Float>()) } returns true

        every { anyConstructed<Paint>().measureText(any<String>()) } returns 20f
        every { anyConstructed<Paint>().textSize } returns 12f
        every { anyConstructed<Paint>().fontMetrics } returns Paint.FontMetrics().apply {
            ascent = -10f
            descent = 2f
        }

        val metrics = DisplayMetrics().apply { density = 1.0f }
        val resources = mockk<Resources>(relaxed = true)
        every { resources.displayMetrics } returns metrics
        val context = mockk<Context>(relaxed = true)
        every { context.resources } returns resources
        return context
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
