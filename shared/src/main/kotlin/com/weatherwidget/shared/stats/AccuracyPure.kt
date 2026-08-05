package com.weatherwidget.shared.stats

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure-Kotlin forecast accuracy scoring and statistics computation.
 * Platform-specific DAO layers supply the data; this object computes the metrics.
 */
object AccuracyPure {

    private const val PERFECT_THRESHOLD = 1.0
    private const val EXCELLENT_THRESHOLD = 2.0
    private const val GOOD_THRESHOLD = 3.0
    private const val FAIR_THRESHOLD = 4.0

    /** One day's forecast-vs-actual comparison (all temps rounded to whole degrees). */
    data class DailyAccuracy(
        val date: String,
        val computedHighTemp: Int,
        val computedLowTemp: Int,
        val forecastHigh: Int,
        val forecastLow: Int,
        val source: String,
        val highError: Int, // actual - forecast
        val lowError: Int,
    )

    /** Aggregate accuracy statistics for a single source over a lookback window. */
    data class AccuracyStatistics(
        val source: String,
        val avgHighError: Double,
        val avgLowError: Double,
        val highBias: Double,
        val lowBias: Double,
        val avgError: Double,
        val maxError: Int,
        val percentWithin3Degrees: Double,
        val accuracyScore: Double,
        val totalForecasts: Int,
        val periodDays: Int,
    )

    /** Computes aggregate accuracy statistics from a list of daily accuracy entries. */
    fun computeStatistics(dailyAccuracies: List<DailyAccuracy>, sourceName: String, days: Int): AccuracyStatistics? {
        if (dailyAccuracies.isEmpty()) return null

        val n = dailyAccuracies.size
        val totalHighError = dailyAccuracies.sumOf { abs(it.highError) }
        val totalLowError = dailyAccuracies.sumOf { abs(it.lowError) }
        val avgHighError = totalHighError.toDouble() / n
        val avgLowError = totalLowError.toDouble() / n
        val avgError = (avgHighError + avgLowError) / 2

        val highBias = dailyAccuracies.sumOf { it.highError }.toDouble() / n
        val lowBias = dailyAccuracies.sumOf { it.lowError }.toDouble() / n

        val maxError = dailyAccuracies.maxOf { maxOf(abs(it.highError), abs(it.lowError)) }

        val within3Degrees = dailyAccuracies.count {
            abs(it.highError) <= 3 && abs(it.lowError) <= 3
        }
        val percentWithin3 = (within3Degrees.toDouble() / n) * 100

        val score = calculateScore(avgError)

        return AccuracyStatistics(
            source = sourceName,
            avgHighError = avgHighError,
            avgLowError = avgLowError,
            highBias = highBias,
            lowBias = lowBias,
            avgError = avgError,
            maxError = maxError,
            percentWithin3Degrees = percentWithin3,
            accuracyScore = score,
            totalForecasts = n,
            periodDays = days,
        )
    }

    /** Computes the 0-5 accuracy score from average absolute error. */
    fun calculateScore(avgError: Double): Double {
        return when {
            avgError <= PERFECT_THRESHOLD -> 5.0
            avgError <= EXCELLENT_THRESHOLD -> 5.0 - ((avgError - PERFECT_THRESHOLD) * 0.5)
            avgError <= GOOD_THRESHOLD -> 4.5 - ((avgError - EXCELLENT_THRESHOLD) * 0.5)
            avgError <= FAIR_THRESHOLD -> 4.0 - ((avgError - GOOD_THRESHOLD) * 0.5)
            else -> maxOf(0.0, 3.5 - ((avgError - FAIR_THRESHOLD) * 0.5))
        }
    }

    /**
     * Builds a [DailyAccuracy] entry from raw forecast and actual temperatures.
     * Returns null if forecast high or low is missing.
     */
    fun buildDailyAccuracy(
        date: String,
        computedHighTemp: Float,
        computedLowTemp: Float,
        forecastHigh: Float?,
        forecastLow: Float?,
        source: String,
    ): DailyAccuracy? {
        val fHigh = forecastHigh ?: return null
        val fLow = forecastLow ?: return null
        val aHighR = computedHighTemp.roundToInt()
        val aLowR = computedLowTemp.roundToInt()
        val fHighR = fHigh.roundToInt()
        val fLowR = fLow.roundToInt()
        return DailyAccuracy(
            date = date,
            computedHighTemp = aHighR,
            computedLowTemp = aLowR,
            forecastHigh = fHighR,
            forecastLow = fLowR,
            source = source,
            highError = aHighR - fHighR,
            lowError = aLowR - fLowR,
        )
    }
}
