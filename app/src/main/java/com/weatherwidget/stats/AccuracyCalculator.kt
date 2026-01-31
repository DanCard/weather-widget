package com.weatherwidget.stats

import com.weatherwidget.data.local.ForecastSnapshotDao
import com.weatherwidget.data.local.WeatherDao
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class AccuracyCalculator @Inject constructor(
    private val weatherDao: WeatherDao,
    private val forecastSnapshotDao: ForecastSnapshotDao
) {

    companion object {
        private const val PERFECT_THRESHOLD = 1.0
        private const val EXCELLENT_THRESHOLD = 2.0
        private const val GOOD_THRESHOLD = 3.0
        private const val FAIR_THRESHOLD = 4.0
    }

    /**
     * Calculate accuracy statistics for a single API source over a time period.
     */
    suspend fun calculateAccuracy(
        source: String,
        lat: Double,
        lon: Double,
        days: Int = 30
    ): AccuracyStatistics? {
        val endDate = LocalDate.now().minusDays(1) // Yesterday is most recent actual data
        val startDate = endDate.minusDays(days.toLong() - 1)

        val dailyAccuracies = getDailyAccuracyBreakdown(source, lat, lon, days)

        if (dailyAccuracies.isEmpty()) {
            return null
        }

        // Calculate average absolute errors separately for high and low
        val totalHighError = dailyAccuracies.sumOf { abs(it.highError) }
        val totalLowError = dailyAccuracies.sumOf { abs(it.lowError) }
        val avgHighError = totalHighError.toDouble() / dailyAccuracies.size
        val avgLowError = totalLowError.toDouble() / dailyAccuracies.size
        val avgError = (avgHighError + avgLowError) / 2

        // Calculate directional bias (positive = forecasts run low, negative = forecasts run high)
        val highBias = dailyAccuracies.sumOf { it.highError }.toDouble() / dailyAccuracies.size
        val lowBias = dailyAccuracies.sumOf { it.lowError }.toDouble() / dailyAccuracies.size

        // Find maximum error
        val maxError = dailyAccuracies.maxOf { maxOf(abs(it.highError), abs(it.lowError)) }

        // Calculate percentage within 3 degrees
        val within3Degrees = dailyAccuracies.count {
            abs(it.highError) <= 3 && abs(it.lowError) <= 3
        }
        val percentWithin3 = (within3Degrees.toDouble() / dailyAccuracies.size) * 100

        // Calculate accuracy score (0-5 scale, 5 = perfect)
        val score = calculateScore(avgError)

        return AccuracyStatistics(
            source = source,
            avgHighError = avgHighError,
            avgLowError = avgLowError,
            highBias = highBias,
            lowBias = lowBias,
            avgError = avgError,
            maxError = maxError,
            percentWithin3Degrees = percentWithin3,
            accuracyScore = score,
            totalForecasts = dailyAccuracies.size,
            periodDays = days
        )
    }

    /**
     * Get comparison statistics for both NWS and Open-Meteo.
     */
    suspend fun calculateComparison(
        lat: Double,
        lon: Double,
        days: Int = 30
    ): ComparisonStatistics {
        val endDate = LocalDate.now().minusDays(1)
        val startDate = endDate.minusDays(days.toLong() - 1)

        val nwsStats = calculateAccuracy("NWS", lat, lon, days)
        val meteoStats = calculateAccuracy("Open-Meteo", lat, lon, days)

        return ComparisonStatistics(
            nwsStats = nwsStats,
            meteoStats = meteoStats,
            periodStart = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
            periodEnd = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        )
    }

    /**
     * Get day-by-day accuracy breakdown for a single source.
     */
    suspend fun getDailyAccuracyBreakdown(
        source: String,
        lat: Double,
        lon: Double,
        days: Int = 30
    ): List<DailyAccuracy> {
        val endDate = LocalDate.now().minusDays(1) // Yesterday is most recent actual data
        val startDate = endDate.minusDays(days.toLong() - 1)

        val startDateStr = startDate.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val endDateStr = endDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Get actual weather data for the period
        val actualWeather = weatherDao.getWeatherRange(startDateStr, endDateStr, lat, lon)
            .filter { it.isActual }

        // Get forecast snapshots for the period
        val forecasts = forecastSnapshotDao.getForecastsInRange(startDateStr, endDateStr, lat, lon)

        val dailyAccuracies = mutableListOf<DailyAccuracy>()

        // For each actual weather day, find the corresponding 1-day-ahead forecast
        for (actual in actualWeather) {
            val targetDate = LocalDate.parse(actual.date)
            val forecastDate = targetDate.minusDays(1) // 1-day-ahead forecast
            val forecastDateStr = forecastDate.format(DateTimeFormatter.ISO_LOCAL_DATE)

            // Find the forecast made the day before for this target date
            val forecast = forecasts.find {
                it.targetDate == actual.date &&
                it.forecastDate == forecastDateStr &&
                it.source == mapSourceName(source)
            }

            if (forecast != null) {
                val highError = actual.highTemp - forecast.highTemp
                val lowError = actual.lowTemp - forecast.lowTemp

                dailyAccuracies.add(
                    DailyAccuracy(
                        date = actual.date,
                        actualHigh = actual.highTemp,
                        actualLow = actual.lowTemp,
                        forecastHigh = forecast.highTemp,
                        forecastLow = forecast.lowTemp,
                        source = source,
                        highError = highError,
                        lowError = lowError
                    )
                )
            }
        }

        return dailyAccuracies.sortedBy { it.date }
    }

    /**
     * Calculate accuracy score from average error.
     * Score range: 0-5.0, where 5.0 is perfect.
     */
    private fun calculateScore(avgError: Double): Double {
        return when {
            avgError <= PERFECT_THRESHOLD -> 5.0
            avgError <= EXCELLENT_THRESHOLD -> 5.0 - ((avgError - PERFECT_THRESHOLD) * 0.5)
            avgError <= GOOD_THRESHOLD -> 4.5 - ((avgError - EXCELLENT_THRESHOLD) * 0.5)
            avgError <= FAIR_THRESHOLD -> 4.0 - ((avgError - GOOD_THRESHOLD) * 0.5)
            else -> maxOf(0.0, 3.5 - ((avgError - FAIR_THRESHOLD) * 0.5))
        }
    }

    /**
     * Map display source name to database source name.
     */
    private fun mapSourceName(source: String): String {
        return when (source) {
            "NWS" -> "NWS"
            "Open-Meteo" -> "OPEN_METEO"
            else -> source
        }
    }
}
