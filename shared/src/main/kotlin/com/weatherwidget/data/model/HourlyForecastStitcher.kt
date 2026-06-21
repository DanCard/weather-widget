package com.weatherwidget.data.model

import com.weatherwidget.data.local.LocationMatch

/**
 * Merges the live hourly rows (`hourly_forecasts`, latest-only, REPLACE-overwritten) with the
 * as-predicted snapshots (`hourly_forecast_history`) into one forecast per hour, shared by Android
 * (`GraphDataLoader`) and desktop (`DesktopWeatherDao.getHourlyWithHistory`).
 *
 * Two rules, split at [nowMs]:
 *  - **Current / future hours:** the live row wins (freshest forecast). History only backfills
 *    nullable fields the live row is missing (e.g. NWS near-term has no skyCover).
 *  - **Past hours:** the *original prediction* wins — the earliest snapshot for that hour — so the
 *    graph keeps "what was forecast" instead of NWS's REPLACE-overwritten hindsight revision.
 *
 * Both sides first collapse same-site fragments via [LocationMatch.sameSite]: float-keyed rows that
 * GPS jitter splits into ~10 cm-apart silos are merged, and genuinely-different neighbouring markers
 * (farther than the same-site tolerance) are dropped, so the result is deterministic and identical
 * across devices for the live portion of the line.
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

        // Live line: freshest same-site row per hour. Original prediction: earliest same-site snapshot.
        val currentByTime = collapse(current, centerLat, centerLon) { rows -> rows.maxByOrNull { it.fetchedAt } }
        val originalByTime = collapse(history, centerLat, centerLon) { rows -> rows.minByOrNull { it.fetchedAt } }

        val times = (currentByTime.keys + originalByTime.keys).toSortedSet()
        return times.mapNotNull { time ->
            val live = currentByTime[time]
            val original = originalByTime[time]
            when {
                time < nowMs && original != null ->
                    original.copy(
                        cloudCover = original.cloudCover ?: live?.cloudCover,
                        precipProbability = original.precipProbability ?: live?.precipProbability,
                        precipAmountMm = original.precipAmountMm ?: live?.precipAmountMm,
                    )
                live != null ->
                    live.copy(
                        cloudCover = live.cloudCover ?: original?.cloudCover,
                        precipProbability = live.precipProbability ?: original?.precipProbability,
                        precipAmountMm = live.precipAmountMm ?: original?.precipAmountMm,
                    )
                else -> original
            }
        }
    }

    /**
     * Collapse rows to one per hour: keep same-site rows (relative to the centre), choose the
     * temperature/condition source via [pick] (freshest for live, earliest for history), and
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
