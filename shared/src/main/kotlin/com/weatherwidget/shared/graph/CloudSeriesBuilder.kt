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
     * The retro-corrected low-cloud actual for a past hour, read from [RetroCloudActual]. Null for
     * the current and future hours — nothing has happened yet — and for any past hour no actual was
     * ever filed for.
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
     * @param retroActual settled low-cloud actuals keyed the same way, from [RetroCloudActual].
     *   Authoritative: a past hour draws an actual if and only if it appears here. Nothing is
     *   inferred from `fetchedAt` any more — that inference silently evaluated to "never" on
     *   Android, see [RetroCloudActual].
     * @param nowMs "now"; hours strictly before the hour containing it are treated as past.
     */
    fun build(
        liveHours: List<HourlyForecast>,
        priorForecast: Map<Long, Int>,
        retroActual: Map<Long, Int>,
        nowMs: Long,
    ): List<CloudPoint> {
        // The hour containing nowMs is still in progress: it has no settled actual and no useful
        // day-ago comparison yet, so it renders as a plain forecast point like the future ones.
        val currentHourStart = nowMs - Math.floorMod(nowMs, 3_600_000L)

        return liveHours
            .asSequence()
            .filter { it.visibleCloudCover() != null }
            .sortedBy { it.dateTime }
            .map { hour ->
                val live = hour.visibleCloudCover()
                val isPast = hour.dateTime < currentHourStart
                if (!isPast) {
                    return@map CloudPoint(
                        timeMs = hour.dateTime,
                        forecastCover = live,
                        actualCover = null,
                        isFrozen = false,
                    )
                }
                val frozen = priorForecast[hour.dateTime]
                CloudPoint(
                    timeMs = hour.dateTime,
                    // No day-ago prediction for this hour: fall back to the live value so the curve
                    // stays continuous, but say so. Never present a hindcast as a frozen forecast.
                    forecastCover = frozen ?: live,
                    actualCover = retroActual[hour.dateTime]?.coerceIn(0, 100),
                    isFrozen = frozen != null,
                )
            }
            .toList()
    }

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
     * True when [fetchedAt] postdates the end of the hour starting at [hourStartMs] — the only
     * condition under which a fetched value has been revised in light of what happened.
     *
     * **Write-side predicate.** [RetroCloudActual.qualifyingActuals] applies it to a payload the
     * moment it arrives, where the fetch time genuinely settles the question. It used to be applied
     * at render time to a stored row's `fetchedAt` instead, which asked a different and much weaker
     * question — "has this device refetched since the hour ended?" — whose answer is structurally
     * "no" on Android. See [RetroCloudActual] for the measurements.
     *
     * `fetchedAt <= 0` means the caller did not populate it; treated as not corrected, because a
     * missing actual is honest and a fabricated one is not.
     */
    fun isRetroCorrected(hourStartMs: Long, fetchedAt: Long): Boolean =
        fetchedAt > 0L && fetchedAt >= hourStartMs + 3_600_000L

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
