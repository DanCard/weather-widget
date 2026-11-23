package com.weatherwidget.data.remote

import com.weatherwidget.shared.util.AppLog

/**
 * Uniform per-source forecast fetch outcome breadcrumb in `app_logs` across Android and desktop.
 * Lets diagnostics and `scripts/open-meteo-stats.py` correlate successes, failures, and recovery
 * times identically on both platforms.
 */
object ForecastFetchLog {
    const val TAG = "FORECAST_FETCH"

    fun logSuccess(sourceId: String, elapsedMs: Long) {
        AppLog.i(TAG, "source=$sourceId ok=true ms=$elapsedMs")
    }

    fun logFailure(sourceId: String, elapsedMs: Long, errorCode: String?, errorClass: String) {
        AppLog.w(TAG, "source=$sourceId ok=false ms=$elapsedMs code=${errorCode ?: "UNKNOWN"} class=$errorClass")
    }
}
