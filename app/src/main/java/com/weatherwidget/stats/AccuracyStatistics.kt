package com.weatherwidget.stats

/**
 * Statistics about forecast accuracy for a single API source.
 */
data class AccuracyStatistics(
    val source: String,                    // "NWS" or "Open-Meteo"
    val avgError: Double,                  // Average absolute difference
    val maxError: Int,                     // Maximum absolute difference
    val percentWithin3Degrees: Double,     // Percentage within 3°
    val accuracyScore: Double,             // 0-5 rating (5 = perfect)
    val totalForecasts: Int,
    val periodDays: Int
)

/**
 * Comparison of accuracy statistics between both API sources.
 */
data class ComparisonStatistics(
    val nwsStats: AccuracyStatistics?,
    val meteoStats: AccuracyStatistics?,
    val periodStart: String,
    val periodEnd: String
)

/**
 * Day-by-day accuracy breakdown for a single forecast.
 */
data class DailyAccuracy(
    val date: String,
    val actualHigh: Int,
    val actualLow: Int,
    val forecastHigh: Int,
    val forecastLow: Int,
    val source: String,
    val highError: Int,
    val lowError: Int
)
