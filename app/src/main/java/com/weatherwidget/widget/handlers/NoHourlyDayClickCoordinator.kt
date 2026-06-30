package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.config.ForecastHorizon
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Pure and DB-backed logic for the two-phase missing-hourly day tap flow: pending message,
 * scoped refresh, then post-refresh result message.
 */
object NoHourlyDayClickCoordinator {

    /** How long the pending message may remain before a slow refresh; replaced by the result message. */
    val PENDING_MESSAGE_MAX_AGE_MS: Long = TimeUnit.MINUTES.toMillis(5)

    fun formatDayLabel(dateStr: String): String =
        try {
            LocalDate.parse(dateStr)
                .format(DateTimeFormatter.ofPattern("EEE MMM d", Locale.getDefault()))
        } catch (_: Exception) {
            dateStr
        }

    fun forecastDaysFor(targetDate: LocalDate, today: LocalDate = LocalDate.now()): Int =
        ForecastHorizon.daysToCover(today, targetDate)

    fun buildPendingMessage(context: Context, dayLabel: String): String =
        context.getString(R.string.widget_no_hourly_pending, dayLabel)

    fun buildResultMessage(
        context: Context,
        dayLabel: String,
        hasHourlyAfterRefresh: Boolean,
        endLabel: String?,
    ): String =
        when {
            hasHourlyAfterRefresh ->
                context.getString(R.string.widget_no_hourly_result_available, dayLabel)
            endLabel != null ->
                context.getString(R.string.widget_no_hourly_result_still_missing, dayLabel, endLabel)
            else ->
                context.getString(R.string.widget_no_hourly_result_still_missing_unknown, dayLabel)
        }

    suspend fun hasHourlyForTappedDay(
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        dateStr: String,
        lat: Double,
        lon: Double,
    ): Boolean {
        val targetDate =
            try {
                LocalDate.parse(dateStr)
            } catch (_: Exception) {
                return false
            }

        val latestWeather = database.forecastDao().getLatestWeather()
        val effectiveLat = if (lat != 0.0) lat else latestWeather?.locationLat ?: return false
        val effectiveLon = if (lon != 0.0) lon else latestWeather?.locationLon ?: return false

        val zoneId = ZoneId.systemDefault()
        val startMs = targetDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val endMs = targetDate.atTime(23, 59).atZone(zoneId).toInstant().toEpochMilli()
        val hourlyForDay = database.hourlyForecastDao().getHourlyForecasts(startMs, endMs, effectiveLat, effectiveLon)

        val hasForecasts =
            if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                hourlyForDay.isNotEmpty()
            } else {
                val displaySource = stateManager.getCurrentDisplaySource(appWidgetId).id
                hourlyForDay.any { it.source == displaySource || it.source == WeatherSource.GENERIC_GAP.id }
            }

        if (hasForecasts) return true

        if (targetDate.isBefore(LocalDate.now())) {
            val observations = database.observationDao().getObservationsInRange(startMs, endMs, effectiveLat, effectiveLon)
            return observations.isNotEmpty()
        }

        return false
    }

    suspend fun lastHourlyEndLabelForSource(
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        lat: Double,
        lon: Double,
    ): String? {
        val latestWeather = database.forecastDao().getLatestWeather()
        val effectiveLat = if (lat != 0.0) lat else latestWeather?.locationLat ?: return null
        val effectiveLon = if (lon != 0.0) lon else latestWeather?.locationLon ?: return null
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null

        val sourceId = stateManager.getCurrentDisplaySource(appWidgetId).id
        val zoneId = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val horizonEnd = now + TimeUnit.DAYS.toMillis(40)
        val rows = database.hourlyForecastDao().getHourlyForecastsBySource(now, horizonEnd, effectiveLat, effectiveLon, sourceId)
        val lastMs = rows.maxOfOrNull { it.dateTime } ?: return null
        return java.time.Instant.ofEpochMilli(lastMs)
            .atZone(zoneId)
            .format(DateTimeFormatter.ofPattern("EEE MMM d 'at' h a", Locale.getDefault()))
    }
}