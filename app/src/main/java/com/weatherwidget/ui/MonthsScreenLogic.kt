package com.weatherwidget.ui

import com.weatherwidget.shared.months.DailyZoomDensity
import com.weatherwidget.shared.months.MonthsDailyJump
import com.weatherwidget.shared.months.MonthsSpan
import com.weatherwidget.shared.months.MonthsViewport
import com.weatherwidget.shared.months.MonthsWindow
import com.weatherwidget.shared.months.MonthsZoomAction
import com.weatherwidget.shared.months.MonthsZoomController
import com.weatherwidget.util.NavigationUtils
import java.time.LocalDate

/**
 * The months screen's two levels — the months line graph and the daily column view — and how
 * gestures move between them. Pure; the zoom rules themselves are the shared
 * [MonthsZoomController] (desktop's scroll wheel asks the same one).
 */
internal data class MonthsScreenState(
    val mode: Mode = Mode.MONTHS,
    val span: MonthsSpan = MonthsSpan.DEFAULT,
    /** Months view right edge; null = where the data ends. Kept while the column view is open, so Back returns to it. */
    val monthsEnd: LocalDate? = null,
    val dailyOffset: Int = 0,
    val dailyColumns: Int = 0,
) {
    enum class Mode { MONTHS, DAILY }
}

/** What the logic needs to know about the screen and the data. */
internal data class MonthsScreenContext(
    val today: LocalDate,
    /** Column count the column view opens with: the user's widget's. */
    val baseColumns: Int,
    /** Width the day columns share (the widget's graph width). */
    val graphWidthDp: Float,
    val bounds: ClosedRange<LocalDate>?,
)

internal object MonthsScreenLogic {
    fun skipYesterday(columns: Int): Boolean = NavigationUtils.shouldSkipYesterday(columns)

    fun monthsWindow(state: MonthsScreenState, ctx: MonthsScreenContext): MonthsWindow {
        val end = MonthsViewport.clampEnd(state.monthsEnd ?: MonthsViewport.defaultEnd(ctx.today, ctx.bounds), state.span, ctx.bounds)
        return MonthsViewport.window(state.span, end)
    }

    /** The column view's visible dates (first, last). */
    fun dailyRange(state: MonthsScreenState, ctx: MonthsScreenContext): Pair<LocalDate, LocalDate> =
        NavigationUtils.getVisibleDateRange(ctx.today, state.dailyOffset, state.dailyColumns, skipYesterday(state.dailyColumns))

    /** Pinch with fingers together. */
    fun zoomOut(state: MonthsScreenState, ctx: MonthsScreenContext): MonthsScreenState = when (state.mode) {
        MonthsScreenState.Mode.MONTHS -> apply(MonthsZoomController.monthsZoomOut(state.span), state, ctx)
        MonthsScreenState.Mode.DAILY -> {
            val (first, last) = dailyRange(state, ctx)
            val moreHistory = ctx.bounds?.let { first.isAfter(it.start) } == true
            apply(MonthsZoomController.dailyZoomOut(ctx.graphWidthDp, state.dailyColumns, moreHistory, last), state, ctx)
        }
    }

    /** Pinch with fingers apart. */
    fun zoomIn(state: MonthsScreenState, ctx: MonthsScreenContext): MonthsScreenState = when (state.mode) {
        MonthsScreenState.Mode.MONTHS -> apply(MonthsZoomController.monthsZoomIn(state.span, monthsWindow(state, ctx)), state, ctx)
        MonthsScreenState.Mode.DAILY -> {
            if (state.dailyColumns <= ctx.baseColumns) {
                state
            } else {
                val (_, last) = dailyRange(state, ctx)
                withColumns(state, ctx, state.dailyColumns - 1, last)
            }
        }
    }

    fun tapMonths(state: MonthsScreenState, ctx: MonthsScreenContext, date: LocalDate): MonthsScreenState =
        apply(MonthsZoomController.monthsTap(date), state, ctx)

    fun panMonths(state: MonthsScreenState, ctx: MonthsScreenContext, days: Int): MonthsScreenState {
        val window = monthsWindow(state, ctx)
        val end = MonthsViewport.pan(window.end, days, state.span, ctx.bounds)
        return if (end == window.end) state else state.copy(monthsEnd = end)
    }

    /** Steps the column view [columns] days, stopping where the window would leave the data. */
    fun panDaily(state: MonthsScreenState, ctx: MonthsScreenContext, columns: Int): MonthsScreenState {
        if (columns == 0) return state
        val step = if (columns > 0) 1 else -1
        var next = state
        repeat(kotlin.math.abs(columns)) {
            val candidate = next.copy(dailyOffset = next.dailyOffset + step)
            val (first, last) = dailyRange(candidate, ctx)
            val bounds = ctx.bounds ?: return next
            if (last.isBefore(bounds.start) || first.isAfter(bounds.endInclusive)) return next
            next = candidate
        }
        return next
    }

    /** Back: the column view returns to the months view; the months view closes the screen (null). */
    fun back(state: MonthsScreenState): MonthsScreenState? =
        if (state.mode == MonthsScreenState.Mode.DAILY) state.copy(mode = MonthsScreenState.Mode.MONTHS) else null

    private fun withColumns(state: MonthsScreenState, ctx: MonthsScreenContext, columns: Int, rightEdge: LocalDate) =
        state.copy(
            dailyColumns = columns,
            dailyOffset = MonthsDailyJump.offsetWithRightEdge(rightEdge, ctx.today, columns, skipYesterday(columns)),
        )

    private fun apply(action: MonthsZoomAction, state: MonthsScreenState, ctx: MonthsScreenContext): MonthsScreenState =
        when (action) {
            MonthsZoomAction.None -> state
            MonthsZoomAction.AddHistoryColumn -> {
                val (_, last) = dailyRange(state, ctx)
                withColumns(state, ctx, state.dailyColumns + 1, last)
            }
            is MonthsZoomAction.OpenMonths ->
                state.copy(mode = MonthsScreenState.Mode.MONTHS, span = action.span, monthsEnd = action.endDate)
            is MonthsZoomAction.SetSpan -> state.copy(span = action.span, monthsEnd = monthsWindow(state, ctx).end)
            // Back in from one month: the densest column view, right edge kept — the way out, reversed.
            is MonthsZoomAction.BackToDaily -> withColumns(
                state.copy(mode = MonthsScreenState.Mode.DAILY),
                ctx,
                DailyZoomDensity.maxColumns(ctx.graphWidthDp).coerceAtLeast(ctx.baseColumns),
                action.rightEdge,
            )
            is MonthsZoomAction.OpenDaily -> state.copy(
                mode = MonthsScreenState.Mode.DAILY,
                dailyColumns = ctx.baseColumns,
                dailyOffset = MonthsDailyJump.offsetCentring(action.date, ctx.today, ctx.baseColumns, skipYesterday(ctx.baseColumns)),
            )
        }
}
