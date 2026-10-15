package com.weatherwidget.ui

import com.weatherwidget.shared.months.DailyZoomDensity
import com.weatherwidget.shared.months.MonthsSpan
import com.weatherwidget.test.category.ShortDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import java.time.LocalDate

/** The months screen's levels and gestures (pinch, drag, tap, Back), without a device. */
@Category(ShortDuration::class)
class MonthsScreenLogicTest {

    private val today = LocalDate.parse("2026-10-15")
    private val widthDp = 400f
    private val ctx = MonthsScreenContext(
        today = today,
        baseColumns = 6,
        graphWidthDp = widthDp,
        bounds = today.minusDays(500)..today.plusDays(10),
    )

    private fun MonthsScreenState.window() = MonthsScreenLogic.monthsWindow(this, ctx)

    @Test
    fun `opens on two months ending at the forecast tail`() {
        val state = MonthsScreenState()
        assertEquals(MonthsSpan.TWO, state.span)
        assertEquals(today.plusDays(10), state.window().end)
    }

    @Test
    fun `tap opens the daily column view centred on the tapped day, months back`() {
        val tapped = today.minusDays(200)
        val state = MonthsScreenLogic.tapMonths(MonthsScreenState(), ctx, tapped)
        assertEquals(MonthsScreenState.Mode.DAILY, state.mode)
        assertEquals(ctx.baseColumns, state.dailyColumns)
        val (first, last) = MonthsScreenLogic.dailyRange(state, ctx)
        assertTrue(!tapped.isBefore(first) && !tapped.isAfter(last))
        assertEquals(first.plusDays(((state.dailyColumns - 1) / 2).toLong()), tapped)
    }

    @Test
    fun `back returns from the column view to the same months position, then closes`() {
        val months = MonthsScreenLogic.panMonths(MonthsScreenState(), ctx, -90)
        val daily = MonthsScreenLogic.tapMonths(months, ctx, months.window().start.plusDays(5))
        val back = MonthsScreenLogic.back(daily)!!
        assertEquals(MonthsScreenState.Mode.MONTHS, back.mode)
        assertEquals(months.window(), back.window())
        assertNull(MonthsScreenLogic.back(back))
    }

    @Test
    fun `zoom out from the column view adds columns until too dense, then opens one month on its right edge`() {
        var state = MonthsScreenLogic.tapMonths(MonthsScreenState(), ctx, today.minusDays(100))
        val rightEdge = MonthsScreenLogic.dailyRange(state, ctx).second
        var steps = 0
        while (state.mode == MonthsScreenState.Mode.DAILY && steps < 50) {
            state = MonthsScreenLogic.zoomOut(state, ctx)
            steps++
            if (state.mode == MonthsScreenState.Mode.DAILY) {
                assertEquals("right edge stays put", rightEdge, MonthsScreenLogic.dailyRange(state, ctx).second)
                assertTrue(!DailyZoomDensity.tooDense(widthDp, state.dailyColumns))
            }
        }
        assertEquals(MonthsScreenState.Mode.MONTHS, state.mode)
        assertEquals(MonthsSpan.ONE, state.span)
        assertEquals(rightEdge, state.window().end)
    }

    @Test
    fun `pinch is reversible end to end`() {
        var state = MonthsScreenState(span = MonthsSpan.ONE, monthsEnd = today.minusDays(40))
        val spans = mutableListOf(state.span)
        repeat(4) {
            state = MonthsScreenLogic.zoomOut(state, ctx)
            spans += state.span
        }
        assertEquals(listOf(MonthsSpan.ONE, MonthsSpan.TWO, MonthsSpan.SIX, MonthsSpan.TWELVE, MonthsSpan.TWELVE), spans)
        repeat(3) { state = MonthsScreenLogic.zoomIn(state, ctx) }
        assertEquals(MonthsSpan.ONE, state.span)
        val edge = state.window().end
        state = MonthsScreenLogic.zoomIn(state, ctx)
        assertEquals(MonthsScreenState.Mode.DAILY, state.mode)
        assertEquals(DailyZoomDensity.maxColumns(widthDp), state.dailyColumns)
        assertEquals(edge, MonthsScreenLogic.dailyRange(state, ctx).second)
        // And straight back out: the densest column view hands off again at once.
        state = MonthsScreenLogic.zoomOut(state, ctx)
        assertEquals(MonthsScreenState.Mode.MONTHS, state.mode)
        assertEquals(edge, state.window().end)
    }

    @Test
    fun `zoom in on the column view stops at the base column count`() {
        val state = MonthsScreenLogic.tapMonths(MonthsScreenState(), ctx, today.minusDays(30))
        assertEquals(state, MonthsScreenLogic.zoomIn(state, ctx))
    }

    @Test
    fun `column view drag steps days and stops where the data ends`() {
        var state = MonthsScreenLogic.tapMonths(MonthsScreenState(), ctx, today.plusDays(5))
        state = MonthsScreenLogic.panDaily(state, ctx, 100)
        val (first, _) = MonthsScreenLogic.dailyRange(state, ctx)
        assertTrue("window still overlaps the data", !first.isAfter(ctx.bounds!!.endInclusive))
        val back = MonthsScreenLogic.panDaily(state, ctx, -3)
        assertEquals(state.dailyOffset - 3, back.dailyOffset)
    }
}
