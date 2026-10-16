package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.ForecastHorizonCadence

/**
 * The desktop's inputs to the shared [ForecastHorizonCadence] for [weatherSource] at the site: the
 * machine's power and screen state and two markers read back from `app_logs` — the decision itself
 * is `:shared`, as on Android (performance/261016-unified-long-short-forecast-horizons.md).
 *
 * Markers are log rows (message `source=… site=lat,lon`): [LONG_TAG] for the last LONG fetch,
 * [SHORT_TAG] for the last fetch of either horizon (Android keeps both in prefs). app_logs keeps 72 h,
 * longer than the 24 h longest interval, so no new table is needed. The service (what to ask a billed
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
) {
    private val siteKey = "${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)}"
    private val prefix = "source=$weatherSource site=$siteKey"

    fun lastLongFetch(): Long? = weatherDao?.getLatestLogByTagAndMessagePrefix(LONG_TAG, prefix)?.timestamp

    fun lastShortFetch(): Long? = weatherDao?.getLatestLogByTagAndMessagePrefix(SHORT_TAG, prefix)?.timestamp

    /** [ForecastHorizonCadence.decide] for this source now; null without a DB (not judged). */
    fun decision(nowMs: Long): ForecastHorizonCadence.Decision? {
        weatherDao ?: return null
        val (isCharging, level) = powerState()
        return ForecastHorizonCadence.decide(
            isCharging = isCharging,
            batteryLevel = level,
            isScreenOn = screenOn(),
            lastLongMs = lastLongFetch(),
            lastShortMs = lastShortFetch(),
            nowMs = nowMs,
        )
    }

    /**
     * The horizon of a fetch made now outside the schedule ([ForecastHorizonCadence.forced]): the
     * user's Refresh is LONG; anything else what is due, at least SHORT. Null without a DB.
     */
    fun forced(nowMs: Long, userRefresh: Boolean = false): ForecastHorizonCadence.Horizon? {
        weatherDao ?: return null
        return ForecastHorizonCadence.forced(if (userRefresh) null else decision(nowMs), userRefresh)
    }

    /** A fetch of [horizon] answered: restart SHORT, and LONG too for a LONG fetch. */
    fun mark(horizon: ForecastHorizonCadence.Horizon?) {
        val dao = weatherDao ?: return
        dao.log(tag = SHORT_TAG, message = prefix, level = "INFO")
        if (horizon == ForecastHorizonCadence.Horizon.LONG) dao.log(tag = LONG_TAG, message = prefix, level = "INFO")
    }

    companion object {
        const val LONG_TAG = "FORECAST_LONG_FETCH"
        const val SHORT_TAG = "FORECAST_SHORT_FETCH"
    }
}
