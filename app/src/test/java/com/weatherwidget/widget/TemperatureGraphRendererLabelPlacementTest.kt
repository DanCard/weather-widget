package com.weatherwidget.widget

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.DisplayMetrics
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category

@Category(LongDuration::class)
class TemperatureGraphRendererLabelPlacementTest {

    @Before
    fun setUp() {
        mockkStatic(Bitmap::class)
        mockkStatic(RectF::class)
        mockkConstructor(Canvas::class)
        mockkConstructor(Paint::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `label placement prefers step 0 below over step 1 above when step 0 above is blocked`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 4, 4, 12, 0)
        
        // Index 1 is LOW (70f). Index 4 is HIGH (82f).
        val hours = listOf(
            buildHour(start, 0, 75f),
            buildHour(start, 1, 70f), // LOW
            buildHour(start, 2, 75f),
            buildHour(start, 3, 78f),
            buildHour(start, 4, 82f), // HIGH
            buildHour(start, 5, 80f),
            buildHour(start, 6, 78f),
            buildHour(start, 7, 76f)
        )

        val labelsPlaced = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        // Order of placement:
        // 1. LOW (idx 1). RectF.intersects will NOT be called because drawnLabelBounds is empty.
        // 2. HIGH (idx 4). directions are [above, below].
        //    - Above: RectF.intersects called once. We want this to return true.
        //    - Below: RectF.intersects called once. We want this to return false.
        // 3. START (idx 0).
        // 4. END (idx 7).
        
        var callCount = 0
        every { RectF.intersects(any(), any()) } answers {
            val count = callCount++
            if (count == 0) true else false
        }

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { labelsPlaced.add(it) }
        )

        val peakLabel = labelsPlaced.find { it.role == "HIGH" || it.role == "FORECAST_HIGH" }
        
        assertTrue("Peak label should be placed", peakLabel != null)
        assertEquals("Should be step 0", 0, peakLabel!!.displacementSteps)
        assertEquals("Should be below (placedAbove=false)", false, peakLabel.placedAbove)
    }

    @Test
    fun `label placement exhausts step 0 before moving to step 1`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 4, 4, 12, 0)
        
        val hours = listOf(
            buildHour(start, 0, 75f),
            buildHour(start, 1, 70f), // LOW
            buildHour(start, 2, 75f),
            buildHour(start, 3, 78f),
            buildHour(start, 4, 82f), // HIGH
            buildHour(start, 5, 80f),
            buildHour(start, 6, 78f),
            buildHour(start, 7, 76f)
        )

        val labelsPlaced = mutableListOf<TemperatureGraphRenderer.LabelPlacementDebug>()

        // HIGH (idx 4). attempts:
        // step 0 above: intersects -> true
        // step 0 below: intersects -> true
        // step 1 above: intersects -> false
        var callCount = 0
        every { RectF.intersects(any(), any()) } answers {
            val count = callCount++
            if (count < 4) true else false
        }

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 400,
            currentTime = start,
            onLabelPlaced = { labelsPlaced.add(it) }
        )

        val peakLabel = labelsPlaced.find { it.role == "HIGH" || it.role == "FORECAST_HIGH" }
        
        assertTrue("Peak label should be placed", peakLabel != null)
        assertEquals("Should be step 1", 1, peakLabel!!.displacementSteps)
        assertEquals("Should be above (placedAbove=true)", true, peakLabel.placedAbove)
    }

    private fun mockContext(): Context {
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

    private fun buildHour(start: LocalDateTime, offset: Int, temp: Float): TemperatureGraphRenderer.HourData {
        val dt = start.plusHours(offset.toLong())
        return TemperatureGraphRenderer.HourData(
            dateTime = dt,
            temperature = temp,
            label = "${dt.hour}h",
            showLabel = true,
            isCurrentHour = false,
            isActual = false,
            actualTemperature = null
        )
    }
}
