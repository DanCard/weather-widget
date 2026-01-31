package com.weatherwidget.util

import com.weatherwidget.data.local.HourlyForecastEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * Interpolates temperature between hourly forecast data points.
 *
 * Given a list of hourly forecasts and a target time, calculates the
 * interpolated temperature based on the surrounding data points.
 */
@Singleton
class TemperatureInterpolator @Inject constructor() {

    companion object {
        private val HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

        /**
         * Minimum temperature difference (in degrees) to trigger interpolation.
         * Below this threshold, just use the nearest hour's temperature.
         */
        const val INTERPOLATION_THRESHOLD = 1
    }

    /**
     * Get the interpolated temperature for the given time.
     *
     * @param hourlyForecasts List of hourly forecast data points, should be sorted by dateTime
     * @param targetTime The time to interpolate for
     * @return The interpolated temperature, or null if insufficient data
     */
    fun getInterpolatedTemperature(
        hourlyForecasts: List<HourlyForecastEntity>,
        targetTime: LocalDateTime
    ): Int? {
        if (hourlyForecasts.isEmpty()) return null

        // Find the two surrounding data points
        val targetHour = targetTime.truncatedTo(ChronoUnit.HOURS)
        val nextHour = targetHour.plusHours(1)

        val targetHourStr = targetHour.format(HOUR_FORMATTER)
        val nextHourStr = nextHour.format(HOUR_FORMATTER)

        val currentHourForecast = hourlyForecasts.find { it.dateTime == targetHourStr }
        val nextHourForecast = hourlyForecasts.find { it.dateTime == nextHourStr }

        // If we only have current hour, return that
        if (currentHourForecast != null && nextHourForecast == null) {
            return currentHourForecast.temperature
        }

        // If we only have next hour, return that
        if (currentHourForecast == null && nextHourForecast != null) {
            return nextHourForecast.temperature
        }

        // If we have neither, try to find the closest data point
        if (currentHourForecast == null && nextHourForecast == null) {
            return findClosestTemperature(hourlyForecasts, targetTime)
        }

        // We have both hours - interpolate
        val currentTemp = currentHourForecast!!.temperature
        val nextTemp = nextHourForecast!!.temperature
        val tempDiff = nextTemp - currentTemp

        // If difference is below threshold, just return current hour temp
        if (kotlin.math.abs(tempDiff) < INTERPOLATION_THRESHOLD) {
            return currentTemp
        }

        // Calculate interpolation factor (0.0 to 1.0)
        val minutesIntoHour = targetTime.minute
        val factor = minutesIntoHour / 60.0

        // Linear interpolation
        val interpolatedTemp = currentTemp + (tempDiff * factor)
        return interpolatedTemp.roundToInt()
    }

    /**
     * Find the closest temperature to the target time when we don't have surrounding data points.
     */
    private fun findClosestTemperature(
        hourlyForecasts: List<HourlyForecastEntity>,
        targetTime: LocalDateTime
    ): Int? {
        if (hourlyForecasts.isEmpty()) return null

        val targetMinutes = targetTime.toLocalDate().atStartOfDay()
            .until(targetTime, ChronoUnit.MINUTES)

        return hourlyForecasts.minByOrNull { forecast ->
            val forecastTime = LocalDateTime.parse(forecast.dateTime, HOUR_FORMATTER)
            val forecastMinutes = forecastTime.toLocalDate().atStartOfDay()
                .until(forecastTime, ChronoUnit.MINUTES)
            kotlin.math.abs(targetMinutes - forecastMinutes)
        }?.temperature
    }

    /**
     * Determines if interpolation should occur based on the time elapsed since last update.
     *
     * @param tempDifference The difference between current and next hour temperatures
     * @return The number of updates per hour (1, 2, 3, or 4)
     */
    fun getUpdatesPerHour(tempDifference: Int): Int {
        val absDiff = kotlin.math.abs(tempDifference)
        return when {
            absDiff >= 6 -> 4   // Update every 15 minutes
            absDiff >= 4 -> 3   // Update every 20 minutes
            absDiff >= 2 -> 2   // Update every 30 minutes
            else -> 1           // Update every hour (no interpolation needed)
        }
    }

    /**
     * Gets the next scheduled update time based on temperature difference.
     *
     * @param currentTime The current time
     * @param tempDifference The difference between current and next hour temperatures
     * @return The next time the widget should update its temperature display
     */
    fun getNextUpdateTime(currentTime: LocalDateTime, tempDifference: Int): LocalDateTime {
        val updatesPerHour = getUpdatesPerHour(tempDifference)
        val intervalMinutes = 60 / updatesPerHour

        val currentMinute = currentTime.minute
        val nextUpdateMinute = ((currentMinute / intervalMinutes) + 1) * intervalMinutes

        return if (nextUpdateMinute >= 60) {
            currentTime.truncatedTo(ChronoUnit.HOURS).plusHours(1)
        } else {
            currentTime.truncatedTo(ChronoUnit.HOURS).plusMinutes(nextUpdateMinute.toLong())
        }
    }
}
