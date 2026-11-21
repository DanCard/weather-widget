package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.data.remote.ForecastHorizonCadence
import com.weatherwidget.shared.util.LocationChangeRefreshPolicy

/**
 * Whether a picker move refetches [sourceId]'s forecast at the new site, judged by
 * [LocationChangeRefreshPolicy] against that site's own last fetch
 * ([DesktopWeatherDao.getLastSuccessfulFetch]). The daemon's launch refresh is the only caller
 * ([locationChangeLaunchAction]). Logged as `LOCATION_CHANGE_REFRESH`.
 * [drawableCache] = the new site's cache is what the window adopts onto the screen.
 */
internal fun desktopLocationChangeRefresh(
    dao: DesktopWeatherDao,
    sourceId: String,
    latitude: Double,
    longitude: Double,
    drawableCache: Boolean,
    nowMs: Long = System.currentTimeMillis(),
    isHighBattery: Boolean = desktopIsHighBattery(),
): LocationChangeRefreshPolicy.Decision =
    LocationChangeRefreshPolicy.decide(
        drawableCache = drawableCache,
        lastForecastFetchMs = dao.getLastSuccessfulFetch(sourceId, latitude, longitude),
        nowMs = nowMs,
        isHighBattery = isHighBattery,
    ).also { dao.log(LocationChangeRefreshPolicy.TAG, it.logLine(sourceId), "INFO") }

internal fun desktopIsHighBattery(): Boolean =
    PowerDetector.getPowerState().let { ForecastHorizonCadence.isHighBattery(it.isCharging, it.batteryLevel) }
