package com.weatherwidget.shared.stats

/**
 * Cross-platform accuracy display models. Android's Statistics screen and the desktop statistics
 * window read the same types so per-source scoring and provenance never drift between platforms.
 */

/**
 * Comparison of accuracy statistics between all API sources.
 */
data class ComparisonStatistics(
    val nwsStats: AccuracyPure.AccuracyStatistics?,
    val visualCrossingStats: AccuracyPure.AccuracyStatistics?,
    val openWeatherMapStats: AccuracyPure.AccuracyStatistics?,
    val meteoStats: AccuracyPure.AccuracyStatistics?,
    val weatherApiStats: AccuracyPure.AccuracyStatistics?,
    val tomorrowIoStats: AccuracyPure.AccuracyStatistics? = null,
    val silurianStats: AccuracyPure.AccuracyStatistics? = null,
    val periodStart: String,
    val periodEnd: String,
)

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
