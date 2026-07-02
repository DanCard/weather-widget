package com.weatherwidget.shared.actuals

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import java.time.ZoneId
import kotlin.math.abs

/**
 * Pure, platform-free computation of the "how much warmer/cooler than this time yesterday" delta shown
 * as a small label on the zoomed-in hourly temperature graph (see [com.weatherwidget.shared.graph.YesterdayDeltaLabel]).
 *
 * The "current" side is the fetch-dot observation ([currentObservedTemp] at [observedAtMs]) — the same
 * most-recent real observation the staleness/age label describes — so the delta is independent of where
 * the graph is panned. The "yesterday" side is the blended actual temperature at `observedAtMs - 24h`,
 * derived from the SAME IDW blend the pink actual line uses ([ActualTemperatureSeriesBuilder.blendObservationSeries]),
 * so the two numbers are measured the same way.
 *
 * Returns null whenever the answer would be guesswork: no fetch-dot, or no observation within
 * [toleranceMs] of the 24h-ago instant (a genuine data gap, or the window doesn't reach back a day —
 * e.g. when navigated far into the past). Callers hide the label on null rather than showing 0.
 */
object YesterdayDeltaCalculator {
    const val YESTERDAY_OFFSET_MS = 24L * 60 * 60 * 1000

    /** Accept a blended observation within ±90 min of the exact 24h-ago instant before interpolating. */
    const val DEFAULT_TOLERANCE_MS = 90L * 60 * 1000

    fun computeDelta(
        observations: List<ObservationReading>,
        hourlyForecasts: List<HourlyForecast>,
        displaySourceId: String,
        userLat: Double,
        userLon: Double,
        observedAtMs: Long?,
        currentObservedTemp: Float?,
        personalStationWeight: Double = 1.0,
        zoneId: ZoneId = ZoneId.systemDefault(),
        toleranceMs: Long = DEFAULT_TOLERANCE_MS,
    ): Float? {
        if (observedAtMs == null || currentObservedTemp == null) return null
        val targetMs = observedAtMs - YESTERDAY_OFFSET_MS

        val blended = ActualTemperatureSeriesBuilder.blendObservationSeries(
            observations = observations,
            hourlyForecasts = hourlyForecasts,
            displaySourceId = displaySourceId,
            userLat = userLat,
            userLon = userLon,
            startMs = targetMs - toleranceMs,
            endMs = targetMs + toleranceMs,
            personalStationWeight = personalStationWeight,
            zoneId = zoneId,
        ).observations
        if (blended.isEmpty()) return null

        val yesterdayTemp = valueAt(blended, targetMs, toleranceMs) ?: return null
        return currentObservedTemp - yesterdayTemp
    }

    /**
     * The blended temperature at [targetMs]: linearly interpolated between the two blended observations
     * bracketing it, or — when only one side exists — the nearest one, provided it falls within
     * [toleranceMs]. Returns null if nothing qualifies.
     */
    private fun valueAt(obs: List<ObservationReading>, targetMs: Long, toleranceMs: Long): Float? {
        val sorted = obs.sortedBy { it.timestamp }
        val before = sorted.lastOrNull { it.timestamp <= targetMs }
        val after = sorted.firstOrNull { it.timestamp >= targetMs }
        return when {
            before != null && after != null && after.timestamp != before.timestamp -> {
                val frac = (targetMs - before.timestamp).toFloat() / (after.timestamp - before.timestamp).toFloat()
                before.temperature + (after.temperature - before.temperature) * frac
            }
            before != null && after != null -> before.temperature // exact hit (same timestamp)
            else -> {
                val nearest = sorted.minByOrNull { abs(it.timestamp - targetMs) } ?: return null
                if (abs(nearest.timestamp - targetMs) <= toleranceMs) nearest.temperature else null
            }
        }
    }
}
