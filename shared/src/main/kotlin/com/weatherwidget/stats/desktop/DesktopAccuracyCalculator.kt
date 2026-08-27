package com.weatherwidget.stats.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.AccuracyBaselineField
import com.weatherwidget.shared.stats.AccuracyBreakdown
import com.weatherwidget.shared.stats.AccuracyPure
import java.time.LocalDate

/**
 * Forecast-accuracy calculator for the desktop, backed by [DesktopWeatherDao].
 *
 * Pure computation delegates to the shared [AccuracyBreakdown] loop and [AccuracyPure] scoring.
 * Baseline selection mirrors Android's `AccuracyCalculator` via [AccuracyBreakdown] (which uses
 * [com.weatherwidget.shared.stats.ActualsBaselineResolver] internally); [baselineField] picks which
 * pair of temperatures on the resolved row to read.
 */
class DesktopAccuracyCalculator(
    private val dao: DesktopWeatherDao,
    private val orderedVisibleSources: List<WeatherSource> = listOf(WeatherSource.NWS),
    private val baselineField: AccuracyBaselineField = AccuracyBaselineField.DEFAULT,
) {

    fun calculateAccuracy(source: String, lat: Double, lon: Double, days: Int = 30): AccuracyPure.AccuracyStatistics? {
        val daily = getDailyAccuracyBreakdown(source, lat, lon, days)
        return AccuracyPure.computeStatistics(daily, WeatherSource.fromId(source).displayName, days)
    }

    fun getDailyAccuracyBreakdown(source: String, lat: Double, lon: Double, days: Int = 30): List<AccuracyPure.DailyAccuracy> {
        val endDate = LocalDate.now().minusDays(1)
        val startDate = endDate.minusDays(days.toLong() - 1)
        val startEpoch = startDate.toEpochDay() * AccuracyBreakdown.MS_IN_A_DAY
        val endEpoch = endDate.toEpochDay() * AccuracyBreakdown.MS_IN_A_DAY

        val gradedSource = WeatherSource.fromId(source)
        val allExtremes = dao.getExtremesInRange(startEpoch, endEpoch, lat, lon)
        val forecasts = dao.getForecastsInRangeBySource(startEpoch, endEpoch, lat, lon, source)
            .map { AccuracyBreakdown.ForecastRow(it.targetDate, it.dateOfPrediction, it.highTemp, it.lowTemp, it.fetchedAt) }

        return AccuracyBreakdown.compute(
            startDate = startDate,
            endDate = endDate,
            allExtremes = allExtremes,
            forecasts = forecasts,
            gradedSource = gradedSource,
            orderedVisibleSources = orderedVisibleSources,
            baselineField = baselineField,
            lat = lat,
            lon = lon,
        ).map { it.toPureDailyAccuracy() }
    }
}
