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
import io.mockk.verify
import org.junit.After
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TemperatureGraphRendererStalenessTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `draws age text in narrow view with few points`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 3, 21, 10, 0)
        // 5 points, 4 hour duration
        val hours = (0..4).map { offset ->
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 60f,
                label = "${(10 + offset) % 24}h",
                showLabel = true,
                isCurrentHour = offset == 2,
            )
        }
        val observedAtMs = start.plusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(2).plusMinutes(25),
            observedAt = observedAtMs,
        )

        // Verify drawText was called with "25m"
        verify(atLeast = 1) { anyConstructed<Canvas>().drawText("25m", any(), any(), any()) }
    }

    @Test
    fun `draws age text in narrow view with many points`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 3, 21, 10, 0)
        // 49 points (every 5 mins), 4 hour duration
        val hours = (0..48).map { offset ->
            val time = start.plusMinutes(offset.toLong() * 5)
            TemperatureGraphRenderer.HourData(
                dateTime = time,
                temperature = 60f,
                label = "${time.hour}h",
                showLabel = offset % 12 == 0,
                isCurrentHour = offset == 24, // center
            )
        }
        val observedAtMs = start.plusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(2).plusMinutes(25),
            observedAt = observedAtMs,
        )

        // Even with 49 points, it's only 4h duration, so it should draw "25m"
        verify(atLeast = 1) { anyConstructed<Canvas>().drawText("25m", any(), any(), any()) }
    }

    @Test
    fun `does not draw age text in wide view`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 3, 21, 10, 0)
        // 25 points, 24 hour duration
        val hours = (0..24).map { offset ->
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 60f,
                label = "${(10 + offset) % 24}h",
                showLabel = true,
                isCurrentHour = offset == 12,
            )
        }
        val observedAtMs = start.plusHours(12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(12).plusMinutes(25),
            observedAt = observedAtMs,
        )

        // Should NOT draw the age text "25m" in wide view
        verify(exactly = 0) { anyConstructed<Canvas>().drawText("25m", any(), any(), any()) }
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
}
