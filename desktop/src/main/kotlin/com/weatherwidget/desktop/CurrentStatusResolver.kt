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
    /**
     * `CURRENT_STATUS_PUBLISH` sink. Called only when the published value moves ≥ 0.1° or its forecast
     * correction appears / disappears, so the 2-min re-resolve stays quiet. Added after the header
     * showed KSJC's lone 73.4 with no correction for minutes while the popup's own resolve read 71.8
     * (2026-10-13; cause never found — this records what the next one is built from).
     */
    private val logChange: ((String) -> Unit)? = null,
    private val resolveTemp: (RawFetch, Long) -> DesktopWeatherRepository.ResolvedCurrentTemp,
) {
    @Volatile private var lastLogged: DesktopWeatherRepository.ResolvedCurrentTemp? = null

    fun resolve(snapshot: ForecastSnapshot, now: Long): CurrentStatus {
        val resolved = resolveTemp(snapshot.raw, now)
        logIfChanged(snapshot, resolved)
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

    private fun logIfChanged(snapshot: ForecastSnapshot, resolved: DesktopWeatherRepository.ResolvedCurrentTemp) {
        val sink = logChange ?: return
        val previous = lastLogged
        val moved = previous == null ||
            (previous.displayTemp == null) != (resolved.displayTemp == null) ||
            (previous.appliedDelta == null) != (resolved.appliedDelta == null) ||
            kotlin.math.abs((previous.displayTemp ?: 0f) - (resolved.displayTemp ?: 0f)) >= 0.1f
        if (!moved) return
        lastLogged = resolved
        val observations = snapshot.raw.rawObservations
        sink(
            "source=$source display=${resolved.displayTemp} appliedDelta=${resolved.appliedDelta} " +
                "obs=${resolved.observedTemp} observedAt=${resolved.observedAt} " +
                "windowObs=${resolved.windowObservations} windowHourly=${resolved.windowHourly} " +
                "snapshotObs=${observations.size} snapshotNewestObs=${observations.maxOfOrNull { it.timestamp }} " +
                "snapshotHourly=${snapshot.raw.hourly.size} previous=${previous?.displayTemp}",
        )
    }
}
