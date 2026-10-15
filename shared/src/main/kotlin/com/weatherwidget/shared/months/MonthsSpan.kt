package com.weatherwidget.shared.months

/**
 * How many months the months view (the line-graph zoom-out of the daily view) shows at once.
 * Pinch (Android) and the scroll wheel (desktop) step through these in order.
 * See plans/261015-months-line-graph-app-screen-zooms-to-daily.md.
 */
enum class MonthsSpan(val months: Int) {
    ONE(1),
    TWO(2),
    SIX(6),
    TWELVE(12);

    /** The next wider span, or null at [TWELVE]. */
    fun wider(): MonthsSpan? = entries.getOrNull(ordinal + 1)

    /** The next narrower span, or null at [ONE]. */
    fun narrower(): MonthsSpan? = entries.getOrNull(ordinal - 1)

    companion object {
        /** User's choice, 2026-10-15. */
        val DEFAULT = TWO

        fun fromMonths(months: Int?): MonthsSpan = entries.find { it.months == months } ?: DEFAULT
    }
}
