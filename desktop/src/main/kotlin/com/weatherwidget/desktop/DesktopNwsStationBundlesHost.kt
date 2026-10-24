package com.weatherwidget.desktop

import com.weatherwidget.data.local.desktop.DesktopWeatherDao
import com.weatherwidget.shared.observations.NwsStationBundles

/**
 * Desktop SQLite side of [NwsStationBundles] at one site; a null [weatherDao] (no database) does
 * nothing. Pinned to Android's by `NwsStationBundlesHostContract` (`:shared` testFixtures).
 */
internal class DesktopNwsStationBundlesHost(
    private val weatherDao: DesktopWeatherDao?,
    private val latitude: Double,
    private val longitude: Double,
    private val clock: () -> Long = System::currentTimeMillis,
) : NwsStationBundles.Host {
    // This site's rows only, as Android: after a move the newest row may be a previous site's.
    override suspend fun touchLatest(stationId: String) {
        weatherDao?.touchLatestObservationFetchedAt(stationId, latitude, longitude, clock())
    }

    override suspend fun log(tag: String, message: String, level: String) {
        weatherDao?.log(tag, message, level)
    }
}
