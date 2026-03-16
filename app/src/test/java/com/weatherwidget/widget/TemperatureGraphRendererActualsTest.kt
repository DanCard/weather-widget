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

/**
 * Verifies that renderGraph draws the correct number of paths depending on
 * whether actuals are present.
 *
 * Path drawing order in renderGraph:
 *   1. forecastFillPath     — always (fill under forecast curve)
 *   2. expectedPath (ghost) — only when nowIndicatorVisible && |appliedDelta| >= 0.1
 *   3. forecastPath (dashed)— always
 *   4. originalPath (solid) — only when transitionX != null (i.e., actuals present)
 *
 * So baseline (no actuals, no ghost) = 2 paths.
 * Adding actuals = 3 paths (+solid actual).
 * Adding ghost (actuals + nowVisible + delta) = 4 paths.
 */
class TemperatureGraphRendererActualsTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------
    // Test 1: No actuals → 2 drawPath calls (fill + dashed forecast only)
    // -------------------------------------------------------------------
    @Test
    fun `no actuals produces 2 drawPath calls — fill and dashed forecast only`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        val hours = buildHours(start, actualsCount = 0, markCurrentHour = false)

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(2),
        )

        verify(exactly = 2) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    // -------------------------------------------------------------------
    // Test 2: With actuals → 3 drawPath calls (+solid actual line)
    // -------------------------------------------------------------------
    @Test
    fun `with actuals produces 3 drawPath calls — fill, dashed forecast, solid actual`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        // First 4 hours are actuals
        val hours = buildHours(start, actualsCount = 4, markCurrentHour = false)

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(5),
        )

        verify(exactly = 3) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    // -------------------------------------------------------------------
    // Test 3: With actuals + nowVisible + delta → 4 drawPath calls (+ghost)
    // -------------------------------------------------------------------
    @Test
    fun `with actuals and ghost line produces 4 drawPath calls`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        // Mark hour index 5 as current hour so NOW indicator is visible
        val hours = buildHours(start, actualsCount = 3, markCurrentHour = true, currentHourIndex = 5)

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(5),
            appliedDelta = 2.0f,
        )

        verify(exactly = 4) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    // -------------------------------------------------------------------
    // Test 4: No actuals + delta active but NOW hidden → 2 paths, no ghost
    //         (Mirrors existing TemperatureGraphRendererFetchDotTest case)
    // -------------------------------------------------------------------
    @Test
    fun `no actuals and delta but NOW hidden produces 2 drawPath calls — no ghost`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        val hours = buildHours(start, actualsCount = 0, markCurrentHour = false)

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(2),
            appliedDelta = 2.0f,
        )

        verify(exactly = 2) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    // -------------------------------------------------------------------
    // Test 5: lastActualIndex reflects the last isActual=true hour
    //         Verified indirectly: 3 paths → solid drawn → transitionX != null → actuals exist
    //         If actuals were at indices 0-2 only, index 3+ are forecast-only
    // -------------------------------------------------------------------
    @Test
    fun `partial actuals — only first N hours actual — still draws 3 paths`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        val hours = buildHours(start, actualsCount = 2, markCurrentHour = false) // 8 hours total, only first 2 actual

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(4),
        )

        verify(exactly = 3) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    // -------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------

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

        val metrics = DisplayMetrics().apply { density = 1.0f }
        val resources = mockk<Resources>(relaxed = true)
        every { resources.displayMetrics } returns metrics
        val context = mockk<Context>(relaxed = true)
        every { context.resources } returns resources
        return context
    }

    /**
     * Build a list of HourData with [actualsCount] hours at the start marked as isActual.
     * If [markCurrentHour] is true, marks [currentHourIndex] as isCurrentHour (makes NOW visible).
     */
    private fun buildHours(
        start: LocalDateTime,
        actualsCount: Int,
        markCurrentHour: Boolean,
        currentHourIndex: Int = 0,
        total: Int = 8,
    ): List<TemperatureGraphRenderer.HourData> {
        return (0 until total).map { offset ->
            val dt = start.plusHours(offset.toLong())
            val isActual = offset < actualsCount
            TemperatureGraphRenderer.HourData(
                dateTime = dt,
                temperature = 52f + offset,
                label = "${dt.hour}h",
                showLabel = true,
                isCurrentHour = markCurrentHour && offset == currentHourIndex,
                isActual = isActual,
                actualTemperature = if (isActual) 50f + offset else null,
            )
        }
    }
}
