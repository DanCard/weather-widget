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
    val userInteraction: Boolean,
    val observationBackfillMode: Boolean,
    val backfillLat: Double,
    val backfillLon: Double,
    val backfillHours: Long,
    val backfillReason: String,
    /** See [WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ATTEMPT]. */
    val backfillAttempt: Int = 0,
    /** See [WeatherWidgetWorker.KEY_REQUESTED_AT_MS]; 0 when the enqueue path did not stamp it. */
    val requestedAtMs: Long = 0L,
    /** See [WeatherWidgetWorker.KEY_STARTUP_DEFERRED]. */
    val startupDeferred: Boolean = false,
    /** See [WeatherWidgetWorker.KEY_LOCATION_CHANGE_PLACE]; null on every other run. */
    val locationChangePlace: String? = null,
    /** See [WeatherWidgetWorker.KEY_LOCATION_CHANGE_BANNER]. */
    val locationChangeBanner: Boolean = false,
    /** See [WeatherWidgetWorker.KEY_LOCATION_CACHE_ADOPTED]. */
    val locationCacheAdopted: Boolean = false,
    /** See [WeatherWidgetWorker.KEY_SOURCE_SWITCH_ID]; null on every other run. */
    val sourceSwitchId: String? = null,
    /** See [WeatherWidgetWorker.KEY_HOURLY_LIMITED]. */
    val hourlyLimited: Boolean = false,
) {
    companion object {
        fun from(data: Data): WorkInput {
            val uiOnlyRefresh = data.getBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, false)
            val forceRefresh = data.getBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, false)
            val currentTempOnly = data.getBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, false)
            val nonPrimaryCurrentTempOnly = data.getBoolean(WeatherWidgetWorker.KEY_NONPRIMARY_CURRENT_TEMP_ONLY, false)
            val observationBackfillMode = data.getBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, false)

            return WorkInput(
                uiOnlyRefresh = uiOnlyRefresh,
                forceRefresh = forceRefresh,
                candidateLocationRefresh = data.getBoolean(WeatherWidgetWorker.KEY_LOCATION_CANDIDATE_REFRESH, false),
                currentTempOnly = currentTempOnly,
                nonPrimaryCurrentTempOnly = nonPrimaryCurrentTempOnly,
                opportunisticCurrentTemp = data.getBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_OPPORTUNISTIC, false),
                currentTempReason = data.getString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON) ?: "unspecified",
                targetSourceId = data.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE),
                userInteraction = data.getBoolean(WeatherWidgetWorker.KEY_USER_INTERACTION, false),
                observationBackfillMode = observationBackfillMode,
                // NaN, not a coordinate: a backfill enqueued without an explicit location has no
                // location, and must skip rather than pull observations for somewhere else.
                backfillLat = data.getDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, Double.NaN),
                backfillLon = data.getDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, Double.NaN),
                backfillHours = data.getLong(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_HOURS, WeatherWidgetWorker.DEFAULT_OBSERVATION_BACKFILL_HOURS),
                backfillReason = data.getString(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_REASON) ?: "unspecified",
                backfillAttempt = data.getInt(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ATTEMPT, 0),
                requestedAtMs = data.getLong(WeatherWidgetWorker.KEY_REQUESTED_AT_MS, 0L),
                startupDeferred = data.getBoolean(WeatherWidgetWorker.KEY_STARTUP_DEFERRED, false),
                locationChangePlace = data.getString(WeatherWidgetWorker.KEY_LOCATION_CHANGE_PLACE),
                locationChangeBanner = data.getBoolean(WeatherWidgetWorker.KEY_LOCATION_CHANGE_BANNER, false),
                locationCacheAdopted = data.getBoolean(WeatherWidgetWorker.KEY_LOCATION_CACHE_ADOPTED, false),
                sourceSwitchId = data.getString(WeatherWidgetWorker.KEY_SOURCE_SWITCH_ID),
                hourlyLimited = data.getBoolean(WeatherWidgetWorker.KEY_HOURLY_LIMITED, false),
            )
        }
    }
}
