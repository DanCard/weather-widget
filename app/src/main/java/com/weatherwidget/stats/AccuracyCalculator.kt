package com.weatherwidget.stats

import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.AccuracyBaselineField
import com.weatherwidget.shared.stats.AccuracyPure
import com.weatherwidget.shared.stats.ActualsBaselineResolver
import com.weatherwidget.shared.stats.resolveBaselineTemps
import com.weatherwidget.util.TempUtils
import com.weatherwidget.widget.WidgetConstants
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

            val startEpoch = startDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY
            val endEpoch = endDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY

            // Every source's rows, not just the graded one: a source with no past-weather product
            // of its own borrows another's actual rather than being scored against its own
            // forecast re-filed as an observation. See ActualsBaselineResolver.
            val allExtremes = dailyHistoryDao.getExtremesInRange(startEpoch, endEpoch, lat, lon)
            val orderedSources = widgetStateManager.getVisibleSourcesOrder()
            val baselineField = accuracyPreferences.baselineField()

            val forecasts = forecastDao.getForecastsInRangeBySource(startEpoch, endEpoch, lat, lon, source.id)

            val dailyAccuracies = mutableListOf<DailyAccuracy>()
            var current = startDate
            while (!current.isAfter(endDate)) {
                val targetDate = current
                current = current.plusDays(1)

                val targetEpochVal = targetDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY
                val forecastEpoch = targetDate.minusDays(1).toEpochDay() * WidgetConstants.MS_IN_A_DAY

                val forecast = forecasts
                    .filter { it.targetDate == targetEpochVal && it.dateOfPrediction == forecastEpoch }
                    .maxByOrNull { it.fetchedAt }
                    ?: continue

                val rowsForDate = allExtremes.filter { it.date == targetEpochVal }
                val baselineSource = ActualsBaselineResolver.resolveBaselineSource(
                    gradedSource = source,
                    orderedVisibleSources = orderedSources,
                    // Forecast-only rows (null computed*) carry no observations — they must never
                    // serve as an accuracy baseline.
                    hasRowForDate = { candidate ->
                        rowsForDate.any { it.source == candidate.id && it.computedHighTemp != null && it.computedLowTemp != null }
                    },
                ) ?: continue // no trustworthy actual for this day — exclude it rather than guess

                val baselineRow = rowsForDate
                    .filter { it.source == baselineSource.id }
                    .minByOrNull { TempUtils.distanceSq(it.locationLat, it.locationLon, lat, lon) }
                    ?: continue
                val baselineHigh = baselineRow.computedHighTemp ?: continue
                val baselineLow = baselineRow.computedLowTemp ?: continue

                val temps = resolveBaselineTemps(
                    field = baselineField,
                    computedHigh = baselineHigh,
                    computedLow = baselineLow,
                    apiHigh = baselineRow.apiHighTemp,
                    apiLow = baselineRow.apiLowTemp,
                )

                val entry = AccuracyPure.buildDailyAccuracy(
                    date = targetDate.toString(),
                    computedHighTemp = temps.high,
                    computedLowTemp = temps.low,
                    forecastHigh = forecast.highTemp,
                    forecastLow = forecast.lowTemp,
                    source = source.displayName,
                ) ?: continue

                dailyAccuracies.add(
                    DailyAccuracy(
                        date = entry.date,
                        computedHighTemp = entry.computedHighTemp,
                        computedLowTemp = entry.computedLowTemp,
                        forecastHigh = entry.forecastHigh,
                        forecastLow = entry.forecastLow,
                        source = entry.source,
                        highError = entry.highError,
                        lowError = entry.lowError,
                        baselineSourceId = baselineSource.id.takeIf { it != source.id },
                        baselineStationId = baselineRow.apiStationId
                            ?.takeIf { baselineField == AccuracyBaselineField.NATIVE_ACTUAL && !temps.fellBackToBlend },
                        baselineFellBackToBlend = temps.fellBackToBlend,
                    ),
                )
            }

            return dailyAccuracies.sortedBy { it.date }
        }
    }
