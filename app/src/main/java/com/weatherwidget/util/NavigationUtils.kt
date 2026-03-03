package com.weatherwidget.util

import java.time.LocalDate
import java.time.LocalTime

/**
 * Centrally manages the calculation of day offsets and navigation bounds
 * for different widget widths.
 */
object NavigationUtils {
    /**
     * Hour when evening mode starts (6 PM). At or after this hour, the widget
     * skips history and shows today with forecast comparison.
     */
    const val EVENING_MODE_START_HOUR = 18

    /**
     * Checks if current time is in "evening mode" (6 PM or later).
     * In evening mode, the widget shows today+forecast instead of yesterday+today.
     */
    fun isEveningMode(time: LocalTime = LocalTime.now()): Boolean {
        return time.hour >= EVENING_MODE_START_HOUR
    }

    /**
     * Returns whether evening "skip history" mode should be applied for the current offset.
     * In evening mode, only offset 0 uses the today-first window.
     */
    fun shouldSkipHistory(
        isEveningMode: Boolean,
        dateOffset: Int,
    ): Boolean {
        return isEveningMode && dateOffset == 0
    }

    /**
     * Computes the center date for daily rendering/navigation.
     *
     * Evening mode uses a today-first window at offset 0. For negative offsets in evening mode,
     * shift the center forward by one day so moving left/right still advances exactly one day.
     */
    fun getDisplayCenterDate(
        today: LocalDate,
        dateOffset: Int,
        isEveningMode: Boolean,
    ): LocalDate {
        return if (isEveningMode && dateOffset != 0) {
            // Evening mode at offset 0 uses skipHistory to shift the window forward by 1 day
            // (showing today+6 instead of yesterday+5). Non-zero offsets don't use skipHistory,
            // so we shift the center by +1 to keep each offset step moving exactly 1 day.
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
        isEveningMode: Boolean,
    ): Pair<LocalDate, LocalDate> {
        val skipHistory = shouldSkipHistory(isEveningMode, dateOffset)
        val centerDate = getDisplayCenterDate(today, dateOffset, isEveningMode)
        val dayOffsets = getDayOffsets(numColumns, skipHistory)
        return centerDate.plusDays(dayOffsets.first()) to centerDate.plusDays(dayOffsets.last())
    }

    /**
     * Returns the list of day offsets relative to the center date that should be displayed.
     *
     * @param numColumns Number of grid columns available in the widget.
     * @param skipHistory If true, start from today (offset 0) instead of yesterday (offset -1).
     *                    Used in evening mode to show today's forecast comparison.
     * @return List of offsets (e.g., -1 for yesterday, 0 for today, 1 for tomorrow).
     */
    fun getDayOffsets(numColumns: Int, skipHistory: Boolean = false): List<Long> {
        // In evening mode with skipHistory, start from today (0) instead of yesterday (-1).
        // On very narrow widgets (1-2 columns), also start from today to prioritize immediate forecast.
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
