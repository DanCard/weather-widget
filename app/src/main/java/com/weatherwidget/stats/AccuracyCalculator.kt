package com.weatherwidget.stats

import com.weatherwidget.data.local.DailyExtremeDao
import com.weatherwidget.data.local.ForecastDao
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ObservationResolver
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.roundToInt

@Singleton
class AccuracyCalculator
    @Inject
    constructor(
        private val forecastDao: ForecastDao,
        private val dailyExtremeDao: DailyExtremeDao,
    ) {
        companion object {
            private const val PERFECT_THRESHOLD = 1.0
            private const val EXCELLENT_THRESHOLD = 2.0
            private const val GOOD_THRESHOLD = 3.0
            private const val FAIR_THRESHOLD = 4.0
        }

        suspend fun calculateAccuracy(
            source: WeatherSource,
            lat: Double,
            lon: Double,
            days: Int = 30,
        ): AccuracyStatistics? {
            val dailyAccuracies = getDailyAccuracyBreakdown(source, lat, lon, days)

            if (dailyAccuracies.isEmpty()) {
                return null
            }

            val totalHighError = dailyAccuracies.sumOf { abs(it.highError) }
            val totalLowError = dailyAccuracies.sumOf { abs(it.lowError) }
            val avgHighError = totalHighError.toDouble() / dailyAccuracies.size
            val avgLowError = totalLowError.toDouble() / dailyAccuracies.size
            val avgError = (avgHighError + avgLowError) / 2

            val highBias = dailyAccuracies.sumOf { it.highError }.toDouble() / dailyAccuracies.size
            val lowBias = dailyAccuracies.sumOf { it.lowError }.toDouble() / dailyAccuracies.size

            val maxError = dailyAccuracies.maxOf { maxOf(abs(it.highError), abs(it.lowError)) }

            val within3Degrees = dailyAccuracies.count {
                abs(it.highError) <= 3 && abs(it.lowError) <= 3
            }
            val percentWithin3 = (within3Degrees.toDouble() / dailyAccuracies.size) * 100

            val score = calculateScore(avgError)

            return AccuracyStatistics(
                source = source.displayName,
                avgHighError = avgHighError,
                avgLowError = avgLowError,
                highBias = highBias,
                lowBias = lowBias,
                avgError = avgError,
                maxError = maxError,
                percentWithin3Degrees = percentWithin3,
                accuracyScore = score,
                totalForecasts = dailyAccuracies.size,
                periodDays = days,
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
            val meteoStats = calculateAccuracy(WeatherSource.OPEN_METEO, lat, lon, days)
            val weatherApiStats = calculateAccuracy(WeatherSource.WEATHER_API, lat, lon, days)

            return ComparisonStatistics(
                nwsStats = nwsStats,
                meteoStats = meteoStats,
                weatherApiStats = weatherApiStats,
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

            val startDateStr = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val endDateStr = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

            val extremes = dailyExtremeDao.getExtremesInRange(startDateStr, endDateStr, lat, lon)
                .filter { it.source == source.id }
            val dailyActuals = ObservationResolver.extremesToDailyActuals(extremes)

            val forecasts = forecastDao.getForecastsInRangeBySource(startDateStr, endDateStr, lat, lon, source.id)

            val dailyAccuracies = mutableListOf<DailyAccuracy>()

            for (actual in dailyActuals) {
                val targetDate = LocalDate.parse(actual.date)
                val forecastDate = targetDate.minusDays(1)
                val forecastDateStr = forecastDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

                val forecast = forecasts
                    .filter {
                        it.targetDate == actual.date &&
                                it.forecastDate == forecastDateStr &&
                                it.source == source.id
                    }
                    .maxByOrNull { it.fetchedAt }

                if (forecast != null) {
                    val aHigh = actual.highTemp
                    val aLow = actual.lowTemp
                    val fHigh = forecast.highTemp
                    val fLow = forecast.lowTemp

                    if (fHigh != null && fLow != null) {
                        val roundedActualHigh = aHigh.roundToInt()
                        val roundedActualLow = aLow.roundToInt()
                        val roundedForecastHigh = fHigh.roundToInt()
                        val roundedForecastLow = fLow.roundToInt()
                        val highError = roundedActualHigh - roundedForecastHigh
                        val lowError = roundedActualLow - roundedForecastLow

                        dailyAccuracies.add(
                            DailyAccuracy(
                                date = actual.date,
                                actualHigh = roundedActualHigh,
                                actualLow = roundedActualLow,
                                forecastHigh = roundedForecastHigh,
                                forecastLow = roundedForecastLow,
                                source = source.displayName,
                                highError = highError,
                                lowError = lowError,
                            ),
                        )
                    }
                }
            }

            return dailyAccuracies.sortedBy { it.date }
        }

        private fun calculateScore(avgError: Double): Double {
            return when {
                avgError <= PERFECT_THRESHOLD ->5.0
                avgError <= EXCELLENT_THRESHOLD ->5.0 - ((avgError - PERFECT_THRESHOLD) * 0.5)
                avgError <= GOOD_THRESHOLD ->4.5 - ((avgError - EXCELLENT_THRESHOLD) * 0.5)
                avgError <= FAIR_THRESHOLD ->4.0 - ((avgError - GOOD_THRESHOLD) * 0.5)
                else -> maxOf(0.0, 3.5 - ((avgError - FAIR_THRESHOLD) * 0.5))
            }
        }
    }
