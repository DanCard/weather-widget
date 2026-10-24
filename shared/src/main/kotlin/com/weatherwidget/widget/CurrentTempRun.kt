package com.weatherwidget.widget

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.shared.actuals.ActualsAggregator
import com.weatherwidget.shared.actuals.YesterdayDeltaCalculator
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The header's current temperature, start to finish: the now-centred window, the observation blend
 * at now, [CurrentTemperatureResolver.resolve] (forecast + that reading's delta), and the "from
 * yesterday" delta. No blended reading = the bare forecast: no stored delta from an earlier reading
 * and no raw-row fallback, on either platform (user, 2026-10-24) — the fallback could only ever show
 * a reading the blend had rejected (QC-failed, or a personal station when they are ignored).
 * Desktop's `resolveForForecastResult`, moved (plans/261024-shared-current-temp-run.md).
 */
object CurrentTempRun {
    data class Result(
        val displayTemp: Float?,
        val appliedDelta: Float?,
        val deltaFromYesterday: Float?,
        val observedTemp: Float?,
        val observedAt: Long?,
        val windowObservations: Int,
        val windowHourly: Int,
    )

    /**
     * [hourly] and [observations] may be wider than the window: the yesterday delta reads the full
     * lists, the rest is cut to the window here.
     */
    fun run(
        hourly: List<HourlyForecast>,
        observations: List<ObservationReading>,
        displaySource: WeatherSource,
        latitude: Double,
        longitude: Double,
        nowMs: Long,
        personalStationWeight: Double,
        zoneId: ZoneId = ZoneId.systemDefault(),
        resultLogLevel: String = "DEBUG",
    ): Result {
        val nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zoneId)
        val window = CurrentTemperatureResolver.buildCurrentTempResolutionWindow(nowLocal)
        val minMs = window.start.atZone(zoneId).toInstant().toEpochMilli()
        val maxMs = window.end.atZone(zoneId).toInstant().toEpochMilli()
        val windowObs = observations.filter { it.timestamp in minMs..maxMs }
        val windowHourly = hourly.filter { it.dateTime in minMs..maxMs }

        val blended = ActualsAggregator.resolveCurrentObservation(
            observations = windowObs,
            hourlyForecasts = windowHourly,
            displaySourceId = displaySource.id,
            userLat = latitude,
            userLon = longitude,
            nowMs = nowMs,
            lookbackHours = CurrentTemperatureResolver.RESOLUTION_LOOKBACK_HOURS,
            lookaheadHours = CurrentTemperatureResolver.RESOLUTION_LOOKAHEAD_HOURS,
            personalStationWeight = personalStationWeight,
        )
        val observedTemp = blended?.first
        val observedAt = blended?.second

        val resolution = CurrentTemperatureResolver.resolve(
            now = nowLocal,
            displaySource = displaySource,
            hourlyForecasts = windowHourly,
            lastObservedTemp = observedTemp,
            observedAt = observedAt,
            smoothedForecasts = CurrentTemperatureResolver.computeSmoothedForecasts(windowHourly, displaySource.id),
            resultLogLevel = resultLogLevel,
        )
        // The full lists, not the window: the 24 h-ago blend target never fits in it.
        val deltaFromYesterday = YesterdayDeltaCalculator.computeDelta(
            observations = observations,
            hourlyForecasts = hourly,
            displaySourceId = displaySource.id,
            userLat = latitude,
            userLon = longitude,
            observedAtMs = observedAt,
            currentObservedTemp = observedTemp,
            personalStationWeight = personalStationWeight,
            zoneId = zoneId,
        )
        return Result(
            displayTemp = resolution.displayTemp,
            appliedDelta = resolution.appliedDelta,
            deltaFromYesterday = deltaFromYesterday,
            observedTemp = observedTemp,
            observedAt = observedAt,
            windowObservations = windowObs.size,
            windowHourly = windowHourly.size,
        )
    }
}
