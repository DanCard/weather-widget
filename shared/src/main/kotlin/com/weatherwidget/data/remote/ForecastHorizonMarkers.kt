package com.weatherwidget.data.remote

/**
 * When one source last fetched each horizon at one site — what [ForecastHorizonCadence] measures
 * "due" from. Android keeps them in prefs, desktop in `app_logs` marker rows.
 */
interface ForecastHorizonMarkers {
    fun lastLongMs(): Long?
    fun lastShortMs(): Long?
    suspend fun markLong(nowMs: Long)
    suspend fun markShort(nowMs: Long)

    companion object {
        const val LOG_TAG = "FORECAST_HORIZON"

        /**
         * A forecast fetch of [horizon] answered: it restarts the SHORT cadence, a LONG one the LONG
         * cadence too. Logged `FORECAST_HORIZON source=… horizon=…` (`unjudged` without a cadence
         * decision) on both platforms.
         */
        suspend fun mark(
            markers: ForecastHorizonMarkers,
            sourceId: String,
            horizon: ForecastHorizonCadence.Horizon?,
            nowMs: Long,
            log: suspend (tag: String, message: String, level: String) -> Unit,
        ) {
            markers.markShort(nowMs)
            if (horizon == ForecastHorizonCadence.Horizon.LONG) markers.markLong(nowMs)
            log(LOG_TAG, "source=$sourceId horizon=${horizon ?: "unjudged"}", "INFO")
        }
    }
}
