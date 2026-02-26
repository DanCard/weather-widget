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

class TemperatureGraphRendererFetchDotTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `renderGraph does not draw fetch dot when observed timestamp is null`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 26, 10, 0)
        val hours = buildHours(start)

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(2),
            observedTempFetchedAt = null,
        )

        verify(exactly = 0) { anyConstructed<Canvas>().drawCircle(any(), any(), any(), any()) }
    }

    @Test
    fun `renderGraph draws fetch dot rings when observed timestamp is present in range`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 26, 10, 0)
        val hours = buildHours(start)
        val observedAtMs = start.plusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(3),
            observedTempFetchedAt = observedAtMs,
        )

        verify(exactly = 3) { anyConstructed<Canvas>().drawCircle(any(), any(), any(), any()) }
    }

    @Test
    fun `renderGraph does not draw ghost line when now indicator is not visible`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 26, 10, 0)
        val hours = buildHours(start) // No isCurrentHour marker -> NOW indicator hidden

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(2),
            appliedDelta = 1.5f,
        )

        // Hidden NOW indicator should force original-only fill + curve (2 paths).
        verify(exactly = 2) { anyConstructed<Canvas>().drawPath(any(), any()) }
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

        val metrics = DisplayMetrics().apply { density = 1.0f }
        val resources = mockk<Resources>(relaxed = true)
        every { resources.displayMetrics } returns metrics
        val context = mockk<Context>(relaxed = true)
        every { context.resources } returns resources
        return context
    }

    private fun buildHours(start: LocalDateTime): List<TemperatureGraphRenderer.HourData> {
        return (0..7).map { offset ->
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 52f + offset,
                label = "${start.plusHours(offset.toLong()).hour}h",
                showLabel = true,
            )
        }
    }
}
