package com.weatherwidget.data.model

import com.weatherwidget.data.local.LocationMatch
import com.weatherwidget.shared.graph.PriorDayCloudForecast

/**
 * Merges the live hourly rows (`hourly_forecasts`, latest-only, REPLACE-overwritten) with the
 * snapshots (`hourly_forecast_history`) into one forecast per hour, shared by Android
 * (`GraphDataLoader`) and desktop (`DesktopWeatherDao.getHourlyWithHistory`).
 *
 * One rule for every hour, past and future: **the latest forecast wins.** The live row is the
 * freshest fetch, so it wins whenever present; the freshest history snapshot only backfills hours the
 * live set lacks (e.g. fully-past days that have aged out of the REPLACE-overwritten live table) plus
 * nullable fields the live row is missing (e.g. NWS near-term has no skyCover).
 *
 * Earlier this picked the *earliest* snapshot for past hours to show "what was originally forecast",
 * but that surfaced the 6–7-day-out long-range prediction — the least accurate forecast NWS ever
 * published for that hour — making the past line wildly disagree with reality and diverge across
 * devices by history depth. The as-predicted / accuracy comparison lives in the dedicated Forecast
 * History view, not here. [nowMs] is retained for call-site compatibility but no longer splits the line.
 *
 * Both sides first collapse same-site fragments via [LocationMatch.sameSite]: float-keyed rows that
 * GPS jitter splits into ~10 cm-apart silos are merged, and genuinely-different neighbouring markers
 * (farther than the same-site tolerance) are dropped, so the result is deterministic and identical
 * across devices.
 */
object HourlyForecastStitcher {
    /**
     * Rows the merge must never consider, whatever the caller passed.
     *
     * [PriorDayCloudForecast.SOURCE_ID] rows live in `hourly_forecast_history` beside the app's own
     * snapshots, but they describe a *deliberately stale* prediction (~24h before the hour) while
     * carrying a fresh `fetchedAt`. Freshest-wins would therefore promote one to "the latest
     * forecast" for any hour the live table has dropped — changing the temperature and precipitation
     * lines, which read this same stitched list.
     *
     * Every production caller happens to scope its history query to one real source today, so this
     * is belt-and-braces. It is here rather than in the callers because the failure is silent and
     * the next caller to add an all-sources read would not know to look.
     */
    private fun List<HourlyForecast>.withoutSyntheticSources(): List<HourlyForecast> =
        filter { it.source != PriorDayCloudForecast.SOURCE_ID }

    fun stitch(
        current: List<HourlyForecast>,
        history: List<HourlyForecast>,
        nowMs: Long,
        centerLat: Double,
        centerLon: Double,
    ): List<HourlyForecast> {
        @Suppress("NAME_SHADOWING") val current = current.withoutSyntheticSources()
        @Suppress("NAME_SHADOWING") val history = history.withoutSyntheticSources()
        if (current.isEmpty() && history.isEmpty()) return emptyList()

        // Freshest same-site row per hour on both sides — the latest forecast, regardless of past/future.
        val currentByTime = collapse(current, centerLat, centerLon) { rows -> rows.maxByOrNull { it.fetchedAt } }
        val historyByTime = collapse(history, centerLat, centerLon) { rows -> rows.maxByOrNull { it.fetchedAt } }

        val times = (currentByTime.keys + historyByTime.keys).toSortedSet()
        return times.mapNotNull { time ->
            val live = currentByTime[time]
            val fallback = historyByTime[time]
            // Live (freshest fetch) wins for every hour; history fills hours live lacks and any
            // nullable fields the live row is missing.
            live?.copy(
                cloudCover = live.cloudCover ?: fallback?.cloudCover,
                cloudCoverLow = live.cloudCoverLow ?: fallback?.cloudCoverLow,
                cloudCoverMid = live.cloudCoverMid ?: fallback?.cloudCoverMid,
                cloudCoverHigh = live.cloudCoverHigh ?: fallback?.cloudCoverHigh,
                precipProbability = live.precipProbability ?: fallback?.precipProbability,
                precipAmountMm = live.precipAmountMm ?: fallback?.precipAmountMm,
            ) ?: fallback
        }
    }

    /**
     * Multi-source variant of [stitch], for loaders that read several sources in one query (the
     * display source of every installed widget plus `GENERIC_GAP`).
     *
     * [stitch] collapses to **one row per hour**, so handing it rows from several sources at once
     * silently drops every source but the freshest one for that hour. This stitches each source
     * independently and concatenates, so per-hour freshness selection still happens — just scoped
     * within a source, which is the only comparison that means anything.
     */
    fun stitchBySource(
        current: List<HourlyForecast>,
        history: List<HourlyForecast>,
        nowMs: Long,
        centerLat: Double,
        centerLon: Double,
    ): List<HourlyForecast> {
        @Suppress("NAME_SHADOWING") val current = current.withoutSyntheticSources()
        @Suppress("NAME_SHADOWING") val history = history.withoutSyntheticSources()
        if (current.isEmpty() && history.isEmpty()) return emptyList()
        val currentBySource = current.groupBy { it.source }
        val historyBySource = history.groupBy { it.source }
        val sources = currentBySource.keys + historyBySource.keys
        if (sources.size <= 1) return stitch(current, history, nowMs, centerLat, centerLon)
        return sources.flatMap { source ->
            stitch(
                current = currentBySource[source].orEmpty(),
                history = historyBySource[source].orEmpty(),
                nowMs = nowMs,
                centerLat = centerLat,
                centerLon = centerLon,
            )
        }.sortedBy { it.dateTime }
    }

    /**
     * Collapse rows to one per hour: keep same-site rows (relative to the centre), choose the
     * temperature/condition source via [pick] (freshest fetch on both the live and history sides), and
     * coalesce nullable fields across the remaining same-site rows so e.g. an NWS snapshot that
     * dropped skyCover on the near-term hour still inherits it from a bucket that carried it.
     */
    private fun collapse(
        rows: List<HourlyForecast>,
        centerLat: Double,
        centerLon: Double,
        pick: (List<HourlyForecast>) -> HourlyForecast?,
    ): Map<Long, HourlyForecast> =
        rows.groupBy { it.dateTime }
            .mapNotNull { (time, hourRows) ->
                val sameSite = hourRows.filter { row ->
                    val lat = row.locationLat
                    val lon = row.locationLon
                    lat == null || lon == null || LocationMatch.sameSite(centerLat, centerLon, lat, lon)
                }.ifEmpty { hourRows }
                val base = pick(sameSite) ?: return@mapNotNull null
                time to base.copy(
                    cloudCover = base.cloudCover ?: sameSite.firstNotNullOfOrNull { it.cloudCover },
                    cloudCoverLow = base.cloudCoverLow ?: sameSite.firstNotNullOfOrNull { it.cloudCoverLow },
                    cloudCoverMid = base.cloudCoverMid ?: sameSite.firstNotNullOfOrNull { it.cloudCoverMid },
                    cloudCoverHigh = base.cloudCoverHigh ?: sameSite.firstNotNullOfOrNull { it.cloudCoverHigh },
                    precipProbability = base.precipProbability ?: sameSite.firstNotNullOfOrNull { it.precipProbability },
                    precipAmountMm = base.precipAmountMm ?: sameSite.firstNotNullOfOrNull { it.precipAmountMm },
                )
            }
            .toMap()
}
