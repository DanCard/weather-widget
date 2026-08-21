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
    /** The retro-corrected live row. Null for the current and future hours — nothing has happened yet. */
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
     * @param priorForecast day-ago predictions keyed by the same top-of-hour epoch ms.
     * @param nowMs "now"; hours strictly before the hour containing it are treated as past.
     */
    fun build(
        liveHours: List<HourlyForecast>,
        priorForecast: Map<Long, Int>,
        nowMs: Long,
    ): List<CloudPoint> {
        // The hour containing nowMs is still in progress: it has no settled actual and no useful
        // day-ago comparison yet, so it renders as a plain forecast point like the future ones.
        val currentHourStart = nowMs - Math.floorMod(nowMs, 3_600_000L)

        return liveHours
            .asSequence()
            .filter { it.cloudCover != null }
            .sortedBy { it.dateTime }
            .map { hour ->
                val live = hour.cloudCover?.coerceIn(0, 100)
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
                    actualCover = live,
                    isFrozen = frozen != null,
                )
            }
            .toList()
    }

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
