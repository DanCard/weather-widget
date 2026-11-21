package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.util.LocationChangeRefreshPolicy
import com.weatherwidget.data.local.LocationMatch
import java.time.LocalDate

/** How long the window waits for the daemon's location-change result: its offline retries (5 s + 15 s) plus fetch timeouts. */
internal const val LOCATION_REFRESH_WAIT_MS = 150_000L

/**
 * A picker move is refreshed by the daemon alone: its `.config-changed` launch refresh
 * ([LOCATION_CHANGE_REASON]). The window used to send its own forecast request beside it, and both
 * fetched NWS within one second on 2026-11-21. The window still needs the outcome, to end its
 * "Getting weather for…" interstitial or banner or to turn it into "Couldn't get weather for…", so
 * the daemon records one row per location-change refresh and pushes [LOCATION_REFRESH_BYTE]
 * ([notifyLocationRefresh]). The row is keyed by the quantized site, so a result for another site is
 * never taken for this one, and the window reads results recorded since its pick, so a daemon that
 * finishes before the window starts waiting is not missed.
 * plans/261121-location-change-honours-fresh-cache.md
 */
internal object DesktopLocationRefreshResult {
    const val TAG = "LOCATION_REFRESH_RESULT"

    private fun sitePrefix(latitude: Double, longitude: Double) =
        "site=${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)} "

    fun record(dao: DesktopWeatherDao, sourceId: String, latitude: Double, longitude: Double, action: LaunchRefreshAction, ok: Boolean) {
        dao.log(TAG, sitePrefix(latitude, longitude) + "source=$sourceId action=$action ok=$ok", if (ok) "INFO" else "WARN")
        notifyLocationRefresh()
    }

    /** Whether the newest result for this site recorded at or after [sinceMs] succeeded; null when none yet. */
    fun since(dao: DesktopWeatherDao, latitude: Double, longitude: Double, sinceMs: Long): Boolean? =
        dao.getLatestLogByTagAndMessagePrefix(TAG, sitePrefix(latitude, longitude))
            ?.takeIf { it.timestamp >= sinceMs }
            ?.let { it.message.contains(" ok=true") }
}

/**
 * The daemon's launch action for a location change ([LOCATION_CHANGE_REASON]):
 * [LocationChangeRefreshPolicy] decides the forecast against the new site's own last fetch, with
 * "drawable" judged exactly as the window judges it ([DesktopLocationChangeFeedback.decide]), so the
 * window waits only when the daemon fetches. Otherwise observations refresh when they are stale.
 */
internal fun locationChangeLaunchAction(
    dao: DesktopWeatherDao,
    config: DesktopConfig,
    cached: com.weatherwidget.data.model.ForecastSnapshot?,
    lastObservationFetchMs: Long?,
    nowMs: Long = System.currentTimeMillis(),
    isHighBattery: Boolean = desktopIsHighBattery(),
): LaunchRefreshAction {
    val drawable = DesktopLocationChangeFeedback.decide(cached, hasRenderOnScreen = true, LocalDate.now(), nowMs).adoptCached
    val decision = desktopLocationChangeRefresh(dao, config.displaySource, config.lat, config.lon, drawable, nowMs, isHighBattery)
    if (decision.fetchForecast) return LaunchRefreshAction.FULL_FORECAST
    val observationsFresh = lastObservationFetchMs != null && nowMs - lastObservationFetchMs < FRESHNESS_THRESHOLD_MS
    return if (observationsFresh) LaunchRefreshAction.NONE else LaunchRefreshAction.OBSERVATIONS
}
