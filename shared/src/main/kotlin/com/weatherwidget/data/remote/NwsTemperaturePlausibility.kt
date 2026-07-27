package com.weatherwidget.data.remote

/**
 * Range gate for NWS-supplied temperatures, plus the record of what it rejected.
 *
 * NWS leaks an internal missing-data sentinel of -100°F through its public API. On 2026-07-27 it
 * surfaced simultaneously as `temperature: -100` on the "Tonight" period of `/forecast` and as
 * `-73.33333333333333` degC — exactly -100.0°F — in the `/gridpoints` minTemperature series. Both
 * of those feed the daily low, and both "fill nulls only", so filtering one path alone just lets
 * the other supply identical garbage.
 *
 * One such value corrupts the whole widget rather than its own cell: the daily graph scales its
 * y-axis to the data range, so a single -100 collapsed all seven days into a sliver.
 *
 * The bounds sit outside every temperature NWS legitimately reports (US record low -79.8°F at
 * Prospect Creek, Alaska, 1971; record high 134°F at Death Valley, 1913) while still rejecting the
 * sentinel with ~20°F of margin. Rejected slots are left null so the hourly repair path can fill
 * them from NWS's own clean hourly series — see `NwsForecastMapper.fillTemperatureGapsFromHourly`.
 */
object NwsTemperaturePlausibility {

    const val MIN_PLAUSIBLE_F = -80f
    const val MAX_PLAUSIBLE_F = 140f

    /** NaN/infinity fail this too — `?:` and null checks do not guard against NaN. */
    fun isPlausibleF(tempF: Float): Boolean =
        tempF.isFinite() && tempF >= MIN_PLAUSIBLE_F && tempF <= MAX_PLAUSIBLE_F
}

/**
 * Read-side counterpart to the ingest gate: treats an implausible *stored* temperature as missing.
 *
 * The ingest filter only protects rows written from now on. Rows already persisted keep their
 * sentinel, and paths that deliberately read older rows — notably the desktop's previous-forecast
 * snapshot overlay, which skips the newest batch on purpose — will keep rendering them. A sentinel
 * reaching a renderer is worse than a gap: it drags bar geometry and axis scaling off-screen while
 * the label alongside shows a healthy number.
 */
fun Float?.orNullIfImplausibleTempF(): Float? =
    if (this != null && NwsTemperaturePlausibility.isPlausibleF(this)) this else null

/**
 * A temperature the plausibility gate refused, carrying the window it covered so the hourly repair
 * can recompute the true value over exactly the same span (preserving NWS's own convention, where
 * a day's low belongs to the night that *ends* that morning).
 */
data class RejectedNwsTemperature(
    /** Diagnostic origin, e.g. `GRID:min` or `FCST:Tonight`. */
    val origin: String,
    /** Calendar day the value would have been filed under. */
    val dateString: String,
    /** True if this was a daily high, false for a daily low. */
    val isMax: Boolean,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val rawValueF: Float,
) {
    fun describe(): String =
        "$origin date=$dateString ${if (isMax) "high" else "low"}=$rawValueF"
}
