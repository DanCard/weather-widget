package com.weatherwidget.util

import java.time.LocalDate
import java.time.LocalTime

/**
 * Centrally manages the calculation of day offsets and navigation bounds
 * for different widget widths.
 */
object NavigationUtils {
    /**
     * Hour at which narrow widgets (8 or fewer columns) drop yesterday from the
     * day window and shift forward, gaining an extra forecast day. Wide widgets
     * never shift early — they have room for yesterday plus a long forecast,
     * and let the calendar date roll over naturally.
     */
    const val NARROW_SKIP_YESTERDAY_HOUR = 8

    /**
     * Column threshold at or below which the narrow skip-yesterday rule applies.
     */
    const val NARROW_SKIP_YESTERDAY_COLUMN_THRESHOLD = 8

    /**
     * Returns true when the widget should drop yesterday from its day window
     * (showing today + forecast instead of yesterday + today + forecast).
     * Only narrow widgets shift early; wide widgets always include yesterday
     * until the calendar date rolls over.
     */
    fun shouldSkipYesterday(time: LocalTime = LocalTime.now(), numColumns: Int = Int.MAX_VALUE): Boolean {
        if (numColumns > NARROW_SKIP_YESTERDAY_COLUMN_THRESHOLD) return false
        return time.hour >= NARROW_SKIP_YESTERDAY_HOUR
    }

    /**
     * Returns whether history should be skipped at the given offset.
     * Only offset 0 uses the today-first window when skipYesterday is on.
     */
    fun shouldSkipHistory(
        skipYesterday: Boolean,
        dateOffset: Int,
    ): Boolean {
        return skipYesterday && dateOffset == 0
    }

    /**
     * Computes the center date for daily rendering/navigation.
     *
     * Skip-yesterday at offset 0 uses a today-first window. For non-zero offsets
     * we shift the center forward by one day so moving left/right still advances
     * exactly one day. Without this shift, going from offset 0 (today) to
     * offset 1 would jump two days because skipHistory drops at offset 0 only.
     */
    fun getDisplayCenterDate(
        today: LocalDate,
        dateOffset: Int,
        skipYesterday: Boolean,
    ): LocalDate {
        return if (skipYesterday && dateOffset != 0) {
            today.plusDays(dateOffset.toLong() + 1L)
        } else {
            today.plusDays(dateOffset.toLong())
        }
    }

    /**
     * Returns the leftmost and rightmost visible dates for the given offset and widget width.
     */
    fun getVisibleDateRange(
        today: LocalDate,
        dateOffset: Int,
        numColumns: Int,
        skipYesterday: Boolean,
    ): Pair<LocalDate, LocalDate> {
        val skipHistory = shouldSkipHistory(skipYesterday, dateOffset)
        val centerDate = getDisplayCenterDate(today, dateOffset, skipYesterday)
        val dayOffsets = getDayOffsets(numColumns, skipHistory)
        return centerDate.plusDays(dayOffsets.first()) to centerDate.plusDays(dayOffsets.last())
    }

    /**
     * Whether the daily header's current-observations button should show for a visible window.
     *
     * True when today OR yesterday falls inside [visibleFrom]..[visibleTo]. Yesterday is included
     * because the station-history affordance behind the button is date-independent (tapping a
     * station opens its NWS time-series page), so a user panned back to yesterday still has a
     * reason to reach it. Contrast the forecast-history button, whose target date is strictly
     * today-vs-centre and so is not covered by this predicate.
     */
    fun isTodayOrYesterdayInRange(
        today: LocalDate,
        visibleFrom: LocalDate,
        visibleTo: LocalDate,
    ): Boolean {
        fun inRange(date: LocalDate): Boolean = !date.isBefore(visibleFrom) && !date.isAfter(visibleTo)
        return inRange(today) || inRange(today.minusDays(1))
    }

    /**
     * Returns the list of day offsets relative to the center date that should be displayed.
     *
     * @param numColumns Number of grid columns available in the widget.
     * @param skipHistory If true, start from today (offset 0) instead of yesterday (offset -1).
     * @return List of offsets (e.g., -1 for yesterday, 0 for today, 1 for tomorrow).
     */
    fun getDayOffsets(numColumns: Int, skipHistory: Boolean = false): List<Long> {
        // On very narrow widgets (1-2 columns), always start from today to prioritize immediate forecast.
        val startOffset = if (skipHistory || numColumns <= 2) 0L else -1L

        return when {
            numColumns >= 3 -> (0 until numColumns.toLong()).map { startOffset + it }
            numColumns == 2 -> listOf(0L, 1L) // 2 days - always starts with today
            else -> listOf(0L) // 1 day - always shows today
        }
    }

    /**
     * Returns the leftmost offset relative to the center date.
     *
     * @param numColumns Number of grid columns available in the widget.
     * @param skipHistory If true, start from today (offset 0) instead of yesterday (offset -1).
     */
    fun getMinOffset(numColumns: Int, skipHistory: Boolean = false): Int {
        return getDayOffsets(numColumns, skipHistory).first().toInt()
    }

    /**
     * Returns the rightmost offset relative to the center date.
     *
     * @param numColumns Number of grid columns available in the widget.
     * @param skipHistory If true, start from today (offset 0) instead of yesterday (offset -1).
     */
    fun getMaxOffset(numColumns: Int, skipHistory: Boolean = false): Int {
        return getDayOffsets(numColumns, skipHistory).last().toInt()
    }

    /**
     * Days of history / forecast the daily view must actually LOAD to render a widget, relative to
     * today. Derived from [getVisibleDateRange] so the load window can never drift away from what
     * the render draws.
     *
     * Deliberately NOT a constant: the render horizon is width-derived
     * (`numColumns` reaching `today + dateOffset + numColumns - 2`), so a flat "30 days" over-fetches
     * by ~3x for a 10-column widget at offset 0 and under-fetches for a very wide one. Both have
     * happened; see plans/260803-daily-load-window-right-sizing.md.
     *
     * This sizes the DATABASE QUERY only. It intentionally does **not** bound how far the user can
     * navigate: forward reach comes from `ClimateGapFiller`'s in-memory `GENERIC_GAP` rows (~30
     * cheap synthesized entities, no query) and backward reach from the observation dates, both of
     * which stay at their own horizons.
     */
    data class DailyLoadWindow(val historyDays: Long, val forecastDays: Long) {
        /** Widest of two windows, for paths that serve several widgets from one shared load. */
        fun coerceAtLeast(other: DailyLoadWindow) =
            DailyLoadWindow(
                historyDays = maxOf(historyDays, other.historyDays),
                forecastDays = maxOf(forecastDays, other.forecastDays),
            )
    }

    /**
     * @param headroomDays extra days on each side so a boundary/rollover rounding error, or a nav tap
     *   racing the repaint, cannot land on an unloaded column.
     */
    fun dailyLoadWindow(
        today: LocalDate,
        dateOffset: Int,
        numColumns: Int,
        skipYesterday: Boolean,
        headroomDays: Long = 1L,
    ): DailyLoadWindow {
        val (leftmost, rightmost) = getVisibleDateRange(today, dateOffset, numColumns, skipYesterday)
        return DailyLoadWindow(
            historyDays = java.time.temporal.ChronoUnit.DAYS.between(leftmost, today)
                .coerceAtLeast(0L) + headroomDays,
            forecastDays = java.time.temporal.ChronoUnit.DAYS.between(today, rightmost)
                .coerceAtLeast(0L) + headroomDays,
        )
    }
}
