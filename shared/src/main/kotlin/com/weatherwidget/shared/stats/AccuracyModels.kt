package com.weatherwidget.shared.stats

import com.weatherwidget.data.model.WeatherSource

/**
 * Cross-platform accuracy display models. Android's Statistics screen and the desktop statistics
 * window read the same types so per-source scoring and provenance never drift between platforms.
 */

/**
 * Comparison of accuracy statistics between all API sources.
 *
 * Keyed by source rather than one named field per source, so a new
 * [com.weatherwidget.shared.util.WeatherSourceOrdering.ALL_CONFIGURABLE] entry is scored and shown
 * without editing this type or the screens that read it.
 */
data class ComparisonStatistics(
    val bySource: Map<WeatherSource, AccuracyPure.AccuracyStatistics?>,
    val periodStart: String,
    val periodEnd: String,
) {
    fun statsFor(source: WeatherSource): AccuracyPure.AccuracyStatistics? = bySource[source]
}

/**
 * Day-by-day predicted-vs-actual rainfall, split into clock-based day (8a-8p) and night (8p-8a)
 * totals (mm). Predicted totals come from the prior day's hourly forecast snapshot; actual totals
 * come from observed rainfall. A bucket is null when no data was available for it.
 */
data class DailyRainAccuracy(
    val date: String,
    val source: String,
    val predDayMm: Float?,
    val actualDayMm: Float?,
    val predNightMm: Float?,
    val actualNightMm: Float?,
)
