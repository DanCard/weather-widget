package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import androidx.work.Data

internal data class WorkInput(
    val uiOnlyRefresh: Boolean,
    val forceRefresh: Boolean,
    val candidateLocationRefresh: Boolean,
    val currentTempOnly: Boolean,
    val nonPrimaryCurrentTempOnly: Boolean,
    val opportunisticCurrentTemp: Boolean,
    val currentTempReason: String,
    val targetSourceId: String?,
    val observationBackfillMode: Boolean,
    val backfillLat: Double,
    val backfillLon: Double,
    val backfillHours: Long,
    val backfillReason: String,
    val noHourlyWidgetId: Int,
    val noHourlyDate: String?,
    val noHourlyLat: Double,
    val noHourlyLon: Double,
    val shouldBroadcastNoHourlyComplete: Boolean,
) {
    companion object {
        fun from(data: Data): WorkInput {
            val uiOnlyRefresh = data.getBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, false)
            val forceRefresh = data.getBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, false)
            val currentTempOnly = data.getBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, false)
            val nonPrimaryCurrentTempOnly = data.getBoolean(WeatherWidgetWorker.KEY_NONPRIMARY_CURRENT_TEMP_ONLY, false)
            val observationBackfillMode = data.getBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, false)
            val noHourlyWidgetId = data.getInt(WeatherWidgetWorker.KEY_NO_HOURLY_WIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            val noHourlyDate = data.getString(WeatherWidgetWorker.KEY_NO_HOURLY_DATE)
            val shouldBroadcastNoHourlyComplete =
                !uiOnlyRefresh &&
                    noHourlyWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID &&
                    !noHourlyDate.isNullOrBlank()

            return WorkInput(
                uiOnlyRefresh = uiOnlyRefresh,
                forceRefresh = forceRefresh,
                candidateLocationRefresh = data.getBoolean(WeatherWidgetWorker.KEY_LOCATION_CANDIDATE_REFRESH, false),
                currentTempOnly = currentTempOnly,
                nonPrimaryCurrentTempOnly = nonPrimaryCurrentTempOnly,
                opportunisticCurrentTemp = data.getBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_OPPORTUNISTIC, false),
                currentTempReason = data.getString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON) ?: "unspecified",
                targetSourceId = data.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE),
                observationBackfillMode = observationBackfillMode,
                // NaN, not a coordinate: a backfill enqueued without an explicit location has no
                // location, and must skip rather than pull observations for somewhere else.
                backfillLat = data.getDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, Double.NaN),
                backfillLon = data.getDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, Double.NaN),
                backfillHours = data.getLong(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_HOURS, WeatherWidgetWorker.DEFAULT_OBSERVATION_BACKFILL_HOURS),
                backfillReason = data.getString(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_REASON) ?: "unspecified",
                noHourlyWidgetId = noHourlyWidgetId,
                noHourlyDate = noHourlyDate,
                noHourlyLat = data.getDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LAT, 0.0),
                noHourlyLon = data.getDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LON, 0.0),
                shouldBroadcastNoHourlyComplete = shouldBroadcastNoHourlyComplete,
            )
        }
    }
}
