package com.weatherwidget.shared.observations

/**
 * What the stations list says about a single observation row: where the reading came from, or — more
 * usefully — that it is no longer being used at all.
 *
 * A station that stopped reporting hours ago kept showing its origin ("OFFICIAL (API)") as though it
 * were still contributing, while the blend had long since dropped it. [Kind.STALE] says so instead.
 *
 * The staleness cutoff is not a display choice: both blend estimators decay a station's weight
 * linearly to zero at [BLEND_MAX_AGE_MS], so a reading at or beyond that age contributes nothing to
 * the temperature the user sees. Keeping the constant here — and having the estimators read it —
 * is what stops the badge from drifting away from the blend it describes.
 *
 * Distinct from [ObservationFallbackPolicy.STALE_AFTER_MS] (1h), which decides when to *re-fetch* a
 * lagging station from the web source. A station can be stale enough to re-fetch but still fresh
 * enough to blend.
 */
object ObservationOrigin {

    /**
     * A reading at least this old carries zero weight in the blend.
     *
     * @see com.weatherwidget.shared.util.SpatialInterpolator
     * @see com.weatherwidget.shared.actuals.ActualTemperatureSeriesBuilder
     */
    const val BLEND_MAX_AGE_MS = 3 * 60 * 60 * 1000L

    enum class Kind {
        /** Upstream QC rejected the reading; the value is bogus and excluded from the blend. */
        QC_FAILED,

        /** Station has not reported recently enough to carry any weight in the blend. */
        STALE,

        /** Fetched from the Synoptic web source after the API's value was missing or stale. */
        WEB,

        /** Fetched from the provider API. */
        API,
    }

    /** True when [timestampMs] (the reading's own time) is too old to carry weight in the blend. */
    fun isStale(timestampMs: Long, nowMs: Long): Boolean = nowMs - timestampMs >= BLEND_MAX_AGE_MS

    /**
     * QC failure outranks staleness: a rejected reading is the more specific explanation, and it was
     * never a blend input at any age.
     */
    fun of(
        timestampMs: Long,
        qcFailed: Boolean,
        isWebFallback: Boolean,
        nowMs: Long,
    ): Kind = when {
        qcFailed -> Kind.QC_FAILED
        isStale(timestampMs, nowMs) -> Kind.STALE
        isWebFallback -> Kind.WEB
        else -> Kind.API
    }
}
