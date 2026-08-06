package com.weatherwidget.widget

import com.weatherwidget.shared.graph.*
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
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

/**
 * Renders the hourly graph with a supplied forecast delta (observed minus forecast, the same value
 * that shifts the ghost line) and asserts the "+X.X from forecast" label is drawn in the zoomed-in
 * (narrow) view and suppressed past the day-span gate. Placement/format/color live in shared
 * [ForecastDeltaLabel]; this only checks the Android render path delegates and draws.
 */
@Category(MediumDuration::class)
class TemperatureGraphRendererForecastDeltaTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `draws forecast delta label in narrow view`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 3, 21, 10, 0)
        // A low, gently varying curve leaves clear empty space in the upper band for the label.
        val temps = listOf(50f, 52f, 54f, 53f, 51f)
        val hours = temps.mapIndexed { offset, t ->
            HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = t,
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
            heightPx = 400,
            currentTime = start.plusHours(2).plusMinutes(25),
            bitmapScale = 0.97f, // distinct scale → fresh PaintSet under this test's mockk (cache is keyed on scale)
            observedAt = observedAtMs,
            lastObservedTemp = 54f,
            appliedDelta = 2.3f, useCelsius = false,
        )

        verify(atLeast = 1) { anyConstructed<Canvas>().drawText("+2.3 from forecast", any(), any(), any()) }
    }

    @Test
    fun `draws forecast delta label in the 24h view`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 3, 21, 0, 0)
        // 25 points spanning 24h (the WIDE view), with a hill curve leaving empty bands for the label.
        val hours = (0..24).map { offset ->
            HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 50f + (12 - kotlin.math.abs(offset - 12)), // 50..62 hill peaking at noon
                label = "${offset % 24}h",
                showLabel = true,
                isCurrentHour = offset == 12,
            )
        }
        val observedAtMs = start.plusHours(12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 400,
            currentTime = start.plusHours(12).plusMinutes(25),
            bitmapScale = 0.96f,
            observedAt = observedAtMs,
            lastObservedTemp = 62f,
            appliedDelta = 2.3f, useCelsius = false,
        )

        verify(atLeast = 1) { anyConstructed<Canvas>().drawText("+2.3 from forecast", any(), any(), any()) }
    }

    @Test
    fun `does not draw forecast delta label in the 3-day view`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 3, 20, 0, 0)
        // 73 points spanning 72h -> the 3-day view, past the day-span gate.
        val hours = (0..72).map { offset ->
            HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 55f,
                label = "${offset % 24}h",
                showLabel = offset % 6 == 0,
                isCurrentHour = offset == 36,
            )
        }
        val observedAtMs = start.plusHours(36).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 400,
            currentTime = start.plusHours(36).plusMinutes(25),
            bitmapScale = 0.94f,
            observedAt = observedAtMs,
            lastObservedTemp = 55f,
            appliedDelta = 2.3f, useCelsius = false,
        )

        verify(exactly = 0) { anyConstructed<Canvas>().drawText("+2.3 from forecast", any(), any(), any()) }
    }

    @Test
    fun `does not draw forecast delta label when delta is null`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 3, 21, 10, 0)
        val hours = (0..4).map { offset ->
            HourData(
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
            bitmapScale = 0.95f,
            observedAt = observedAtMs,
            lastObservedTemp = 60f,
            appliedDelta = null, useCelsius = false,
        )

        verify(exactly = 0) { anyConstructed<Canvas>().drawText(match<String> { it.endsWith("from forecast") }, any(), any(), any()) }
    }

    @Test
    fun `does not draw forecast delta label when fetch dot is outside the visible hours`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 3, 21, 10, 0)
        // Tomorrow's hours (starts 24h later)
        val tomorrowStart = start.plusHours(24)
        val temps = listOf(50f, 52f, 54f, 53f, 51f)
        val hours = temps.mapIndexed { offset, t ->
            HourData(
                dateTime = tomorrowStart.plusHours(offset.toLong()),
                temperature = t,
                label = "${(10 + offset) % 24}h",
                showLabel = true,
                isCurrentHour = false,
            )
        }
        // observedAtMs is TODAY (start.plusHours(2)), which is outside tomorrow's hours range
        val observedAtMs = start.plusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 400,
            currentTime = start.plusHours(2).plusMinutes(25),
            bitmapScale = 0.93f, // distinct scale
            observedAt = observedAtMs,
            lastObservedTemp = 54f,
            appliedDelta = 2.3f, useCelsius = false,
        )

        verify(exactly = 0) { anyConstructed<Canvas>().drawText(match<String> { it.endsWith("from forecast") }, any(), any(), any()) }
    }

    private fun mockContext(): Context {
        mockkStatic(Bitmap::class)
        mockkConstructor(Canvas::class)
        mockkConstructor(Paint::class)

        val paintColors = mutableMapOf<Paint, Int>()
        every { anyConstructed<Paint>().setColor(any()) } answers {
            paintColors[invocation.self as Paint] = invocation.args[0] as Int
        }
        every { anyConstructed<Paint>().color } answers {
            paintColors[invocation.self as Paint] ?: 0
        }

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
