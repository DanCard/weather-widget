package com.weatherwidget.shared.graph

import com.weatherwidget.data.model.HourlyForecast

/**
 * One hour of the cloud graph, carrying both curves.
 *
 * [forecastCover] and [actualCover] answer different questions about the same hour, which is the
 * whole point: the forecast is what was predicted ~24h out, the actual is what Open-Meteo now says
 * happened after later runs assimilated observations.
 */
data class CloudPoint(
    val timeMs: Long,
    /** Frozen day-ago prediction for past hours; the live row for the current and future hours. */
    val forecastCover: Int?,
    /**
     * The low-cloud actual for this hour, read from `observations`. Drawn for **every** hour one was
     * filed for, the hour in progress included: a METAR is an instantaneous reading of the sky, so
     * the current hour's blend is measurement, not projection. Null only where nothing was filed —
     * which covers every future hour, because the read window stops at `now`.
     */
    val actualCover: Int?,
    /**
     * False when no day-ago prediction was available and [forecastCover] fell back to the live row.
     * That fallback is a hindcast wearing the forecast's clothes, so it must stay distinguishable —
     * for diagnostics, for tests, and so the render can decline to imply an accuracy comparison it
     * cannot actually make.
     */
    val isFrozen: Boolean,
)

/**
 * Pairs the live hourly rows with the frozen day-ago forecast
 * ([PriorDayCloudForecast]) into the cloud graph's point list. Shared by the Android widget and the
 * desktop app so the two cannot disagree about which value lands on which curve.
 *
 * Both platforms must pass [liveHours] already collapsed to one physical site — the callers' DAO
 * layer does this via `LocationMatch`/`selectNearestSite`. Feeding un-collapsed rows here mixes
 * GPS-jitter fragments and neighbouring towns into one curve.
 */
object CloudSeriesBuilder {

    /**
     * @param liveHours the source's hourly rows for the visible window, site-collapsed, one per hour.
     * @param priorForecast day-ago predictions keyed by top-of-hour epoch ms, from
     *   [PriorDayCloudForecast].
     * @param retroActual low-cloud actuals keyed by their native report timestamps, read from
     *   `observations`. Renderers draw this map independently from the hourly [CloudPoint] list;
     *   since the blend went binless (plans/260824-subhourly-metar-cloud-blend.md) keys land on
     *   real report times (:15/:35/:53), not hour marks — the per-hour [CloudPoint.actualCover]
     *   below is resolved by the nearest key within ±30 min, which is exactly the old bucket
     *   lookup for hourly-keyed maps. Authoritative and ungated otherwise: an hour draws an actual
     *   if and only if the series covers it. Nothing is inferred from `fetchedAt` any more.
     * @param nowMs "now". Used **only** to decide which hours get the frozen day-ago forecast; it
     *   has no say over the actual curve.
     */
    fun build(
        liveHours: List<HourlyForecast>,
        priorForecast: Map<Long, Int>,
        retroActual: Map<Long, Int>,
        nowMs: Long,
    ): List<CloudPoint> {
        // Only the FORECAST curve cares where "now" falls. A day-ago prediction is a comparison for
        // an hour that has already happened; the current and future hours want the live row instead.
        //
        // The ACTUAL curve gets no such gate. This used to null the actual for the hour containing
        // nowMs on the grounds that it was "still in progress", which cost the graph its rightmost
        // 1-2 hours: measured 2026-08-21 11:16, the NWS METAR blend had 11:00 = 65% ready from
        // KNUQ@10:55/KPAO@10:47, and the graph drew 10:00's 100% as its latest actual while the
        // marine layer was visibly breaking up. An observation is not "in progress" — it is a
        // measurement that already happened. Future hours need no gate either: observations cannot
        // exist for them, and both platforms' read windows stop at `now`.
        val currentHourStart = nowMs - Math.floorMod(nowMs, 3_600_000L)
        // Bit-mask-free nearest lookup: the map is a few thousand keys at most and this property
        // exists only for the hourly-aligned fallback render and the frozenCoverage diagnostic —
        // O(hours × keys) is paid nowhere hot.
        val actualByHour = retroActual.entries.sortedBy { it.key }

        return liveHours
            .asSequence()
            .filter { it.visibleCloudCover() != null }
            .sortedBy { it.dateTime }
            .map { hour ->
                val live = hour.visibleCloudCover()
                val frozen = if (hour.dateTime < currentHourStart) priorForecast[hour.dateTime] else null
                CloudPoint(
                    timeMs = hour.dateTime,
                    // No day-ago prediction for this hour: fall back to the live value so the curve
                    // stays continuous, but say so. Never present a hindcast as a frozen forecast.
                    forecastCover = frozen ?: live,
                    actualCover = nearestWithin(actualByHour, hour.dateTime)?.coerceIn(0, 100),
                    isFrozen = frozen != null,
                )
            }
            .toList()
    }

    /**
     * The value whose key is nearest [hourMs] within ±[TOLERANCE_MS], or null when the series has
     * nothing that close. For an exactly hour-keyed map this is precisely the old direct lookup.
     */
    private fun nearestWithin(
        sortedEntries: List<Map.Entry<Long, Int>>,
        hourMs: Long,
    ): Int? {
        val nearest = sortedEntries.minByOrNull { kotlin.math.abs(it.key - hourMs) } ?: return null
        return if (kotlin.math.abs(nearest.key - hourMs) <= TOLERANCE_MS) nearest.value else null
    }

    /** Same ±30-minute reach the blend's anchor tolerance grants (see MetarCloudBlender). */
    private const val TOLERANCE_MS = 30 * 60_000L

    /**
     * What the graph draws for an hour: the **low** layer where the row has it, else the total.
     *
     * The low layer is the one that answers "is it cloudy out", and it is what both the actual
     * series and the frozen day-ago forecast now carry — so the live curve must use it too, or the
     * curve steps at "now" whenever there is cirrus overhead. Measured 2026-08-20: the total column
     * ran 83-99% all afternoon on high cloud while the low layer read 6-13% and every surface
     * station reported clear.
     *
     * The fallback is for rows written before the column existed, and for sources that report only
     * a total. It keeps a pre-migration cache drawing something honest rather than a gap.
     */
    private fun HourlyForecast.visibleCloudCover(): Int? =
        (cloudCoverLow ?: cloudCover)?.coerceIn(0, 100)

    /**
     * Fraction of past points whose forecast is genuinely frozen, for the render-time diagnostic.
     * A low number means the prior-run fetch is failing or the window outruns what was stored —
     * both of which make the two curves collapse toward each other and the graph look broken.
     */
    fun frozenCoverage(points: List<CloudPoint>): Float {
        val past = points.count { it.actualCover != null }
        if (past == 0) return 1f
        return points.count { it.actualCover != null && it.isFrozen }.toFloat() / past
    }
}
