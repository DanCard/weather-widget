package com.weatherwidget.desktop

import com.weatherwidget.data.model.CurrentStatus
import com.weatherwidget.data.model.ForecastSnapshot
import com.weatherwidget.data.model.RawFetch

/**
 * Single owner of the resolved current-status snapshot the daemon publishes after each fetch.
 *
 * The temperature/delta math still lives in [DesktopWeatherRepository.resolveCurrentTempInMemory]
 * (injected as [resolveTemp]); this class packages its result — plus the location/source identity and
 * the observation/condition metadata the panel and popup display — into the persistable
 * [CurrentStatus].
 */
class CurrentStatusResolver(
    private val latitude: Double,
    private val longitude: Double,
    private val source: String,
    private val resolveTemp: (RawFetch, Long) -> DesktopWeatherRepository.ResolvedCurrentTemp,
) {
    fun resolve(snapshot: ForecastSnapshot, now: Long): CurrentStatus {
        val resolved = resolveTemp(snapshot.raw, now)
        return CurrentStatus(
            locationLat = latitude,
            locationLon = longitude,
            source = source,
            displayTempF = resolved.displayTemp,
            appliedDeltaF = resolved.appliedDelta,
            deltaFromYesterdayF = resolved.deltaFromYesterday,
            observedAtMs = snapshot.resolved.currentObservedAt,
            condition = snapshot.resolved.currentCondition,
            updatedAt = now,
        )
    }
}
