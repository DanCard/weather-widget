package com.weatherwidget.stats

import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.local.toDailyHistory
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.AccuracyBreakdown
import com.weatherwidget.shared.stats.AccuracyPure
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
        ): AccuracyStatistics? {
            val dailyAccuracies = getDailyAccuracyBreakdown(source, lat, lon, days)
            val pureDailies = dailyAccuracies.map {
                AccuracyPure.DailyAccuracy(it.date, it.computedHighTemp, it.computedLowTemp, it.forecastHigh, it.forecastLow, it.source, it.highError, it.lowError)
            }
            val stats = AccuracyPure.computeStatistics(pureDailies, source.displayName, days) ?: return null
            return AccuracyStatistics(
                source = stats.source,
                avgHighError = stats.avgHighError,
                avgLowError = stats.avgLowError,
                highBias = stats.highBias,
                lowBias = stats.lowBias,
                avgError = stats.avgError,
                maxError = stats.maxError,
                percentWithin3Degrees = stats.percentWithin3Degrees,
                accuracyScore = stats.accuracyScore,
                totalForecasts = stats.totalForecasts,
                periodDays = stats.periodDays,
            )
        }

        suspend fun calculateComparison(
            lat: Double,
            lon: Double,
            days: Int = 30,
        ): ComparisonStatistics {
            val endDate = LocalDate.now().minusDays(1)
            val startDate = endDate.minusDays(days.toLong() - 1)

            val nwsStats = calculateAccuracy(WeatherSource.NWS, lat, lon, days)
            val visualCrossingStats = calculateAccuracy(WeatherSource.VISUAL_CROSSING, lat, lon, days)
            val openWeatherMapStats = calculateAccuracy(WeatherSource.OPEN_WEATHER_MAP, lat, lon, days)
            val meteoStats = calculateAccuracy(WeatherSource.OPEN_METEO, lat, lon, days)
            val weatherApiStats = calculateAccuracy(WeatherSource.WEATHER_API, lat, lon, days)
            val tomorrowIoStats = calculateAccuracy(WeatherSource.TOMORROW_IO, lat, lon, days)
            val silurianStats = calculateAccuracy(WeatherSource.SILURIAN, lat, lon, days)

            return ComparisonStatistics(
                nwsStats = nwsStats,
                visualCrossingStats = visualCrossingStats,
                openWeatherMapStats = openWeatherMapStats,
                meteoStats = meteoStats,
                weatherApiStats = weatherApiStats,
                tomorrowIoStats = tomorrowIoStats,
                silurianStats = silurianStats,
                periodStart = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                periodEnd = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            )
        }

        suspend fun getDailyAccuracyBreakdown(
            source: WeatherSource,
            lat: Double,
            lon: Double,
            days: Int = 30,
        ): List<DailyAccuracy> {
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
            ).map { entry ->
                DailyAccuracy(
                    date = entry.date,
                    computedHighTemp = entry.computedHighTemp,
                    computedLowTemp = entry.computedLowTemp,
                    forecastHigh = entry.forecastHigh,
                    forecastLow = entry.forecastLow,
                    source = entry.source,
                    highError = entry.highError,
                    lowError = entry.lowError,
                    baselineSourceId = entry.baselineSourceId,
                    baselineStationId = entry.baselineStationId,
                    baselineFellBackToBlend = entry.baselineFellBackToBlend,
                )
            }
        }
    }
