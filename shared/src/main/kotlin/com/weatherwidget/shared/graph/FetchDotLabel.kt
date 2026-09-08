package com.weatherwidget.shared.graph

import com.weatherwidget.shared.util.AgeFormatter

/**
 * Platform-free formatting for the fetch-dot staleness age label, shared by the Android widget
 * (`TemperatureGraphStyle.formatAgeLabel`) and the desktop graph (`TemperatureGraph`).
 *
 * Shows the age for any non-negative value, but only when the visible window is narrow enough that
 * freshness is meaningful — so it appears in the zoomed-in view and hides in the wide view. Returns
 * null when it should not be drawn. Format: "17m", "1h 5m".
 *
 * Negative ages (clock skew) return null; the Android caller additionally logs a warning before
 * delegating here.
 */
object FetchDotLabel {
    const val AGE_LABEL_MAX_HOURS_SPAN = 12L

    fun formatAgeLabel(
        ageMinutes: Long,
        spanHours: Long,
        maxSpanHours: Long = AGE_LABEL_MAX_HOURS_SPAN,
    ): String? = AgeFormatter.formatFetchDotLabel(ageMinutes, spanHours, maxSpanHours)
}
