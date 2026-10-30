package com.weatherwidget.shared.graph

import kotlin.math.abs

/**
 * The hourly graph's ghost line: the forecast shifted by the resolved delta. The delta is always
 * [com.weatherwidget.widget.CurrentTemperatureResolver]'s `appliedDelta`, measured on one forecast
 * run. Never re-derive it from the graph's series: that series is stitched across runs, so the slope
 * between two hours can be a forecast revision (plans/261030-one-forecast-delta-all-views.md).
 */
object ForecastDeltaLine {
    /** Below this the ghost line would sit on the forecast line, so it is not drawn. */
    const val MIN_GHOST_DELTA = 0.1f

    /** No delta means the bare forecast. */
    fun effectiveDelta(appliedDelta: Float?): Float = appliedDelta?.takeUnless { it.isNaN() } ?: 0f

    fun expectedTemps(forecastTemps: List<Float>, appliedDelta: Float?): List<Float> {
        val delta = effectiveDelta(appliedDelta)
        return forecastTemps.map { it + delta }
    }

    fun drawsGhost(appliedDelta: Float?): Boolean =
        appliedDelta != null && abs(appliedDelta) >= MIN_GHOST_DELTA
}
