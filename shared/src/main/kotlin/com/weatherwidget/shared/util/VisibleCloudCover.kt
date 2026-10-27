package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading
import kotlin.math.roundToInt

/**
 * What "how cloudy is it" means everywhere the app reports cloud.
 *
 * Computes a weighted sum of low (50%), mid (25%), and high (25%) cloud layers whenever vertical
 * bands are present. When bands are absent, falls back to the total column.
 *
 * Existing in ONE place is the point. Call sites go through this resolver so the forecast curve,
 * actual curve, daily bars, and text displays answer the same question.
 */
object VisibleCloudCover {

    private const val LOW_WEIGHT = 0.50f
    private const val MID_WEIGHT = 0.25f
    private const val HIGH_WEIGHT = 0.25f

    /**
     * Resolves cloud cover (0–100) using a weighted sum of available bands (50% low, 25% mid, 25% high),
     * normalizing across present bands. When no bands are reported, falls back to [total].
     *
     * Null in means not reported and null out. A missing value must never become a zero: that would
     * be an observation of a clear sky nobody made. A report of `0`, by contrast, IS a report.
     */
    fun of(total: Int?, low: Int? = null, mid: Int? = null, high: Int? = null): Int? {
        val bands = listOfNotNull(
            low?.let { LOW_WEIGHT to it.toFloat() },
            mid?.let { MID_WEIGHT to it.toFloat() },
            high?.let { HIGH_WEIGHT to it.toFloat() },
        )
        val resolved = if (bands.isNotEmpty()) {
            val totalWeight = bands.sumOf { it.first.toDouble() }.toFloat()
            val weightedSum = bands.sumOf { (it.first * it.second).toDouble() }.toFloat()
            (weightedSum / totalWeight).roundToInt()
        } else {
            total
        }
        return resolved?.coerceIn(0, 100)
    }

    /** The value the cloud graph draws for a forecast hour. */
    fun HourlyForecast.visibleCloudCover(): Int? =
        of(total = cloudCover, low = cloudCoverLow, mid = cloudCoverMid, high = cloudCoverHigh)

    /** The value the cloud graph draws for an observation. */
    fun ObservationReading.visibleCloudCover(): Int? =
        of(total = cloudCover, low = cloudCoverLow, mid = cloudCoverMid, high = cloudCoverHigh)
}
