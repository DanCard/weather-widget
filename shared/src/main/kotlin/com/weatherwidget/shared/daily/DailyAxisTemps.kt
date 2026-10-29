package com.weatherwidget.shared.daily

/**
 * The temperatures a daily column actually draws — the values the graph's y-axis must span.
 *
 * The prior-day snapshot ("yesterday's forecast") is a bar only in today's and past columns' triple
 * bar. A future column carries a snapshot too (DailyColumnBuilder fills it from the display
 * snapshot) but never draws it, so counting it stretched the axis past the tallest visible bar and
 * left the top of the graph empty. A future snapshot still counts when the column has no solid or
 * forecast high, since the high label then falls back to it.
 */
object DailyAxisTemps {
    fun drawn(
        isPast: Boolean,
        isToday: Boolean,
        solidHigh: Float?,
        solidLow: Float?,
        forecastHigh: Float?,
        forecastLow: Float?,
        ghostHigh: Float?,
        snapshotHigh: Float?,
        snapshotLow: Float?,
    ): List<Float> {
        val snapshotDrawn = isPast || isToday || (solidHigh == null && forecastHigh == null)
        return listOfNotNull(
            solidHigh,
            solidLow,
            forecastHigh,
            forecastLow,
            ghostHigh,
            snapshotHigh.takeIf { snapshotDrawn },
            snapshotLow.takeIf { snapshotDrawn },
        )
    }
}
