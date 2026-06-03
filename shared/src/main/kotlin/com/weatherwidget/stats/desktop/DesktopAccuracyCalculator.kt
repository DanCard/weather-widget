package com.weatherwidget.stats.desktop

import com.weatherwidget.data.local.desktop.DailyExtremesComputer.MS_IN_A_DAY
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Forecast-accuracy statistics for a single source over a lookback window. Mirrors the Android
 * `AccuracyStatistics` but lives in the `desktop` sub-package to avoid colliding with the Android
 * Room-backed class on the shared classpath (`:app` depends on `:shared`).
 */
data class DesktopAccuracyStatistics(
    val source: String,
    val avgHighError: Double,
    val avgLowError: Double,
    val highBias: Double, // signed; + = forecast ran low (actual warmer than forecast)
    val lowBias: Double,
    val avgError: Double,
    val maxError: Int,
    val percentWithin3Degrees: Double,
    val accuracyScore: Double, // 0–5, 5 = perfect
    val totalForecasts: Int,
    val periodDays: Int,
)

/** One day's forecast-vs-actual comparison (all temps rounded to whole °F, as the widget shows). */
data class DesktopDailyAccuracy(
    val date: String,
    val actualHigh: Int,
    val actualLow: Int,
    val forecastHigh: Int,
    val forecastLow: Int,
    val source: String,
    val highError: Int, // actual − forecast
    val lowError: Int,
)

/**
 * Compares stored 1-day-ahead forecast snapshots against observed daily extremes. Pure logic over
 * DAO reads — no network, no Android dependencies — so it is unit-testable against a temp-file DB.
 *
 * Ported from `app/.../stats/AccuracyCalculator.kt` (Room DAOs swapped for [DesktopWeatherDao]).
 */
class DesktopAccuracyCalculator(private val dao: DesktopWeatherDao) {

    fun calculateAccuracy(source: String, lat: Double, lon: Double, days: Int = 30): DesktopAccuracyStatistics? {
        val daily = getDailyAccuracyBreakdown(source, lat, lon, days)
        if (daily.isEmpty()) return null

        val n = daily.size
        val avgHighError = daily.sumOf { abs(it.highError) }.toDouble() / n
        val avgLowError = daily.sumOf { abs(it.lowError) }.toDouble() / n
        val avgError = (avgHighError + avgLowError) / 2

        val highBias = daily.sumOf { it.highError }.toDouble() / n
        val lowBias = daily.sumOf { it.lowError }.toDouble() / n
        val maxError = daily.maxOf { maxOf(abs(it.highError), abs(it.lowError)) }
        val within3 = daily.count { abs(it.highError) <= 3 && abs(it.lowError) <= 3 }
        val percentWithin3 = within3.toDouble() / n * 100

        return DesktopAccuracyStatistics(
            source = source,
            avgHighError = avgHighError,
            avgLowError = avgLowError,
            highBias = highBias,
            lowBias = lowBias,
            avgError = avgError,
            maxError = maxError,
            percentWithin3Degrees = percentWithin3,
            accuracyScore = calculateScore(avgError),
            totalForecasts = n,
            periodDays = days,
        )
    }

    fun getDailyAccuracyBreakdown(source: String, lat: Double, lon: Double, days: Int = 30): List<DesktopDailyAccuracy> {
        val endDate = LocalDate.now().minusDays(1)
        val startDate = endDate.minusDays(days.toLong() - 1)
        val startEpoch = startDate.toEpochDay() * MS_IN_A_DAY
        val endEpoch = endDate.toEpochDay() * MS_IN_A_DAY

        val actuals = dao.getExtremesInRange(startEpoch, endEpoch, lat, lon).filter { it.source == source }
        val forecasts = dao.getForecastsInRangeBySource(startEpoch, endEpoch, lat, lon, source)

        val result = mutableListOf<DesktopDailyAccuracy>()
        for (actual in actuals) {
            val targetEpoch = actual.date // already UTC-midnight epoch ms (epochDay * MS_IN_A_DAY)
            val targetDate = LocalDate.ofEpochDay(actual.date / MS_IN_A_DAY)
            val forecastEpoch = targetDate.minusDays(1).toEpochDay() * MS_IN_A_DAY

            val forecast = forecasts
                .filter { it.targetDate == targetEpoch && it.forecastDate == forecastEpoch }
                .maxByOrNull { it.fetchedAt }
                ?: continue
            val fHigh = forecast.highTemp ?: continue
            val fLow = forecast.lowTemp ?: continue

            val aHigh = actual.highTemp.roundToInt()
            val aLow = actual.lowTemp.roundToInt()
            val fHighR = fHigh.roundToInt()
            val fLowR = fLow.roundToInt()

            result.add(
                DesktopDailyAccuracy(
                    date = targetDate.toString(),
                    actualHigh = aHigh,
                    actualLow = aLow,
                    forecastHigh = fHighR,
                    forecastLow = fLowR,
                    source = source,
                    highError = aHigh - fHighR,
                    lowError = aLow - fLowR,
                )
            )
        }
        return result.sortedBy { it.date }
    }

    private fun calculateScore(avgError: Double): Double = when {
        avgError <= PERFECT_THRESHOLD -> 5.0
        avgError <= EXCELLENT_THRESHOLD -> 5.0 - ((avgError - PERFECT_THRESHOLD) * 0.5)
        avgError <= GOOD_THRESHOLD -> 4.5 - ((avgError - EXCELLENT_THRESHOLD) * 0.5)
        avgError <= FAIR_THRESHOLD -> 4.0 - ((avgError - GOOD_THRESHOLD) * 0.5)
        else -> maxOf(0.0, 3.5 - ((avgError - FAIR_THRESHOLD) * 0.5))
    }

    companion object {
        private const val PERFECT_THRESHOLD = 1.0
        private const val EXCELLENT_THRESHOLD = 2.0
        private const val GOOD_THRESHOLD = 3.0
        private const val FAIR_THRESHOLD = 4.0
    }
}
