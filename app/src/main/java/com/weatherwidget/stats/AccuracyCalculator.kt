package com.weatherwidget.stats

import com.weatherwidget.data.local.DailyHistoryDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.stats.AccuracyPure
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.WidgetConstants
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
    ) {
        suspend fun calculateAccuracy(
            source: WeatherSource,
            lat: Double,
            lon: Double,
            days: Int = 30,
        ): AccuracyStatistics? {
            val dailyAccuracies = getDailyAccuracyBreakdown(source, lat, lon, days)
            val pureDailies = dailyAccuracies.map {
                AccuracyPure.DailyAccuracy(it.date, it.actualHigh, it.actualLow, it.forecastHigh, it.forecastLow, it.source, it.highError, it.lowError)
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

            val extremes = dailyHistoryDao.getExtremesInRange(startEpoch, endEpoch, lat, lon)
                .filter { it.source == source.id }
            val dailyActuals = ObservationResolver.extremesToDailyActuals(extremes)

            val forecasts = forecastDao.getForecastsInRangeBySource(startEpoch, endEpoch, lat, lon, source.id)

            val dailyAccuracies = mutableListOf<DailyAccuracy>()
            for (actual in dailyActuals) {
                val targetDate = actual.toLocalDate()
                val forecastDate = targetDate.minusDays(1)
                val targetEpochVal = targetDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY
                val forecastEpoch = forecastDate.toEpochDay() * WidgetConstants.MS_IN_A_DAY

                val forecast = forecasts
                    .filter {
                        it.targetDate == targetEpochVal &&
                                it.forecastDate == forecastEpoch &&
                                it.source == source.id
                    }
                    .maxByOrNull { it.fetchedAt }

                if (forecast != null) {
                    val entry = AccuracyPure.buildDailyAccuracy(
                        date = targetDate.toString(),
                        actualHigh = actual.highTemp,
                        actualLow = actual.lowTemp,
                        forecastHigh = forecast.highTemp,
                        forecastLow = forecast.lowTemp,
                        source = source.displayName,
                    )
                    if (entry != null) {
                        dailyAccuracies.add(
                            DailyAccuracy(
                                date = entry.date,
                                actualHigh = entry.actualHigh,
                                actualLow = entry.actualLow,
                                forecastHigh = entry.forecastHigh,
                                forecastLow = entry.forecastLow,
                                source = entry.source,
                                highError = entry.highError,
                                lowError = entry.lowError,
                            ),
                        )
                    }
                }
            }

            return dailyAccuracies.sortedBy { it.date }
        }
    }
