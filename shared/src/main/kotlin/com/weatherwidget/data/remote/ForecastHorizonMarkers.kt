package com.weatherwidget.data.remote

/**
 * When one source last fetched at one site — what [ForecastHorizonCadence] measures "due" from.
 * Android keeps it in prefs, desktop in `app_logs` marker rows.
 */
interface ForecastHorizonMarkers {
    fun lastLongMs(): Long?
    suspend fun markLong(nowMs: Long)

    companion object {
        const val LOG_TAG = "FORECAST_HORIZON"

        /**
         * A forecast fetch answered: a judged ([ForecastHorizonCadence.Horizon.LONG]) one restarts the
         * cadence. Logged `FORECAST_HORIZON source=… horizon=…` (`unjudged` without a cadence decision)
         * on both platforms.
         */
        suspend fun mark(
            markers: ForecastHorizonMarkers,
            sourceId: String,
            horizon: ForecastHorizonCadence.Horizon?,
            nowMs: Long,
            log: suspend (tag: String, message: String, level: String) -> Unit,
        ) {
            if (horizon == ForecastHorizonCadence.Horizon.LONG) markers.markLong(nowMs)
            log(LOG_TAG, "source=$sourceId horizon=${horizon ?: "unjudged"}", "INFO")
        }
    }
}
