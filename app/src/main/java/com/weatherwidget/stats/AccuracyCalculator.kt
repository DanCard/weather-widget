package com.weatherwidget.stats

import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.AccuracyBreakdown
import com.weatherwidget.shared.stats.AccuracyPure
import com.weatherwidget.shared.stats.ComparisonStatistics
import com.weatherwidget.shared.util.WeatherSourceOrdering
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccuracyCalculator
    @Inject
    constructor(
        private val forecastDao: ForecastDao,
        private val dailyHistoryDao: DailyHistoryDao,
        private val widgetStateManager: WidgetStateManager,
        private val accuracyPreferences: AccuracyPreferences,
    ) {
        suspend fun calculateAccuracy(
            source: WeatherSource,
            lat: Double,
            lon: Double,
            days: Int = 30,
        ): AccuracyPure.AccuracyStatistics? {
            return AccuracyPure.computeStatistics(
                getDailyAccuracyBreakdown(source, lat, lon, days).map { it.toPureDailyAccuracy() },
                source.displayName,
                days,
            )
        }

        suspend fun calculateComparison(
            lat: Double,
            lon: Double,
            days: Int = 30,
        ): ComparisonStatistics {
            val endDate = LocalDate.now().minusDays(1)
            val startDate = endDate.minusDays(days.toLong() - 1)

            return ComparisonStatistics(
                bySource = WeatherSourceOrdering.ALL_CONFIGURABLE.associateWith { source ->
                    calculateAccuracy(source, lat, lon, days)
                },
                periodStart = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                periodEnd = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
        }

        suspend fun getDailyAccuracyBreakdown(
            source: WeatherSource,
            lat: Double,
            lon: Double,
            days: Int = 30,
        ): List<AccuracyBreakdown.DailyResult> {
            val endDate = LocalDate.now().minusDays(1)
            val startDate = endDate.minusDays(days.toLong() - 1)

            val startEpoch = startDate.toEpochDay() * AccuracyBreakdown.MS_IN_A_DAY
            val endEpoch = endDate.toEpochDay() * AccuracyBreakdown.MS_IN_A_DAY

            // Every source's rows, not just the graded one: a source with no past-weather product
            // of its own borrows another's actual rather than being scored against its own
            // forecast re-filed as an observation. See ActualsBaselineResolver.
            val allExtremes = dailyHistoryDao.getExtremesInRange(startEpoch, endEpoch, lat, lon)
                .map { it.toDailyHistory() }
            val forecasts = forecastDao.getForecastsInRangeBySource(startEpoch, endEpoch, lat, lon, source.id)
                .map { AccuracyBreakdown.ForecastRow(it.targetDate, it.dateOfPrediction, it.highTemp, it.lowTemp, it.fetchedAt) }

            return AccuracyBreakdown.compute(
                startDate = startDate,
                endDate = endDate,
                allExtremes = allExtremes,
                forecasts = forecasts,
                gradedSource = source,
                orderedVisibleSources = widgetStateManager.getVisibleSourcesOrder(),
                baselineField = accuracyPreferences.baselineField(),
                lat = lat,
                lon = lon,
            )
        }
    }
