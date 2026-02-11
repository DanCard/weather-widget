package com.weatherwidget.util

import android.util.Log
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.model.WeatherSource
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "TemperatureInterpolator"

/**
 * Interpolates temperature between hourly forecast data points.
 *
 * Given a list of hourly forecasts and a target time, calculates the
 * interpolated temperature based on the surrounding data points.
 */
@Singleton
class TemperatureInterpolator
    @Inject
    constructor() {
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
         * @param source Optional source filter. If null, uses all available sources.
         * @return The interpolated temperature, or null if insufficient data
         */
        fun getInterpolatedTemperature(
            hourlyForecasts: List<HourlyForecastEntity>,
            targetTime: LocalDateTime,
            source: WeatherSource? = null,
        ): Float? {
            if (hourlyForecasts.isEmpty()) return null

            // Filter by source if specified, otherwise prefer the specified source with fallback
            val sourcesInData = hourlyForecasts.map { it.source }.distinct()
            Log.d(TAG, "getInterpolatedTemperature: source=$source, sourcesInData=$sourcesInData, totalForecasts=${hourlyForecasts.size}")

            val filteredForecasts =
                if (source != null) {
                    // Group by dateTime and prefer the requested source, fallback to generic gap
                    hourlyForecasts.groupBy { it.dateTime }
                        .mapValues { entry ->
                            entry.value.find { it.source == source.id }
                                ?: entry.value.find { it.source == WeatherSource.GENERIC_GAP.id }
                                ?: entry.value.firstOrNull()
                        }
                        .values.filterNotNull()
                } else {
                    hourlyForecasts
                }
            Log.d(
                TAG,
                "getInterpolatedTemperature: filteredForecasts=${filteredForecasts.size}, sources=${filteredForecasts.map { it.source }.distinct()}",
            )

            // Find the two surrounding data points
            val targetHour = targetTime.truncatedTo(ChronoUnit.HOURS)
            val nextHour = targetHour.plusHours(1)

            val targetHourStr = targetHour.format(HOUR_FORMATTER)
            val nextHourStr = nextHour.format(HOUR_FORMATTER)

            val currentHourForecast = filteredForecasts.find { it.dateTime == targetHourStr }
            val nextHourForecast = filteredForecasts.find { it.dateTime == nextHourStr }

            Log.d(
                TAG,
                "getInterpolatedTemperature: currentHour=$targetHourStr found=${currentHourForecast?.source}:${currentHourForecast?.temperature}, nextHour=$nextHourStr found=${nextHourForecast?.source}:${nextHourForecast?.temperature}",
            )

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
                return findClosestTemperature(filteredForecasts, targetTime)
            }

            // We have both hours - interpolate
            val currentTemp = currentHourForecast!!.temperature
            val nextTemp = nextHourForecast!!.temperature
            val tempDiff = nextTemp - currentTemp

            // If difference is below threshold, just return current hour temp
            if (kotlin.math.abs(tempDiff) < INTERPOLATION_THRESHOLD) {
                Log.d(TAG, "Below threshold, returning currentTemp=$currentTemp")
                return currentTemp
            }

            // Calculate interpolation factor (0.0 to 1.0)
            val minutesIntoHour = targetTime.minute
            val factor = minutesIntoHour / 60.0f

            // Linear interpolation
            val interpolatedTemp = currentTemp + (tempDiff * factor)
            Log.d(
                TAG,
                "Interpolating: time=${targetTime.hour}:${targetTime.minute}, " +
                    "current=$currentTemp@$targetHourStr, next=$nextTemp@$nextHourStr, " +
                    "factor=$factor, result=$interpolatedTemp",
            )
            return interpolatedTemp
        }

        /**
         * Find the closest temperature to the target time when we don't have surrounding data points.
         */
        private fun findClosestTemperature(
            hourlyForecasts: List<HourlyForecastEntity>,
            targetTime: LocalDateTime,
        ): Float? {
            if (hourlyForecasts.isEmpty()) return null

            val targetMinutes =
                targetTime.toLocalDate().atStartOfDay()
                    .until(targetTime, ChronoUnit.MINUTES)

            return hourlyForecasts.minByOrNull { forecast ->
                val forecastTime = LocalDateTime.parse(forecast.dateTime, HOUR_FORMATTER)
                val forecastMinutes =
                    forecastTime.toLocalDate().atStartOfDay()
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
                absDiff >= 6 -> 4 // Update every 15 minutes
                absDiff >= 4 -> 3 // Update every 20 minutes
                absDiff >= 2 -> 2 // Update every 30 minutes
                else -> 1 // Update every hour (no interpolation needed)
            }
        }

        /**
         * Gets the next scheduled update time based on temperature difference.
         *
         * @param currentTime The current time
         * @param tempDifference The difference between current and next hour temperatures
         * @return The next time the widget should update its temperature display
         */
        fun getNextUpdateTime(
            currentTime: LocalDateTime,
            tempDifference: Int,
        ): LocalDateTime {
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
