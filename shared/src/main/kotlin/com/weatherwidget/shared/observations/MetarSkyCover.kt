package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.util.Log

/**
 * Maps a METAR sky-condition layer list to a cloud percent, for the NWS actual cloud curve.
 *
 * The percent is derived from the [amount] codes alone — never from `base`. `base` only decides
 * whether a layer belongs to the low-layer read: a METAR ceilometer can see nothing above ~12,000 ft,
 * and a `CLR` report still carries a base (the detection ceiling, e.g. 3810 m). Keying percent on
 * base would read that clear sky as "cloud at 3810 m".
 *
 * METAR amounts are cumulative, so the sky cover of a report is the **maximum** amount across its
 * layers — `FEW010 SCT020 BKN040` is BKN (broken) overall, not a sum.
 */
object MetarSkyCover {
    private const val TAG = "MetarSkyCover"

    // WMO okta midpoints. Measured 2026-08-20: swapping to okta lower bounds (12/38/63) or to a
    // linear n/8 scale (25/50/75) moves MAE against Open-Meteo's low layer by <1.5 points over 64
    // hours. The choice is not load-bearing; the midpoints are the standard, so they win.
    private val PERCENT = mapOf(
        "CLR" to 0, "SKC" to 0, "NCD" to 0, "CAVOK" to 0,
        "FEW" to 19, "SCT" to 44, "BKN" to 75, "OVC" to 100,
        "VV" to 100,   // sky obscured — vertical visibility only
    )

    // Roughly the ≈6,500 ft low-cloud convention; 2000 m vs 3000 m moved MAE by 0.2 points.
    const val LOW_LAYER_CEILING_M = 2_000.0

    // One WARN per never-before-seen amount code, so a new abbreviation surfaces loudly instead of
    // silently reading as clear. SynchronizedSet is fine here: this fires at most a handful of times
    // per process, never on a hot path.
    private val warnedAmounts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun percentFor(amount: String): Int? = PERCENT[amount]

    private fun isClearCode(amount: String): Boolean = PERCENT[amount] == 0

    /**
     * Total sky cover (the maximum amount across all layers), or null when the report carries no
     * usable sky condition — an empty list means "not reported", and an unrecognised amount means
     * "we cannot interpret this report", never "clear".
     */
    fun totalPercent(layers: List<NwsApi.CloudLayer>): Int? {
        if (layers.isEmpty()) return null
        var max: Int? = null
        for (layer in layers) {
            val pct = percentFor(layer.amount) ?: run {
                warnUnknown(layer.amount)
                return null
            }
            max = maxOf(max ?: Int.MIN_VALUE, pct)
        }
        return max
    }

    /**
     * Low-layer sky cover: the maximum amount among layers below [LOW_LAYER_CEILING_M].
     *
     * A clear-sky code contributes 0 even when its base sits at the ceilometer limit (a `CLR` at
     * 3810 m must not leave the low layer "unknown"), so the low layer reads 0 whenever the sky is
     * reported clear. Layers with an unknown (null) base are included in the low layer — there is
     * no basis for excluding them, and dropping them would hide real low cloud.
     */
    fun lowPercent(layers: List<NwsApi.CloudLayer>): Int? {
        if (layers.isEmpty()) return null
        var lowMax: Int? = null
        for (layer in layers) {
            val pct = percentFor(layer.amount) ?: run {
                warnUnknown(layer.amount)
                return null
            }
            // Clear codes are admitted to the low layer regardless of base (the base is the
            // ceilometer's detection ceiling, e.g. 3810 m, not a cloud — §2.3), and carry the
            // mapped 0. Cloud layers enter the low layer when below the ceiling or when their
            // height is unknown (dropping an unknown-height layer would hide real low cloud).
            val base = layer.baseMeters
            if (isClearCode(layer.amount) || base == null || base < LOW_LAYER_CEILING_M) {
                lowMax = maxOf(lowMax ?: Int.MIN_VALUE, pct)
            }
        }
        return lowMax
    }

    private fun warnUnknown(amount: String) {
        if (warnedAmounts.add(amount)) {
            Log.w(TAG, "unrecognised METAR sky amount='$amount'; report treated as unknown cloud cover")
        }
    }
}
