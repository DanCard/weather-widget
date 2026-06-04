package com.weatherwidget.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.util.NavigationUtils
import com.weatherwidget.shared.util.TemperatureInterpolator
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Manages opportunistic UI updates using AlarmManager.
 *
 * Schedules current temperature updates based on temperature change rate
 * without forcing device wakeups. Updates piggyback on other system activity.
 */
class UIUpdateScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule the next UI-only update based on current temperature change rate.
     * Uses setAndAllowWhileIdle() for opportunistic updates without guaranteed wakeup.
     */
    suspend fun scheduleNextUpdate() {
        try {
            val database = WeatherDatabase.getDatabase(context)
            val hourlyDao = database.hourlyForecastDao()
            val weatherDao = database.forecastDao()

            // Get location from latest weather data
            val latestWeather = weatherDao.getLatestWeather()
            val lat = latestWeather?.locationLat ?: WeatherWidgetWorker.DEFAULT_LAT
            val lon = latestWeather?.locationLon ?: WeatherWidgetWorker.DEFAULT_LON

            // Get hourly forecasts around current time
            val now = LocalDateTime.now()
            val zoneId = ZoneId.systemDefault()
            val startTimeMs = now.minusHours(1).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val endTimeMs = now.plusHours(2).truncatedTo(java.time.temporal.ChronoUnit.HOURS).atZone(zoneId).toInstant().toEpochMilli()
            val hourlyForecasts = hourlyDao.getHourlyForecasts(startTimeMs, endTimeMs, lat, lon)

            if (hourlyForecasts.isEmpty()) {
                Log.d(TAG, "No hourly forecasts available, scheduling default 30 min update")
                scheduleUpdate(30 * 60 * 1000L) // 30 minutes
                return
            }

            // Find current and next hour forecasts to determine temp change rate
            val currentHourMs = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                .atZone(zoneId).toInstant().toEpochMilli()
            val nextHourMs = now.truncatedTo(java.time.temporal.ChronoUnit.HOURS).plusHours(1)
                .atZone(zoneId).toInstant().toEpochMilli()

            val currentTemp = hourlyForecasts.find { it.dateTime == currentHourMs }?.temperature
            val nextTemp = hourlyForecasts.find { it.dateTime == nextHourMs }?.temperature

            val tempDifference =
                if (currentTemp != null && nextTemp != null) {
                    Math.abs(nextTemp - currentTemp).toInt()
                } else {
                    2 // Default to moderate change rate
                }

            // Calculate next update time based on temperature change rate
            val nextUpdateEpochMs = TemperatureInterpolator.getNextUpdateTime(
                Instant.now().toEpochMilli(),
                tempDifference
            )
            val nextUpdateTime = Instant.ofEpochMilli(nextUpdateEpochMs).atZone(ZoneId.systemDefault()).toLocalDateTime()

            // If plugged in (and screen is on, handled by receiver), update very frequently
            val batteryStatus: Intent? = context.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val isCharging = BatteryStatePolicy.isEffectivelyCharging(batteryStatus)

            // Use strategy to compute actual delay
            val delayMillis = UIUpdateIntervalStrategy.computeDelayMillis(
                nextUpdateTimeMillis = nextUpdateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                nowMillis = now.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
                isCharging = isCharging,
                timeUntilSkipYesterdayMillis = getTimeUntilSkipYesterday()
            )

            Log.d(
                TAG,
                "Scheduling next UI update in ${delayMillis / 1000 / 60} minutes " +
                    "(tempDiff=$tempDifference, nextUpdate=$nextUpdateTime)",
            )

            scheduleUpdate(delayMillis)
        } catch (e: Exception) {
            Log.e(TAG, "Error scheduling next update", e)
            // Fallback to 30 minute update
            scheduleUpdate(30 * 60 * 1000L)
        }
    }

    /**
     * Schedule a UI update after the specified delay.
     */
    private fun scheduleUpdate(delayMillis: Long) {
        val intent = Intent(context, UIUpdateReceiver::class.java)
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                UI_UPDATE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val triggerAtMillis = System.currentTimeMillis() + delayMillis

        // Use setAndAllowWhileIdle for opportunistic updates (no guaranteed wakeup)
        // This will fire when the device is already awake from other activity
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC,
                triggerAtMillis,
                pendingIntent,
            )
        } else {
            alarmManager.set(
                AlarmManager.RTC,
                triggerAtMillis,
                pendingIntent,
            )
        }

        Log.d(TAG, "UI update alarm scheduled for ${delayMillis / 1000} seconds from now")
    }

    /**
     * Cancel any pending UI updates.
     */
    fun cancelScheduledUpdates() {
        val intent = Intent(context, UIUpdateReceiver::class.java)
        val pendingIntent =
            PendingIntent.getBroadcast(
                context,
                UI_UPDATE_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )

        pendingIntent?.let {
            alarmManager.cancel(it)
            it.cancel()
            Log.d(TAG, "Canceled scheduled UI updates")
        }
    }

    /**
     * Calculates milliseconds until the narrow-widget skip-yesterday threshold.
     * If the threshold has already passed today, returns the duration until tomorrow's threshold.
     */
    private fun getTimeUntilSkipYesterday(): Long {
        val now = LocalDateTime.now()
        val threshold = NavigationUtils.NARROW_SKIP_YESTERDAY_HOUR

        val targetToday = now.withHour(threshold).withMinute(0).withSecond(0).withNano(0)
        val target = if (now.hour >= threshold) targetToday.plusDays(1) else targetToday
        return java.time.Duration.between(now, target).toMillis()
    }

    companion object {
        private const val TAG = "UIUpdateScheduler"
        private const val UI_UPDATE_REQUEST_CODE = 1001
    }
}
