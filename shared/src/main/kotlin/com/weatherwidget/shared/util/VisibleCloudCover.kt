package com.weatherwidget.shared.util

import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.model.ObservationReading

/**
 * What "how cloudy is it" means everywhere the app reports cloud: the **total** column.
 *
 * Reverses the 2026-08-20 decision to prefer `cloudCoverLow`. That decision was measured and, on
 * its own terms, right — the total ran 83-99% all afternoon on thin cirrus while the low layer and
 * every surface station read 4-13%, so drawing the total claimed an overcast nobody could see. What
 * it missed is the opposite error, which is larger and more frequent. Measured 2026-08-27 over the
 * 720 stored Open-Meteo hours carrying both values: the total exceeds low by >= 30 points on 125 of
 * them (17.4%), and on 93 (12.9%) low reads under 20 while the total is at or above 70 — the curve
 * painting a clear sky over a covered one. Mean gap 15.5 points, maximum 100.
 *
 * Existing in ONE place is the point. Twelve call sites each spelled `cloudCoverLow ?: cloudCover`
 * by hand; a preference expressed twelve times drifts, and the forecast curve answering a different
 * question from the actual curve is exactly the divergence the accuracy claim cannot survive.
 */
object VisibleCloudCover {

    /**
     * The total where the row has one; otherwise the maximum of whichever bands it does have.
     *
     * The band fallback is not a guess. Station sources (NWS, Aviation Weather, Synoptic) store no
     * total by design — a METAR reports cumulative layers, not a total-column percentage — so their
     * bands hold the whole report and `max` across them is precisely what
     * [com.weatherwidget.shared.observations.MetarSkyCover.totalPercent] computes from the same
     * layers. Without it the forecast curve would show a total while the actual curve showed a low
     * layer, and the two would no longer be answering the same question.
     *
     * Null in means not reported and null out. A missing value must never become a zero: that would
     * be an observation of a clear sky nobody made. A total of `0`, by contrast, IS a report, and
     * wins over any band exactly as any other total does.
     */
    fun of(total: Int?, low: Int? = null, mid: Int? = null, high: Int? = null): Int? {
        val resolved = total ?: listOfNotNull(low, mid, high).maxOrNull()
        return resolved?.coerceIn(0, 100)
    }

    /** The value the cloud graph draws for a forecast hour. */
    fun HourlyForecast.visibleCloudCover(): Int? =
        of(total = cloudCover, low = cloudCoverLow, mid = cloudCoverMid, high = cloudCoverHigh)

    /** The value the cloud graph draws for an observation. */
    fun ObservationReading.visibleCloudCover(): Int? =
        of(total = cloudCover, low = cloudCoverLow, mid = cloudCoverMid, high = cloudCoverHigh)
}
