package com.weatherwidget.widget.handlers

import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.widget.ObservationResolver
import com.weatherwidget.widget.ViewMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Emits the `CURR_STALE_DEBUG` VERBOSE breadcrumb for temperature-graph renders.
 *
 * Split out of [WidgetIntentRouter] (2026-07-28, third-pass review N7) so current-temperature
 * diagnostic formatting and age arithmetic no longer live in the intent dispatcher. VERBOSE is
 * dropped at the DAO boundary ([com.weatherwidget.data.local.AppLogDao.log] skips it before Room),
 * so these per-render rows reach logcat without growing the persistent `app_logs` table.
 */
object CurrentTempStalenessLogger {
    suspend fun log(
        appLogDao: AppLogDao,
        appWidgetId: Int,
        viewMode: ViewMode,
        displaySource: WeatherSource,
        observation: ObservationResolver.ObservedCurrentTemperature?,
        centerTime: LocalDateTime,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (viewMode != ViewMode.TEMPERATURE) return

        if (observation == null) {
            appLogDao.log(
                "CURR_STALE_DEBUG",
                "widget=$appWidgetId source=${displaySource.id} center=$centerTime observation=none",
                "VERBOSE",
            )
            return
        }

        val observedAgeMin = ((nowMs - observation.observedAt).coerceAtLeast(0L) / 1000.0 / 60.0)
        val fetchAgeMin = ((nowMs - observation.rowFetchedAt).coerceAtLeast(0L) / 1000.0 / 60.0)
        val message =
            "widget=$appWidgetId source=${displaySource.id} selectedSource=${observation.source} " +
                "temp=${String.format(Locale.US, "%.1f", observation.temperature)} " +
                "obsAt=${formatEpochLocal(observation.observedAt)} obsAgeMin=${String.format(Locale.US, "%.1f", observedAgeMin)} " +
                "rowFetchedAt=${formatEpochLocal(observation.rowFetchedAt)} rowFetchAgeMin=${String.format(Locale.US, "%.1f", fetchAgeMin)} " +
                "center=$centerTime"
        appLogDao.log("CURR_STALE_DEBUG", message, "VERBOSE")
    }

    private fun formatEpochLocal(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
}
