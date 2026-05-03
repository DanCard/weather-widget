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
}
