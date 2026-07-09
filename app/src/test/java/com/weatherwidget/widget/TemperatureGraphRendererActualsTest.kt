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
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import com.weatherwidget.test.category.MediumDuration
import org.junit.experimental.categories.Category



/**
 * Verifies that renderGraph draws the correct number of paths depending on
 * whether actuals are present.
 *
 * Path drawing order in renderGraph:
 *   1. expectedFillPath + ghost stroke — when [GhostLineGate] allows and |appliedDelta| >= 0.1
 *   2. forecastSegments — always (one drawPath per hour segment, = hours - 1)
 *   3. originalPath (solid actual) — only when transitionX != null (actuals present)
 *
 * With 8 hours (default), forecast = 7 segments.
 * Baseline (no actuals, no ghost) = 7 segments.
 * Adding actuals = 7 + 1 = 8 paths.
 * Adding ghost = 8 + 2 = 10 paths (expected fill + ghost stroke).
 */
@Category(MediumDuration::class)
class TemperatureGraphRendererActualsTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -------------------------------------------------------------------
    // Test 1: No actuals → 8 drawPath calls (fill + 7 forecast segments)
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
            currentTime = start.plusHours(2), useCelsius = false,
        )

        verify(exactly = 7) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    // -------------------------------------------------------------------
    // Test 2: With actuals → 8 drawPath calls (7 segments + solid actual)
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
            currentTime = start.plusHours(5), useCelsius = false,
        )

        verify(exactly = 8) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    // -------------------------------------------------------------------
    // Test 3: With actuals + nowVisible + delta → 10 drawPath calls (+ghost)
    // -------------------------------------------------------------------
    @Test
    fun `with actuals and ghost line produces 4 drawPath calls`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        // Mark hour index 5 as current hour so NOW indicator is visible
        val hours = buildHours(start, actualsCount = 3, markCurrentHour = true, currentHourIndex = 5)
        // Ghost line requires an observation (fetchDotX) to project from
        val observedAtMs = start.plusHours(4).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 900,
            heightPx = 300,
            currentTime = start.plusHours(5),
            appliedDelta = 2.0f,
            observedAt = observedAtMs, useCelsius = false,
        )

        verify(exactly = 10) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    // -------------------------------------------------------------------
    // Test 4: No actuals + delta active but NOW hidden → 8 paths, no ghost
    //         (Mirrors existing TemperatureGraphRendererFetchDotTest case)
    //         Note: ghost extension (for future scroll where now dot off left) requires
    //         observedAt/fetchTime so fetchDotX is computed (even if <0); here no observedAt
    //         so fetchDotX=null, no ghost.
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
            appliedDelta = 2.0f, useCelsius = false,
        )

        verify(exactly = 7) { anyConstructed<Canvas>().drawPath(any(), any()) }
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
            currentTime = start.plusHours(4), useCelsius = false,
        )

        verify(exactly = 8) { anyConstructed<Canvas>().drawPath(any(), any()) }
    }

    // -------------------------------------------------------------------
    // Test 6: Actual line does not extend past NOW even when isActual
    //         hours exist in the future
    // -------------------------------------------------------------------
    @Test
    fun `actual line clipRect does not extend past NOW when actuals span future hours`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        // currentTime is at hour index 3 (13:00), but actuals go through index 6 (16:00)
        // This simulates WAPI returning "actual" data for future hours
        val hours = buildHours(start, actualsCount = 7, markCurrentHour = true, currentHourIndex = 3)

        val clipRights = mutableListOf<Float>()
        every {
            anyConstructed<Canvas>().clipRect(any<Float>(), any<Float>(), capture(clipRights), any<Float>())
        } returns true

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 300,
            currentTime = start.plusHours(3), useCelsius = false,
        )

        val hourWidth = 800f / 8f
        val nowApproxX = hourWidth * 3 + hourWidth / 2  // ~350
        val lastActualApproxX = hourWidth * 6 + hourWidth / 2  // ~650

        // The actual line clipRect is the one that clips to the LEFT side (right edge < widthPx)
        val actualLineClips = clipRights.filter { it < 800f - 1f }
        assertTrue("Expected at least one clipRect for actual line", actualLineClips.isNotEmpty())
        val actualLineRight = actualLineClips.maxOrNull()!!
        assertTrue(
            "Actual line clip right ($actualLineRight) should be near NOW (~$nowApproxX) not at lastActual (~$lastActualApproxX)",
            actualLineRight < nowApproxX + 10f
        )
    }

    @Test
    fun `actual line clipRect stops at fetch dot when observation is older than NOW`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        // currentTime at index 5 (15:00), actuals through index 5,
        // but observation was fetched at 14:00 (index 4) — 1 hour stale
        val hours = buildHours(start, actualsCount = 6, markCurrentHour = true, currentHourIndex = 5)
        val fetchedAtMs = start.plusHours(4).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        val clipRights = mutableListOf<Float>()
        every {
            anyConstructed<Canvas>().clipRect(any<Float>(), any<Float>(), capture(clipRights), any<Float>())
        } returns true

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 300,
            currentTime = start.plusHours(5),
            observedAt = fetchedAtMs, useCelsius = false,
        )

        val hourWidth = 800f / 8f
        val fetchApproxX = hourWidth * 4 + hourWidth / 2  // ~450
        val nowApproxX = hourWidth * 5 + hourWidth / 2    // ~550

        val actualLineClips = clipRights.filter { it < 800f - 1f }
        assertTrue("Expected at least one clipRect for actual line", actualLineClips.isNotEmpty())
        val actualLineRight = actualLineClips.maxOrNull()!!
        assertTrue(
            "Actual line clip right ($actualLineRight) should be near fetchDot (~$fetchApproxX) not NOW (~$nowApproxX)",
            actualLineRight < fetchApproxX + 10f
        )
    }

    @Test
    fun `actual line clipRect stops at last real anchor when later buckets are synthetic actuals`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        val hours =
            buildHours(start, actualsCount = 6, markCurrentHour = true, currentHourIndex = 6).mapIndexed { index, hour ->
                when {
                    index <= 3 -> hour.copy(isObservedActual = true)
                    index <= 5 -> hour.copy(isObservedActual = false)
                    else -> hour
                }
            }
        val anchorAtMs = start.plusHours(3).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        val clipRights = mutableListOf<Float>()
        every {
            anyConstructed<Canvas>().clipRect(any<Float>(), any<Float>(), capture(clipRights), any<Float>())
        } returns true

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 300,
            currentTime = start.plusHours(6),
            observedAt = anchorAtMs, useCelsius = false,
        )

        val hourWidth = 800f / 8f
        val anchorApproxX = hourWidth * 3 + hourWidth / 2
        val syntheticApproxX = hourWidth * 5 + hourWidth / 2

        val actualLineClips = clipRights.filter { it < 800f - 1f }
        assertTrue("Expected at least one clipRect for actual line", actualLineClips.isNotEmpty())
        val actualLineRight = actualLineClips.maxOrNull()!!
        assertTrue(
            "Actual line clip right ($actualLineRight) should be near real anchor (~$anchorApproxX) not synthetic actual (~$syntheticApproxX)",
            actualLineRight < anchorApproxX + 10f
        )
    }

    @Test
    fun `actual line geometry ends at fetch dot not later carry-forward actual bucket`() {
        val context = mockContext()
        val start = LocalDateTime.of(2026, 2, 20, 10, 0)
        val hours =
            buildHours(start, actualsCount = 6, markCurrentHour = true, currentHourIndex = 6).mapIndexed { index, hour ->
                when {
                    index <= 3 -> hour.copy(isObservedActual = true)
                    index <= 5 -> hour.copy(isObservedActual = false)
                    else -> hour
                }
            }
        val anchorAtMs = start.plusHours(3).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        var actualLineDebug: ActualLineDebug? = null
        var fetchDotDebug: FetchDotDebug? = null

        TemperatureGraphRenderer.renderGraph(
            context = context,
            hours = hours,
            widthPx = 800,
            heightPx = 300,
            currentTime = start.plusHours(6),
            observedAt = anchorAtMs,
            lastObservedTemp = 53f,
            onActualLineResolved = { actualLineDebug = it },
            onFetchDotResolved = { fetchDotDebug = it }, useCelsius = false,
        )

        val hourWidth = 800f / 8f
        val anchorApproxX = hourWidth * 3 + hourWidth / 2

        assertTrue("Actual line debug should be resolved", actualLineDebug != null)
        assertTrue("Fetch dot should be resolved", fetchDotDebug != null)
        assertEquals("Actual line should end at fetch dot X", fetchDotDebug!!.fetchDotX!!, actualLineDebug!!.endX!!, 0.01f)
        assertEquals("Actual line should end at fetch dot Y", fetchDotDebug!!.fetchY!!, actualLineDebug!!.endY!!, 0.01f)
        assertTrue(
            "Actual line end X (${actualLineDebug!!.endX}) should be near the real anchor (~$anchorApproxX)",
            actualLineDebug!!.endX!! < anchorApproxX + 10f,
        )
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
    ): List<HourData> {
        return (0 until total).map { offset ->
            val dt = start.plusHours(offset.toLong())
            val isActual = offset < actualsCount
            HourData(
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
