package com.weatherwidget.util

import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.HourlyForecastEntity
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.Duration
import java.time.ZoneId
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
    constructor(
        private val appLogDao: AppLogDao? = null,
    ) {
        companion object {
            private val HOUR_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")
            @Volatile
            private var defaultAppLogDao: AppLogDao? = null

            /**
             * Minimum temperature difference (in degrees) to trigger interpolation.
             * Below this threshold, just use the nearest hour's temperature.
             */
            const val INTERPOLATION_THRESHOLD = 1

            fun setDefaultAppLogDao(appLogDao: AppLogDao?) {
                defaultAppLogDao = appLogDao
            }
        }

        private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun debugLog(message: String) {
            Log.d(TAG, message)
            val dao = appLogDao ?: defaultAppLogDao ?: return
            logScope.launch {
                dao.log(TAG, message)
            }
        }

        /**
         * Get the interpolated temperature for the given time.
         *
         * This routine turns hourly forecast rows into a minute-level current-temperature estimate.
         *
         * High-level behavior:
         * 1. If no hourly rows exist, return null.
         * 2. If a source is requested, collapse rows by hour and prefer:
         *    a. the requested source for that hour,
         *    b. otherwise Generic Gap fallback for that hour,
         *    c. otherwise the first row for that hour.
         * 3. Snap the target time down to the current hour and compute the next hour.
         * 4. Look up rows for exactly those 2 hour buckets.
         * 5. If only one bucket exists, return that bucket's temperature directly.
         * 6. If neither bucket exists, return the closest hourly row in absolute time.
         * 7. If both buckets exist:
         *    a. compute the temperature difference,
         *    b. if the difference is below INTERPOLATION_THRESHOLD, return the current-hour temperature,
         *    c. otherwise linearly interpolate using targetTime.minute / 60.
         *
         * Pseudocode:
         * ```
         * if hourlyForecasts is empty:
         *     return null
         *
         * filtered = hourlyForecasts
         * if source != null:
         *     for each hour bucket:
         *         choose requested-source row
         *         else choose generic-gap row
         *         else choose first row
         *
         * targetHour = truncate targetTime to hour
         * nextHour = targetHour + 1 hour
         *
         * current = row at targetHour
         * next = row at nextHour
         *
         * if current exists and next missing:
         *     return current.temperature
         * if current missing and next exists:
         *     return next.temperature
         * if both missing:
         *     return closest row by absolute time distance
         *
         * diff = next.temperature - current.temperature
         * if abs(diff) < INTERPOLATION_THRESHOLD:
         *     return current.temperature
         *
         * factor = targetTime.minute / 60.0
         * return current.temperature + diff * factor
         * ```
         *
         * Notes:
         * - `targetTime` is truncated to the hour only for bucket lookup.
         *   The original minute value is still used as the interpolation factor.
         * - The interpolation is linear, not spline/smoothed interpolation.
         * - When hourly coverage is incomplete, the function prefers a sensible direct fallback
         *   instead of inventing a value from non-adjacent hours.
         *
         * @param hourlyForecasts Hourly forecast data points keyed by hourly timestamps.
         * @param targetTime The time to estimate.
         * @param source Optional source preference; when provided, rows are reduced per hour using
         * source-first then generic-gap fallback selection.
         * @return Interpolated or fallback temperature, or null if no hourly data exists.
         */
        fun getInterpolatedTemperature(
            hourlyForecasts: List<HourlyForecastEntity>,
            targetTime: LocalDateTime,
            source: WeatherSource? = null,
        ): Float? {
            if (hourlyForecasts.isEmpty()) return null

            // Filter by source if specified, otherwise prefer the specified source with fallback
            val sourcesInData = hourlyForecasts.map { it.source }.distinct()
            debugLog("getInterpolatedTemperature: source=$source, sourcesInData=$sourcesInData, totalForecasts=${hourlyForecasts.size}")

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
            debugLog(
                "getInterpolatedTemperature: filteredForecasts=${filteredForecasts.size}, sources=${filteredForecasts.map { it.source }.distinct()}",
            )

            /*
            Snaps targetTime down to the start of the current hour so the interpolator can look up the two hourly forecast
            buckets that surround “now”.

            At app/src/main/java/com/weatherwidget/util/TemperatureInterpolator.kt:96, if targetTime is 2026-03-18T10:37,
            truncatedTo(ChronoUnit.HOURS) makes it 2026-03-18T10:00. Then the code uses:

            - targetHour = 10:00
            - nextHour = 11:00

            and looks for forecast rows keyed exactly as "yyyy-MM-dd'T'HH:00" for those two hours.

            That matters because the hourly forecast table stores one value per whole hour, not per minute. Without truncation,
            the lookup would try to match 10:37, which does not exist in the hourly data. The interpolation factor is then taken
            from the minute component separately:

            - minutesIntoHour = 37
            - factor = 37 / 60.0

            So the logic is:

            1. Find the bounding hourly points at 10:00 and 11:00.
            2. Use the original minutes 37 to interpolate between them.

            */
            // Find the two surrounding data points
            val zoneId = ZoneId.systemDefault()
            val targetHour = targetTime.truncatedTo(ChronoUnit.HOURS)
            val nextHour = targetHour.plusHours(1)

            val targetHourMs = targetHour.atZone(zoneId).toInstant().toEpochMilli()
            val nextHourMs = nextHour.atZone(zoneId).toInstant().toEpochMilli()

            val currentHourForecast = filteredForecasts.find { it.dateTime == targetHourMs }
            val nextHourForecast = filteredForecasts.find { it.dateTime == nextHourMs }

            debugLog(
                "getInterpolatedTemperature: targetHourMs=$targetHourMs found=${currentHourForecast?.source}:${currentHourForecast?.temperature}, nextHourMs=$nextHourMs found=${nextHourForecast?.source}:${nextHourForecast?.temperature}",
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
                debugLog("Below threshold, returning currentTemp=$currentTemp")
                return currentTemp
            }

            // Calculate interpolation factor (0.0 to 1.0)
            val minutesIntoHour = targetTime.minute
            val factor = minutesIntoHour / 60.0f

            // Linear interpolation
            val interpolatedTemp = currentTemp + (tempDiff * factor)
            debugLog(
                "Interpolating: time=${targetTime.hour}:${targetTime.minute}, " +
                    "current=$currentTemp@$targetHourMs, next=$nextTemp@$nextHourMs, " +
                    "factor=$factor, result=$interpolatedTemp",
            )
            return interpolatedTemp
        }

        private fun findClosestTemperature(
            hourlyForecasts: List<HourlyForecastEntity>,
            targetTime: LocalDateTime,
        ): Float? {
            if (hourlyForecasts.isEmpty()) return null
            val zoneId = ZoneId.systemDefault()

            return hourlyForecasts.minByOrNull { forecast ->
                val forecastTime = Instant.ofEpochMilli(forecast.dateTime).atZone(zoneId).toLocalDateTime()
                kotlin.math.abs(Duration.between(targetTime, forecastTime).toMinutes())
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
