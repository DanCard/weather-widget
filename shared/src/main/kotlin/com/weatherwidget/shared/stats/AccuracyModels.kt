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
 * Day-by-day predicted-vs-measured rainfall for one source (mm). Predicted amounts come from the prior
 * day's hourly forecast snapshot; measured amounts are the shared gauge series
 * ([com.weatherwidget.shared.actuals.ObservedPrecip.measuredRows]). Every amount sums only the
 * source's **paired** hours ([RainScore.pair]); the day (8a-8p) and night (the date's hours outside
 * it) buckets are null when no paired hour fell in them, and the totals are null with no paired hour.
 */
data class DailyRainAccuracy(
    val date: String,
    val source: WeatherSource,
    val predDayMm: Float?,
    val actualDayMm: Float?,
    val predNightMm: Float?,
    val actualNightMm: Float?,
    val predTotalMm: Float? = null,
    val actualTotalMm: Float? = null,
    val pairedHours: Int = 0,
    /** Closest source of the date ([RainScore.compareDay]). */
    val closest: Boolean = false,
) {
    /** Enough paired hours for the day to be scored ([RainScore.MIN_PAIRED_HOURS]). */
    val counted: Boolean get() = pairedHours >= RainScore.MIN_PAIRED_HOURS
}

/** A source's rain-amount grade over the window ([RainScore.score]). */
data class RainSourceScore(
    val source: WeatherSource,
    val countedDays: Int,
    val rainDays: Int,
    /** Mean |predicted − measured| over rain days; null with none. */
    val meanAbsErrorMm: Float?,
    /** Mean predicted − measured over rain days (+ = forecast too wet); null with none. */
    val biasMm: Float?,
    /** Share of counted days where rain/no-rain at [RainScore.RAIN_DAY_MM] matched; null with none. */
    val hitRate: Float?,
) {
    val ranked: Boolean get() = rainDays >= RainScore.MIN_RAIN_DAYS && meanAbsErrorMm != null
}

/** [com.weatherwidget.shared.stats.RainAccuracyRunner]'s result for a site. */
data class RainAccuracyReport(
    /** Ranked best first ([RainScore.rank]). */
    val scores: List<RainSourceScore>,
    /** Per date (newest first), then sources in the requested order. */
    val days: List<DailyRainAccuracy>,
    /** Cross-source comparison per scored date. */
    val comparisons: Map<java.time.LocalDate, RainScore.DayComparison>,
    /** Oldest and newest dates the window covers (inclusive). */
    val firstDate: java.time.LocalDate,
    val lastDate: java.time.LocalDate,
)
