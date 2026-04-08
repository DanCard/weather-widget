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
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
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
        val hours = (0 until 10).map { offset ->
            val dt = start.plusHours(offset.toLong())
            TemperatureGraphRenderer.HourData(
                dateTime = dt,
                temperature = if (offset == 4) 70.0f else 60.0f + offset,
                label = "${dt.hour}h",
                isActual = offset <= 5,
                actualTemperature = if (offset == 3) 70.2f else 60.0f + offset
            )
        }

        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(6),
            onLabelPlaced = { placements.add(it) }
        )

        // Verify HIGH (at index 4) is placed, but ACTUAL_HIGH (at index 3) is NOT.
        assertTrue("HIGH should be placed at index 4", placements.any { it.index == 4 && it.role == TemperatureGraphRenderer.TemperatureRole.HIGH })
        assertFalse("ACTUAL_HIGH at index 3 should be suppressed", placements.any { it.index == 3 && it.role == TemperatureGraphRenderer.TemperatureRole.ACTUAL_HIGH })
    }

    @Test
    fun `ACTUAL_HIGH is suppressed when redundant near global HIGH`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 4, 8, 10, 0)
        
        // Setup: ACTUAL_HIGH is at index 2 (75.0f)
        // FORECAST_HIGH is at index 3 (75.1f)
        // Distance is 1, value diff is 0.1f. Should be suppressed.
        val hours = (0 until 10).map { offset ->
            val dt = start.plusHours(offset.toLong())
            TemperatureGraphRenderer.HourData(
                dateTime = dt,
                temperature = if (offset == 3) 75.1f else 65.0f + offset,
                label = "${dt.hour}h",
                isActual = offset <= 5,
                actualTemperature = if (offset == 2) 75.0f else 65.0f + offset
            )
        }

        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(6),
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("HIGH should be placed at index 3", placements.any { it.index == 3 && it.role == TemperatureGraphRenderer.TemperatureRole.HIGH })
        assertFalse("ACTUAL_HIGH at index 2 should be suppressed by HIGH at index 3", placements.any { it.index == 2 && it.role == TemperatureGraphRenderer.TemperatureRole.ACTUAL_HIGH })
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
            TemperatureGraphRenderer.HourData(
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

        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
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
        assertTrue("ACTUAL_HIGH should be placed at index 2", placements.any { it.index == 2 && it.role == TemperatureGraphRenderer.TemperatureRole.ACTUAL_HIGH })
        assertFalse("FORECAST_HIGH at index 3 should be suppressed by ACTUAL_HIGH at index 2", placements.any { it.index == 3 && it.role == TemperatureGraphRenderer.TemperatureRole.FORECAST_HIGH })
        assertTrue("HIGH should be placed at index 9", placements.any { it.index == 9 && it.role == TemperatureGraphRenderer.TemperatureRole.HIGH })
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
            TemperatureGraphRenderer.HourData(
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

        val placements = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 1000,
            heightPx = 500,
            currentTime = start.plusHours(6), 
            onLabelPlaced = { placements.add(it) }
        )

        assertTrue("ACTUAL_HIGH should be placed at index 4", placements.any { it.index == 4 && it.role == TemperatureGraphRenderer.TemperatureRole.ACTUAL_HIGH })
        assertFalse("PAST_FORECAST_HIGH at index 3 should be suppressed by ACTUAL_HIGH at index 4", placements.any { it.index == 3 && it.role == TemperatureGraphRenderer.TemperatureRole.PAST_FORECAST_HIGH })
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
