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
    /**
     * How far back the run's inputs must reach: the yesterday delta blends the readings around
     * observedAt − 24 h (± 90 min), so 36 h covers it with room for a late observation. Android's
     * reads take this window ([inputWindow]); the blend at now still cuts to the 12 h one.
     */
    const val INPUT_LOOKBACK_HOURS = 36L

    /** [nowLocal] − [INPUT_LOOKBACK_HOURS] … the resolution window's end, as epoch ms. */
    fun inputWindow(nowLocal: LocalDateTime, zoneId: ZoneId = ZoneId.systemDefault()): LongRange {
        val window = CurrentTemperatureResolver.buildCurrentTempResolutionWindow(nowLocal)
        return nowLocal.minusHours(INPUT_LOOKBACK_HOURS).atZone(zoneId).toInstant().toEpochMilli()..
            window.end.atZone(zoneId).toInstant().toEpochMilli()
    }

    /** The observation side: the blend at now and the "from yesterday" delta it anchors. */
    data class Observed(
        val temperature: Float?,
        val observedAt: Long?,
        val rowFetchedAt: Long?,
        val deltaFromYesterday: Float?,
        val windowObservations: Int,
        val windowHourly: Int,
    )

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
        val observed = observe(hourly, observations, displaySource, latitude, longitude, nowMs, personalStationWeight, zoneId)
        val resolution = header(
            hourly = hourly,
            displaySource = displaySource,
            nowLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zoneId),
            observedTemp = observed.temperature,
            observedAt = observed.observedAt,
            zoneId = zoneId,
            resultLogLevel = resultLogLevel,
        )
        return Result(
            displayTemp = resolution.displayTemp,
            appliedDelta = resolution.appliedDelta,
            deltaFromYesterday = observed.deltaFromYesterday,
            observedTemp = observed.temperature,
            observedAt = observed.observedAt,
            windowObservations = observed.windowObservations,
            windowHourly = observed.windowHourly,
        )
    }

    /**
     * The blend at now (cut to the resolution window) and the yesterday delta (on the full lists).
     * Android calls this once per paint, where it reads the rows, and hands the result down to the
     * view that calls [header].
     */
    fun observe(
        hourly: List<HourlyForecast>,
        observations: List<ObservationReading>,
        displaySource: WeatherSource,
        latitude: Double,
        longitude: Double,
        nowMs: Long,
        personalStationWeight: Double,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Observed {
        val window = resolutionWindowMs(LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zoneId), zoneId)
        val windowObs = observations.filter { it.timestamp in window }
        val windowHourly = hourly.filter { it.dateTime in window }

        val blended = ActualsAggregator.resolveCurrentObservation(
            observations = windowObs,
            hourlyForecasts = windowHourly,
            displaySourceId = displaySource.id,
            userLat = latitude,
            userLon = longitude,
            nowMs = nowMs,
            lookbackHours = CurrentTemperatureResolver.RESOLUTION_LOOKBACK_HOURS,
            lookaheadHours = CurrentTemperatureResolver.RESOLUTION_LOOKAHEAD_HOURS,
            zoneId = zoneId,
            personalStationWeight = personalStationWeight,
        )
        // The full lists, not the window: the 24 h-ago blend target never fits in it.
        val deltaFromYesterday = YesterdayDeltaCalculator.computeDelta(
            observations = observations,
            hourlyForecasts = hourly,
            displaySourceId = displaySource.id,
            userLat = latitude,
            userLon = longitude,
            observedAtMs = blended?.second,
            currentObservedTemp = blended?.first,
            personalStationWeight = personalStationWeight,
            zoneId = zoneId,
        )
        return Observed(
            temperature = blended?.first,
            observedAt = blended?.second,
            rowFetchedAt = blended?.third,
            deltaFromYesterday = deltaFromYesterday,
            windowObservations = windowObs.size,
            windowHourly = windowHourly.size,
        )
    }

    /**
     * The header number: [CurrentTemperatureResolver.resolve] on [hourly] cut to the window around
     * [nowLocal], with its smoothed forecasts. [nowLocal] may be later than the [observe] call's now
     * (Android re-resolves a header between observation reads).
     */
    fun header(
        hourly: List<HourlyForecast>,
        displaySource: WeatherSource,
        nowLocal: LocalDateTime,
        observedTemp: Float?,
        observedAt: Long?,
        zoneId: ZoneId = ZoneId.systemDefault(),
        resultLogLevel: String = "DEBUG",
    ): CurrentTemperatureResolution {
        val window = resolutionWindowMs(nowLocal, zoneId)
        val windowHourly = hourly.filter { it.dateTime in window }
        return CurrentTemperatureResolver.resolve(
            now = nowLocal,
            displaySource = displaySource,
            hourlyForecasts = windowHourly,
            lastObservedTemp = observedTemp,
            observedAt = observedAt,
            smoothedForecasts = CurrentTemperatureResolver.computeSmoothedForecasts(windowHourly, displaySource.id),
            resultLogLevel = resultLogLevel,
        )
    }

    private fun resolutionWindowMs(nowLocal: LocalDateTime, zoneId: ZoneId): LongRange {
        val window = CurrentTemperatureResolver.buildCurrentTempResolutionWindow(nowLocal)
        return window.start.atZone(zoneId).toInstant().toEpochMilli()..window.end.atZone(zoneId).toInstant().toEpochMilli()
    }
}
