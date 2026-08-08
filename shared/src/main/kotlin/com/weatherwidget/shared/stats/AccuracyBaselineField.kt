package com.weatherwidget.shared.stats

/**
 * Which pair of temperatures on the resolved `daily_history` row counts as "the actual" when
 * scoring forecast accuracy. Chosen by the user on the statistics screen; persisted as
 * `accuracy_baseline_field` in `weather_prefs`.
 *
 * Orthogonal to [ActualsBaselineResolver], which chooses *whose row* to read. This chooses which
 * field on it.
 */
enum class AccuracyBaselineField(val prefValue: String) {
    /**
     * `apiHighTemp`/`apiLowTemp` — the source's own past-weather product. For NWS that is the
     * nearest official station's raw daily max/min ([com.weatherwidget.shared.actuals.StationDailyExtremes]);
     * for Open-Meteo, ERA5. Default: no interpolation and no forecast-derived component.
     */
    NATIVE_ACTUAL("native_actual"),

    /**
     * `computedHighTemp`/`computedLowTemp` — the IDW blend interpolated to the user's coordinates
     * ("Location actual"). Closer to the weather at your address, but partly forecast-extrapolated
     * across station gaps.
     */
    BLENDED_LOCATION("blended_location"),
    ;

    companion object {
        val DEFAULT = NATIVE_ACTUAL

        fun fromPrefValue(value: String?): AccuracyBaselineField =
            entries.firstOrNull { it.prefValue == value } ?: DEFAULT
    }
}

/** The actual high/low a day contributes, plus whether it had to fall back off the chosen field. */
data class ResolvedBaselineTemps(
    val high: Float,
    val low: Float,
    /**
     * True when [AccuracyBaselineField.NATIVE_ACTUAL] was requested but the row had no native
     * actual, so the blend was used for this day. Surfaced per-row in the UI: a mixed-provenance
     * average that does not say so is a number that lies about what it measured.
     */
    val fellBackToBlend: Boolean,
)

/**
 * Picks the actual temperatures off one resolved row.
 *
 * [NATIVE_ACTUAL][AccuracyBaselineField.NATIVE_ACTUAL] degrades to the blend for a day whose native
 * actual is missing — a station that failed the coverage guard, or a date older than the retained
 * observations. Dropping those days instead would silently shrink the window; the flag lets the UI
 * disclose the mix rather than hide it.
 */
fun resolveBaselineTemps(
    field: AccuracyBaselineField,
    computedHigh: Float,
    computedLow: Float,
    apiHigh: Float?,
    apiLow: Float?,
): ResolvedBaselineTemps =
    when (field) {
        AccuracyBaselineField.BLENDED_LOCATION ->
            ResolvedBaselineTemps(computedHigh, computedLow, fellBackToBlend = false)
        AccuracyBaselineField.NATIVE_ACTUAL ->
            if (apiHigh != null && apiLow != null) {
                ResolvedBaselineTemps(apiHigh, apiLow, fellBackToBlend = false)
            } else {
                ResolvedBaselineTemps(computedHigh, computedLow, fellBackToBlend = true)
            }
    }
