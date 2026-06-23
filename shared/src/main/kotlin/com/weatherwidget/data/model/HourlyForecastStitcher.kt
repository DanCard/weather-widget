package com.weatherwidget.data.model

import com.weatherwidget.data.local.LocationMatch

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
    fun stitch(
        current: List<HourlyForecast>,
        history: List<HourlyForecast>,
        nowMs: Long,
        centerLat: Double,
        centerLon: Double,
    ): List<HourlyForecast> {
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
                precipProbability = live.precipProbability ?: fallback?.precipProbability,
                precipAmountMm = live.precipAmountMm ?: fallback?.precipAmountMm,
            ) ?: fallback
        }
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
                    precipProbability = base.precipProbability ?: sameSite.firstNotNullOfOrNull { it.precipProbability },
                    precipAmountMm = base.precipAmountMm ?: sameSite.firstNotNullOfOrNull { it.precipAmountMm },
                )
            }
            .toMap()
}
