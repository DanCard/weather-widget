package com.weatherwidget.shared.util

/**
 * A reported **trace** of rain: precipitation fell, but less than the smallest gauge reading
 * (0.01 in = 0.254 mm). METAR writes it `P0000`; api.weather.gov's JSON writes the same report as
 * `precipitationLastHour = 0`, indistinguishable from a dry hour. Stored as 0.0 it drew nothing —
 * a shower at KSJC on 2026-10-21 left the NWS actuals blank (plans/261021-observed-rain-trace.md).
 */
object PrecipTrace {
    /**
     * Stored for one reported trace. Positive, so every "did it rain?" (> 0) check sees rain; far
     * below anything a gauge measures, so totals that mix traces with real amounts stay honest.
     */
    const val MM = 0.001f

    /** An observed total below this holds traces only (50 trace hours sum to 0.05 mm). */
    const val MAX_MM = 0.05f

    const val LABEL = "Trace"

    fun isTrace(amountMm: Float?): Boolean = amountMm != null && amountMm > 0f && amountMm < MAX_MM
}
