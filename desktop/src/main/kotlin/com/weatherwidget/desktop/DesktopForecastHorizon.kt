package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.data.remote.ForecastHorizonMarkers

/**
 * The desktop's inputs to the shared [ForecastHorizonCadence] for [weatherSource] at the site: the
 * machine's power and screen state and one marker read back from `app_logs` — the decision itself
 * is `:shared`, as on Android (performance/261025-every-forecast-fetch-long.md).
 *
 * Markers are log rows (message `source=… site=lat,lon`) tagged [LONG_TAG] (Android keeps it in
 * prefs). app_logs keeps 72 h, longer than the 24 h longest interval, so no new table is needed. The service (what to ask a billed
 * source for) and the repository (what to store) both read them before the fetch writes them, so
 * they agree.
 */
internal class DesktopForecastHorizon(
    private val weatherDao: DesktopWeatherDao?,
    private val weatherSource: String,
    latitude: Double,
    longitude: Double,
    private val powerState: () -> PowerDetector.PowerState = { PowerDetector.getPowerState() },
    private val screenOn: () -> Boolean = { ScreenStateDetector.isScreenOn() },
) : ForecastHorizonMarkers {
    private val siteKey = "${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)}"
    private val prefix = "source=$weatherSource site=$siteKey"

    fun lastLongFetch(): Long? = weatherDao?.getLatestLogByTagAndMessagePrefix(LONG_TAG, prefix)?.timestamp

    override fun lastLongMs(): Long? = lastLongFetch()

    override suspend fun markLong(nowMs: Long) {
        weatherDao?.log(tag = LONG_TAG, message = prefix, level = "INFO")
    }

    /** [ForecastHorizonCadence.decide] for this source now; null without a DB (not judged). */
    fun decision(nowMs: Long): ForecastHorizonCadence.Decision? {
        weatherDao ?: return null
        val (isCharging, level) = powerState()
        return ForecastHorizonCadence.decide(
            isCharging = isCharging,
            batteryLevel = level,
            isScreenOn = screenOn(),
            lastFetchMs = lastLongFetch(),
            nowMs = nowMs,
        )
    }

    /** The horizon of a fetch made now outside the schedule: LONG, like every fetch. Null without a DB (not judged). */
    fun forced(): ForecastHorizonCadence.Horizon? = ForecastHorizonCadence.Horizon.LONG.takeIf { weatherDao != null }

    /** A fetch of [horizon] answered ([ForecastHorizonMarkers.mark], shared with Android). */
    suspend fun mark(horizon: ForecastHorizonCadence.Horizon?, nowMs: Long = System.currentTimeMillis()) {
        val dao = weatherDao ?: return
        ForecastHorizonMarkers.mark(this, weatherSource, horizon, nowMs) { tag, message, level -> dao.log(tag, message, level) }
    }

    companion object {
        const val LONG_TAG = "FORECAST_LONG_FETCH"
    }
}
