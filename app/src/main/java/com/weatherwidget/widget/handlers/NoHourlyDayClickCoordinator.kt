package com.weatherwidget.widget.handlers

import android.appwidget.AppWidgetManager
import android.content.Context
import com.weatherwidget.R
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.toHourlyForecast
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.HourlyOnDemand
import com.weatherwidget.shared.util.NoHourlyChecker
import com.weatherwidget.widget.WidgetStateManager
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Pure and DB-backed logic for the two-phase missing-hourly day tap flow: the tapped day's hourly
 * view (empty) under a "Fetching…" banner, a scoped refresh, then the banner cleared or replaced by
 * the result.
 */
object NoHourlyDayClickCoordinator {

    /** How long the pending message may remain before a slow refresh; replaced by the result message. */
    val PENDING_MESSAGE_MAX_AGE_MS: Long = TimeUnit.MINUTES.toMillis(5)

    fun formatDayLabel(dateStr: String): String =
        try {
            NoHourlyChecker.formatDayLabel(LocalDate.parse(dateStr))
        } catch (_: Exception) {
            dateStr
        }

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
            // Unscoped on purpose: the question here is "does ANY observation exist for this day",
            // which is what decides whether the day-tap shows a banner. Narrowing it would answer a
            // different question.
            val observations = database.observationDao()
                .getObservationsInRange(startMs, endMs, effectiveLat, effectiveLon, apis = null)
            return observations.isNotEmpty()
        }

        return false
    }

    /**
     * The `forecast/hours` horizon that would cover [dateStr] for this widget's display source
     * ([HourlyOnDemand]: Google past its routine 72 h), or null when nothing needs fetching.
     */
    suspend fun onDemandHours(
        database: WeatherDatabase,
        stateManager: WidgetStateManager,
        appWidgetId: Int,
        dateStr: String,
        lat: Double,
        lon: Double,
        nowMs: Long = System.currentTimeMillis(),
    ): Int? {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return null
        val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return null
        val sourceId = stateManager.getCurrentDisplaySource(appWidgetId).id
        if (!HourlyOnDemand.extendsHourly(sourceId)) return null
        val latestWeather = database.forecastDao().getLatestWeather()
        val effectiveLat = if (lat != 0.0) lat else latestWeather?.locationLat ?: return null
        val effectiveLon = if (lon != 0.0) lon else latestWeather?.locationLon ?: return null
        val stored = storedHourlyForSource(database, sourceId, effectiveLat, effectiveLon, nowMs)
        return HourlyOnDemand.hoursToCover(sourceId, date, ZoneId.systemDefault(), nowMs, stored)
    }

    /** [sourceId]'s hourly rows at the site from the current hour through [HourlyOnDemand.REACH_HOURS]. */
    suspend fun storedHourlyForSource(
        database: WeatherDatabase,
        sourceId: String,
        lat: Double,
        lon: Double,
        nowMs: Long,
    ): List<com.weatherwidget.data.model.HourlyForecast> =
        database.hourlyForecastDao()
            .getHourlyForecastsBySource(
                nowMs - TimeUnit.HOURS.toMillis(1),
                nowMs + TimeUnit.HOURS.toMillis(HourlyOnDemand.REACH_HOURS.toLong()),
                lat,
                lon,
                sourceId,
            ).map { it.toHourlyForecast() }

    /** "No hourly forecast for {day} — data ends {end}": a day no fetch can help ([HourlyOnDemand.PanAction.NoDataMessage]). */
    fun buildNoDataMessage(context: Context, dayLabel: String, endLabel: String?): String =
        if (endLabel != null) {
            context.getString(R.string.widget_no_hourly_data_ends, dayLabel, endLabel)
        } else {
            context.getString(R.string.widget_no_hourly_data, dayLabel)
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
        return NoHourlyChecker.formatEndLabel(lastMs)
    }
}