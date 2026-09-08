package com.weatherwidget.ui

import android.appwidget.AppWidgetManager
import com.weatherwidget.data.local.AppLogEntity
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.observations.ObservationSourceMatcher

internal object WeatherObservationsSupport {
    fun shouldRefreshWidgetOnExit(
        appWidgetId: Int,
        widgetContentChanged: Boolean,
    ): Boolean =
        appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID && widgetContentChanged

    // Delegates to the shared matcher so Android and the desktop stations list filter synthetic
    // rows (NWS_BLEND, the NWS history backfill) identically. See ObservationSourceMatcher.
    fun matchesObservationSource(stationId: String, source: WeatherSource): Boolean =
        ObservationSourceMatcher.matchesObservationSource(stationId, source)

    // The list filter proper. Split from the above because a forecast-only source shows its
    // BORROWED provider's stations — the "Actuals source" row above the list names that feed,
    // and filtering by display source alone left the picker sitting over an empty list.
    fun matchesStationsList(stationId: String, api: String, source: WeatherSource): Boolean =
        ObservationSourceMatcher.matchesStationsList(stationId, api, source)

    fun matchesFetchLog(log: AppLogEntity, source: WeatherSource): Boolean =
        when (log.tag) {
            "CURR_FETCH_START",
            "CURR_FETCH_DONE",
            "CURR_FETCH_SKIP",
            -> log.message.containsTargetSource(source)
            "CURR_FETCH_ERROR",
            "CURR_FETCH_SOURCE_RESULT",
            "OBS_CURRENT_INSERT",
            -> log.message.containsSource(source)
            "OBS_HOURLY_BACKFILL_SKIP",
            "OBS_HOURLY_BACKFILL_REQ",
            -> log.message.containsSource(source)
            "OBS_HOURLY_BACKFILL_START",
            "OBS_HOURLY_BACKFILL_FAIL",
            "OBS_HOURLY_BACKFILL_STATION",
            "OBS_HOURLY_BACKFILL_STATION_FAIL",
            "OBS_HOURLY_BACKFILL_DONE",
            -> source == WeatherSource.NWS
            "CURR_FETCH_EXCEPTION",
            "CURR_FETCH_FAIL",
            "CURR_FETCH_CANCELLED",
            "CURR_FETCH_FRESH_SKIP",
            "CURR_FETCH_WORK_ENQUEUED",
            "CURR_FETCH_WORK_REQUESTED",
            "CURR_FETCH_WORK_STATE",
            "CURR_FETCH_WORK_RECOVERED",
            "CURR_FETCH_WORK_START",
            "CURR_FETCH_WORK_RESULT",
            "CURR_FETCH_WORK_CANCELLED",
            "CURR_FETCH_LOOP_STOP",
            -> true
            else -> false
        }

    fun formatFetchLog(log: AppLogEntity, source: WeatherSource): String {
        val message =
            when (log.tag) {
                "CURR_FETCH_START", "CURR_FETCH_DONE" -> log.message
                "CURR_FETCH_ERROR" -> log.message.removePrefix("source=${source.id} ")
                else -> log.message
            }

        return when (log.tag) {
            "CURR_FETCH_START" -> "start $message"
            "CURR_FETCH_DONE" -> "done $message"
            "CURR_FETCH_SKIP" -> "skip $message"
            "CURR_FETCH_ERROR" -> "error $message"
            "CURR_FETCH_SOURCE_RESULT" -> "source $message"
            "OBS_CURRENT_INSERT" -> "insert $message"
            "OBS_HOURLY_BACKFILL_START" -> "hourly start $message"
            "OBS_HOURLY_BACKFILL_SKIP" -> "hourly skip $message"
            "OBS_HOURLY_BACKFILL_REQ" -> "hourly request $message"
            "OBS_HOURLY_BACKFILL_FAIL" -> "hourly fail $message"
            "OBS_HOURLY_BACKFILL_STATION" -> "hourly station $message"
            "OBS_HOURLY_BACKFILL_STATION_FAIL" -> "hourly station fail $message"
            "OBS_HOURLY_BACKFILL_DONE" -> "hourly done $message"
            "CURR_FETCH_EXCEPTION" -> "exception $message"
            "CURR_FETCH_FAIL" -> "fail $message"
            "CURR_FETCH_CANCELLED" -> "cancelled $message"
            "CURR_FETCH_FRESH_SKIP" -> "fresh skip $message"
            "CURR_FETCH_WORK_ENQUEUED" -> "enqueued $message"
            "CURR_FETCH_WORK_REQUESTED" -> "requested $message"
            "CURR_FETCH_WORK_STATE" -> "work state $message"
            "CURR_FETCH_WORK_RECOVERED" -> "recovered $message"
            "CURR_FETCH_WORK_START" -> "work start $message"
            "CURR_FETCH_WORK_RESULT" -> "work result $message"
            "CURR_FETCH_WORK_CANCELLED" -> "work cancelled $message"
            "CURR_FETCH_LOOP_STOP" -> "loop stop $message"
            else -> message
        }
    }

    private fun String.containsTargetSource(source: WeatherSource): Boolean {
        val targets = substringAfter("targets=", missingDelimiterValue = "")
        if (targets.isEmpty()) return false
        return targets.split(",")
            .map { it.trim() }
            .any { it == source.id }
    }

    private fun String.containsSource(source: WeatherSource): Boolean =
        contains("source=${source.id}")
}
