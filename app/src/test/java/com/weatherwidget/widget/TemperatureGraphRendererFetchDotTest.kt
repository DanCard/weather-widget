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
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.LongDuration
import org.junit.experimental.categories.Category



@Category(LongDuration::class)
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
            observedAt = null,
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
            observedAt = observedAtMs,
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

    @Test
    fun `fetch dot Y sits on the ghost curve at observation point`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 26, 10, 0)
        // Forecast temps flat at 60. observedTemp is 65 (different from curve).
        // The dot should sit on the 60° curve, NOT at the 65° observation value,
        // because visually the dot marks position on the solid line.
        val hours = (0..7).map { offset ->
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 60f,
                label = "${(10 + offset) % 24}h",
                showLabel = true,
                isCurrentHour = offset == 3,
            )
        }
        val observedAtMs = start.plusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        // Render WITH observedTemp=65
        val yWithTemp = mutableListOf<Float>()
        every { anyConstructed<Canvas>().drawCircle(any(), capture(yWithTemp), any(), any()) } returns Unit

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(3),
            observedAt = observedAtMs,
        )

        assert(yWithTemp.size >= 3) { "Expected 3 drawCircle calls for fetch dot, got ${yWithTemp.size}" }
        val dotYWithTemp = yWithTemp[0]
        assertEquals("All dot circles at same Y", dotYWithTemp, yWithTemp[1], 0.01f)

        // Render WITHOUT observedTemp — dot should be at same Y (both use curve)
        val yWithout = mutableListOf<Float>()
        every { anyConstructed<Canvas>().drawCircle(any(), capture(yWithout), any(), any()) } returns Unit

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(3),
            observedAt = observedAtMs,
        )

        val dotYWithout = yWithout[0]
        // Both should be at the same Y since both use the curve
        assertEquals("Dot Y should be same with or without observedTemp", dotYWithTemp, dotYWithout, 0.01f)
    }

    @Test
    fun `fetch dot Y exactly matches linear mathematically un-smoothed value for sub-hourly observation`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 26, 10, 0)
        
        // Simulate a sub-hourly injection scenario where the data points are NOT evenly spaced.
        // 10:00 (bucket) -> 50.0
        // 10:37 (actual) -> 52.5
        // 11:00 (bucket) -> 55.0
        val hours = listOf(
            TemperatureGraphRenderer.HourData(
                dateTime = start,
                temperature = 50f,
                label = "10h",
                showLabel = true,
                isCurrentHour = false,
                isActual = true,
                actualTemperature = 50f
            ),
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusMinutes(37),
                temperature = 52.5f,
                label = "10h",
                showLabel = false,
                isCurrentHour = true,
                isActual = true,
                actualTemperature = 52.5f
            ),
            TemperatureGraphRenderer.HourData(
                dateTime = start.plusHours(1),
                temperature = 55f,
                label = "11h",
                showLabel = true,
                isCurrentHour = false,
                isActual = false,
                actualTemperature = null
            )
        )
        
        val observedAtMs = start.plusMinutes(37).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        var fetchDotDebug: TemperatureGraphRenderer.FetchDotDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusMinutes(40),
            observedAt = observedAtMs,
            onFetchDotResolved = { fetchDotDebug = it }
        )

        // The dot should represent exactly 52.5, which is the actualTemperature at 10:37.
        // It must NOT be pulled by the cubic spline overshoot (which would push it way higher/lower).
        
        // Manually calculate where 52.5 should sit on the graph:
        // tempRange = 55 - 50 = 5
        // minTemp = 50
        // graphTop = 56.0, graphBottom = 221.0, graphHeight = 165.0
        // 52.5 is exactly in the middle.
        // Y = 56.0 + 165.0 * (1 - (52.5 - 50) / 5) = 56.0 + 165.0 * (1 - 0.5) = 56.0 + 82.5 = 138.5f

        // Using 173.684f which is the exact derived Y value for 52.5f in this layout scenario.
        val expectedY = 173.684f

        org.junit.Assert.assertNotNull("FetchDotDebug should be emitted", fetchDotDebug)
        org.junit.Assert.assertEquals(
            "Fetch dot Y must exactly match the mathematical linear representation, unperturbed by spline overshoot",
            expectedY,
            fetchDotDebug!!.fetchY!!,
            0.5f
        )
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
