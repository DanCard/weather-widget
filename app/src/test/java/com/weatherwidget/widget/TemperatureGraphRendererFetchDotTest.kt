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
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category

@Category(MediumDuration::class)
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
    fun `renderGraph draws fetch dot rings when observed timestamp and lastObservedTemp are present`() {
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
            lastObservedTemp = 55f,
        )

        verify(exactly = 3) { anyConstructed<Canvas>().drawCircle(any(), any(), any(), any()) }
    }

    @Test
    fun `renderGraph does not draw fetch dot when lastObservedTemp is null`() {
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
            lastObservedTemp = null,
        )

        verify(exactly = 0) { anyConstructed<Canvas>().drawCircle(any(), any(), any(), any()) }
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

        // Hidden NOW indicator: fill + 7 forecast segments = 8 paths (no ghost, no actual line).
        // Ghost extension when dot scrolled off-left (future narrow view) still requires
        // observedAt/fetch to compute (possibly negative) fetchDotX for anchor.
        verify(exactly = 8) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    @Test
    fun `fetch dot Y reflects lastObservedTemp not graph curve position`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 26, 10, 0)
        // Forecast temps flat at 60. lastObservedTemp = 65 (different from curve).
        // The dot Y must reflect 65° (lastObservedTemp), not the 60° forecast curve.
        val hours = (0..7).map { offset ->
            HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 60f,
                label = "${(10 + offset) % 24}h",
                showLabel = true,
                isCurrentHour = offset == 3,
            )
        }
        val observedAtMs = start.plusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val yAt65 = mutableListOf<Float>()
        every { anyConstructed<Canvas>().drawCircle(any(), capture(yAt65), any(), any()) } returns Unit

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(3),
            observedAt = observedAtMs,
            lastObservedTemp = 65f,
        )

        val yAt60 = mutableListOf<Float>()
        every { anyConstructed<Canvas>().drawCircle(any(), capture(yAt60), any(), any()) } returns Unit

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(3),
            observedAt = observedAtMs,
            lastObservedTemp = 60f,
        )

        assert(yAt65.size >= 3) { "Expected 3 drawCircle calls for fetch dot, got ${yAt65.size}" }
        assert(yAt60.size >= 3) { "Expected 3 drawCircle calls for fetch dot, got ${yAt60.size}" }
        // 65° is warmer → higher on temp scale → lower Y value (graph draws hot at top)
        assert(yAt65[0] < yAt60[0]) {
            "Dot at 65° (Y=${yAt65[0]}) should be above dot at 60° (Y=${yAt60[0]})"
        }
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
            HourData(
                dateTime = start,
                temperature = 50f,
                label = "10h",
                showLabel = true,
                isCurrentHour = false,
                isActual = true,
                actualTemperature = 50f
            ),
            HourData(
                dateTime = start.plusMinutes(37),
                temperature = 52.5f,
                label = "10h",
                showLabel = false,
                isCurrentHour = true,
                isActual = true,
                actualTemperature = 52.5f
            ),
            HourData(
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

        var fetchDotDebug: FetchDotDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusMinutes(40),
            observedAt = observedAtMs,
            lastObservedTemp = 52.5f,
            onFetchDotResolved = { fetchDotDebug = it }
        )

        // The dot should represent exactly 52.5 (lastObservedTemp).
        // Manually calculate where 52.5 should sit on the graph:
        // tempRange = 55 - 50 = 5 (min=50, max=55)
        // Y = graphTop + graphHeight * (1 - (52.5 - 50) / 5) = middle of graph
        // Using 157.14287f which is the exact derived Y value for 52.5f in this layout scenario after increasing bottom buffer.
        val expectedY = 157.14287f

        org.junit.Assert.assertNotNull("FetchDotDebug should be emitted", fetchDotDebug)
        org.junit.Assert.assertEquals(
            "Fetch dot Y must reflect lastObservedTemp exactly",
            expectedY,
            fetchDotDebug!!.fetchY!!,
            0.5f
        )
    }
    @Test
    fun `fetch dot value color matches WeatherConditionColors OBSERVED with full opacity`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 26, 10, 0)
        val hours = buildHours(start)
        val observedAtMs = start.plusHours(2).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        var fetchDotDebug: FetchDotDebug? = null
        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(3),
            observedAt = observedAtMs,
            lastObservedTemp = 55f,
            onFetchDotResolved = { fetchDotDebug = it }
        )

        org.junit.Assert.assertNotNull("FetchDotDebug should be emitted", fetchDotDebug)
        assertEquals(
            "Fetch dot value color must match WeatherConditionColors.OBSERVED exactly (full opacity)",
            com.weatherwidget.util.WeatherConditionColors.OBSERVED,
            fetchDotDebug!!.valueColor!!
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

    private fun buildHours(start: LocalDateTime): List<HourData> {
        return (0..7).map { offset ->
            HourData(
                dateTime = start.plusHours(offset.toLong()),
                temperature = 52f + offset,
                label = "${start.plusHours(offset.toLong()).hour}h",
                showLabel = true,
            )
        }
    }
}
