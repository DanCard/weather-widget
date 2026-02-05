package com.weatherwidget.util

/**
 * Centrally manages the calculation of day offsets and navigation bounds
 * for different widget widths.
 */
object NavigationUtils {

    /**
     * Returns the list of day offsets relative to the center date that should be displayed.
     * 
     * @param numColumns Number of grid columns available in the widget.
     * @return List of offsets (e.g., -1 for yesterday, 0 for today, 1 for tomorrow).
     */
    fun getDayOffsets(numColumns: Int): List<Long> {
        return when {
            numColumns >= 8 -> listOf(-1L, 0L, 1L, 2L, 3L, 4L, 5L)  // 7 days
            numColumns == 7 -> listOf(-1L, 0L, 1L, 2L, 3L, 4L)       // 6 days
            numColumns == 6 -> listOf(-1L, 0L, 1L, 2L, 3L, 4L)       // 6 days
            numColumns == 5 -> listOf(-1L, 0L, 1L, 2L, 3L)           // 5 days
            numColumns == 4 -> listOf(-1L, 0L, 1L, 2L)               // 4 days
            numColumns == 3 -> listOf(-1L, 0L, 1L)                   // 3 days
            numColumns == 2 -> listOf(0L, 1L)                        // 2 days
            else -> listOf(0L)                                       // 1 day
        }
    }

    /**
     * Returns the leftmost offset relative to the center date.
     */
    fun getMinOffset(numColumns: Int): Int {
        return getDayOffsets(numColumns).first().toInt()
    }

    /**
     * Returns the rightmost offset relative to the center date.
     */
    fun getMaxOffset(numColumns: Int): Int {
        return getDayOffsets(numColumns).last().toInt()
    }
}
