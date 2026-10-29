package com.weatherwidget.shared.daily

import com.weatherwidget.data.remote.NwsTemperaturePlausibility

/**
 * The daily graph's y-axis: exactly the drawn temperatures ([DailyAxisTemps]), with no padding — the
 * coldest bar ends at the graph bottom and the hottest at the graph top. The labels above and below
 * get their room from DailyTopStack and DailyBottomStack, not from the axis
 * (plans/261029-daily-bottom-ink-gap.md). Shared so Android and desktop span the same range.
 */
data class DailyAxisRange(
    val min: Float,
    val max: Float,
    /** Implausible temperatures left out of the axis (a corrupt reading would flatten every bar). */
    val rejected: Int = 0,
    val firstRejected: Float = Float.NaN,
) {
    val range: Float get() = (max - min).coerceAtLeast(1f)

    companion object {
        fun of(temps: Iterable<Float>): DailyAxisRange {
            var min = Float.POSITIVE_INFINITY
            var max = Float.NEGATIVE_INFINITY
            var rejected = 0
            var firstRejected = Float.NaN
            temps.forEach { t ->
                if (!NwsTemperaturePlausibility.isPlausibleF(t)) {
                    if (rejected == 0) firstRejected = t
                    rejected++
                } else {
                    if (t < min) min = t
                    if (t > max) max = t
                }
            }
            return DailyAxisRange(
                min = if (min.isFinite()) min else 0f,
                max = if (max.isFinite()) max else 100f,
                rejected = rejected,
                firstRejected = firstRejected,
            )
        }
    }
}
