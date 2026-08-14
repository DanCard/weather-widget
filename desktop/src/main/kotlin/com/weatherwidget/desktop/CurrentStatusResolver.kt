package com.weatherwidget.desktop

import com.weatherwidget.data.model.CurrentStatus
import com.weatherwidget.data.model.ForecastResult

/**
 * Single owner of the resolved current-status snapshot the daemon publishes after each fetch.
 *
 * Phase 1 of the daemon/UI architecture cleanup: the temperature/delta math still lives in
 * [DesktopWeatherRepository.resolveCurrentTempInMemory] (injected as [resolveTemp]); this class only
 * packages its result — plus the location/source identity and the observation/condition metadata the
 * panel and popup header display — into the persistable [CurrentStatus]. Later phases repoint the
 * panel and UI at the persisted value so they stop re-deriving it independently.
 */
class CurrentStatusResolver(
    private val latitude: Double,
    private val longitude: Double,
    private val source: String,
    private val resolveTemp: (ForecastResult, Long) -> DesktopWeatherRepository.ResolvedCurrentTemp,
) {
    fun resolve(forecast: ForecastResult, now: Long): CurrentStatus {
        val resolved = resolveTemp(forecast, now)
        return CurrentStatus(
            locationLat = latitude,
            locationLon = longitude,
            source = source,
            displayTempF = resolved.displayTemp,
            appliedDeltaF = resolved.appliedDelta,
            deltaFromYesterdayF = resolved.deltaFromYesterday,
            observedAtMs = forecast.currentObservedAt,
            condition = forecast.currentCondition,
            updatedAt = now,
        )
    }
}
