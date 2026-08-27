package com.weatherwidget.shared.observations

import com.weatherwidget.data.remote.NwsApi
import com.weatherwidget.shared.util.Log
import kotlin.math.roundToInt

/**
 * Maps a METAR sky-condition layer list to a cloud percent, for the NWS actual cloud curve.
 *
 * The percent is derived from the [amount] codes alone — never from `base`. `base` only decides
 * whether a layer belongs to a vertical band. Automated ceilometers have a finite detection range,
 * but decoded METAR/Synoptic products can also contain higher reported layers; retain any explicit
 * layer rather than assuming it cannot exist. A `CLR` report may still carry a detection-limit base
 * (e.g. 3810 m), which is not a cloud height.
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

    // Align observed bands with Open-Meteo's documented forecast bands so the values are graphable
    // on the same axes. The previous 2 km aviation convention changed measured MAE by only 0.2 pt.
    const val LOW_LAYER_CEILING_M = 3_000
    const val MID_LAYER_CEILING_M = 8_000

    data class Band(
        val coverPercent: Int,
        val baseMeters: Int?,
    )

    data class VerticalProfile(
        val low: Band?,
        val mid: Band?,
        val high: Band?,
    )

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
     * Converts cumulative reported layers into graph-aligned low/middle/high bands.
     *
     * Each band retains the maximum cumulative amount reported in it and the lowest base at which
     * that maximum occurs. Provider heights are rounded before classification. Clear codes always
     * describe the low band with a null base because any attached height is a detection limit, not
     * cloud. A cloudy layer with an unreadable/invalid base keeps the legacy conservative low-band
     * behavior but cannot be inferred into middle or high.
     *
     * Returns null for an empty list or when any amount is unrecognised; partially interpreting an
     * unknown report could silently understate its cover.
     */
    fun verticalProfile(layers: List<NwsApi.CloudLayer>): VerticalProfile? {
        if (layers.isEmpty()) return null
        var low: Band? = null
        var mid: Band? = null
        var high: Band? = null

        for (layer in layers) {
            val pct = percentFor(layer.amount) ?: run {
                warnUnknown(layer.amount)
                return null
            }
            val roundedBase = layer.baseMeters
                ?.takeIf { it.isFinite() && it >= 0.0 }
                ?.roundToInt()
            val candidate = Band(
                coverPercent = pct,
                baseMeters = if (isClearCode(layer.amount)) null else roundedBase,
            )
            when {
                isClearCode(layer.amount) || roundedBase == null -> low = preferBand(low, candidate)
                roundedBase < LOW_LAYER_CEILING_M -> low = preferBand(low, candidate)
                roundedBase < MID_LAYER_CEILING_M -> mid = preferBand(mid, candidate)
                else -> high = preferBand(high, candidate)
            }
        }
        return VerticalProfile(low = low, mid = mid, high = high)
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
        return verticalProfile(layers)?.low?.coverPercent
    }

    private fun preferBand(current: Band?, candidate: Band): Band = when {
        current == null -> candidate
        candidate.coverPercent > current.coverPercent -> candidate
        candidate.coverPercent < current.coverPercent -> current
        current.baseMeters == null -> candidate.takeIf { it.baseMeters != null } ?: current
        candidate.baseMeters == null -> current
        candidate.baseMeters < current.baseMeters -> candidate
        else -> current
    }

    private fun warnUnknown(amount: String) {
        if (warnedAmounts.add(amount)) {
            Log.w(TAG, "unrecognised METAR sky amount='$amount'; report treated as unknown cloud cover")
        }
    }
}
