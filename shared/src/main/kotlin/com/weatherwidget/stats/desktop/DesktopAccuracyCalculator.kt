package com.weatherwidget.stats.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.stats.AccuracyPure
import java.time.LocalDate

private const val MS_IN_A_DAY = 86_400_000L

/**
 * Forecast-accuracy calculator for the desktop, backed by [DesktopWeatherDao].
 * Pure computation delegates to shared [AccuracyPure].
 */
class DesktopAccuracyCalculator(private val dao: DesktopWeatherDao) {

    fun calculateAccuracy(source: String, lat: Double, lon: Double, days: Int = 30): AccuracyPure.AccuracyStatistics? {
        val daily = getDailyAccuracyBreakdown(source, lat, lon, days)
        return AccuracyPure.computeStatistics(daily, source, days)
    }

    fun getDailyAccuracyBreakdown(source: String, lat: Double, lon: Double, days: Int = 30): List<AccuracyPure.DailyAccuracy> {
        val endDate = LocalDate.now().minusDays(1)
        val startDate = endDate.minusDays(days.toLong() - 1)
        val startEpoch = startDate.toEpochDay() * MS_IN_A_DAY
        val endEpoch = endDate.toEpochDay() * MS_IN_A_DAY

        val actuals = dao.getExtremesInRange(startEpoch, endEpoch, lat, lon).filter { it.source == source }
        val forecasts = dao.getForecastsInRangeBySource(startEpoch, endEpoch, lat, lon, source)

        val result = mutableListOf<AccuracyPure.DailyAccuracy>()
        for (actual in actuals) {
            val targetEpoch = actual.date
            val targetDate = LocalDate.ofEpochDay(actual.date / MS_IN_A_DAY)
            val forecastEpoch = targetDate.minusDays(1).toEpochDay() * MS_IN_A_DAY

            val forecast = forecasts
                .filter { it.targetDate == targetEpoch && it.dateOfPrediction == forecastEpoch }
                .maxByOrNull { it.fetchedAt }
                ?: continue

            val entry = AccuracyPure.buildDailyAccuracy(
                date = targetDate.toString(),
                actualHigh = actual.highTemp,
                actualLow = actual.lowTemp,
                forecastHigh = forecast.highTemp,
                forecastLow = forecast.lowTemp,
                source = source,
            )
            if (entry != null) result.add(entry)
        }
        return result.sortedBy { it.date }
    }
}
