package com.weatherwidget.stats.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.AccuracyBaselineField
import com.weatherwidget.shared.stats.AccuracyPure
import com.weatherwidget.shared.stats.ActualsBaselineResolver
import com.weatherwidget.shared.stats.resolveBaselineTemps
import com.weatherwidget.shared.util.TempUtils
import java.time.LocalDate

private const val MS_IN_A_DAY = 86_400_000L

/**
 * Forecast-accuracy calculator for the desktop, backed by [DesktopWeatherDao].
 * Pure computation delegates to shared [AccuracyPure].
 *
 * Baseline selection mirrors Android's `AccuracyCalculator`: [ActualsBaselineResolver] picks whose
 * `daily_history` row supplies the actual (so a forecast-only source is never graded against its
 * own forecast re-filed as an observation), and [baselineField] picks which pair of temperatures on
 * that row to read.
 */
class DesktopAccuracyCalculator(
    private val dao: DesktopWeatherDao,
    private val orderedVisibleSources: List<WeatherSource> = listOf(WeatherSource.NWS),
    private val baselineField: AccuracyBaselineField = AccuracyBaselineField.DEFAULT,
) {

    fun calculateAccuracy(source: String, lat: Double, lon: Double, days: Int = 30): AccuracyPure.AccuracyStatistics? {
        val daily = getDailyAccuracyBreakdown(source, lat, lon, days)
        return AccuracyPure.computeStatistics(daily, source, days)
    }

    fun getDailyAccuracyBreakdown(source: String, lat: Double, lon: Double, days: Int = 30): List<AccuracyPure.DailyAccuracy> {
        val endDate = LocalDate.now().minusDays(1)
        val startDate = endDate.minusDays(days.toLong() - 1)
        val startEpoch = startDate.toEpochDay() * MS_IN_A_DAY
        val endEpoch = endDate.toEpochDay() * MS_IN_A_DAY

        val gradedSource = WeatherSource.fromId(source)
        val allRows = dao.getExtremesInRange(startEpoch, endEpoch, lat, lon)
        val forecasts = dao.getForecastsInRangeBySource(startEpoch, endEpoch, lat, lon, source)

        val result = mutableListOf<AccuracyPure.DailyAccuracy>()
        var current = startDate
        while (!current.isAfter(endDate)) {
            val targetDate = current
            current = current.plusDays(1)

            val targetEpoch = targetDate.toEpochDay() * MS_IN_A_DAY
            val forecastEpoch = targetDate.minusDays(1).toEpochDay() * MS_IN_A_DAY

            val forecast = forecasts
                .filter { it.targetDate == targetEpoch && it.dateOfPrediction == forecastEpoch }
                .maxByOrNull { it.fetchedAt }
                ?: continue

            val rowsForDate = allRows.filter { it.date == targetEpoch }
            val baselineSource = ActualsBaselineResolver.resolveBaselineSource(
                gradedSource = gradedSource,
                orderedVisibleSources = orderedVisibleSources,
                hasRowForDate = { candidate -> rowsForDate.any { it.source == candidate.id } },
            ) ?: continue

            val baselineRow = rowsForDate
                .filter { it.source == baselineSource.id }
                .minByOrNull { TempUtils.distanceSq(it.locationLat, it.locationLon, lat, lon) }
                ?: continue

            val temps = resolveBaselineTemps(
                field = baselineField,
                computedHigh = baselineRow.computedHighTemp,
                computedLow = baselineRow.computedLowTemp,
                apiHigh = baselineRow.apiHighTemp,
                apiLow = baselineRow.apiLowTemp,
            )

            val entry = AccuracyPure.buildDailyAccuracy(
                date = targetDate.toString(),
                computedHighTemp = temps.high,
                computedLowTemp = temps.low,
                forecastHigh = forecast.highTemp,
                forecastLow = forecast.lowTemp,
                source = source,
            )
            if (entry != null) result.add(entry)
        }
        return result.sortedBy { it.date }
    }
}
