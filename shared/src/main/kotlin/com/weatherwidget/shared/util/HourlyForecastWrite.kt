package com.weatherwidget.shared.util

import com.weatherwidget.data.model.ElapsedForecastBackfill
import com.weatherwidget.data.model.HourlyForecast
import com.weatherwidget.data.remote.HourlyOnDemand

/**
 * One fetch's live hourly rows for one source at one site, as both platforms store them (user
 * 2026-10-24; `plans/261024-shared-forecast-persist-rules.md`):
 *
 * - Hours already elapsed ([ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS]) are not written: the live
 *   table keeps what was forecast for them (the elapsed-hour backfill files them as history instead).
 * - The fetch's horizon ([HourlyOnDemand.trim]) bounds the live rows and, shorter, the snapshot.
 * - A field this fetch lacks (cloud, chance, amount) keeps the stored row's value — a reply without
 *   sky cover never blanks it.
 * - Every hour written is stamped [nowMs]: `fetchedAt` means "last confirmed", which
 *   `GoogleHistoryRefill.staleHours` and the on-demand coverage read.
 *
 * The platform reads this source's live rows on the site's exact quantized key ([readRange]) and
 * writes [Plan.live] live and [Plan.snapshot] under the fetch's snapshot label.
 */
object HourlyForecastWrite {
    data class Plan(val live: List<HourlyForecast>, val snapshot: List<HourlyForecast>, val trimLine: String?)

    /** The hours [incoming] spans, for the platform's stored read; null when empty. */
    fun readRange(incoming: List<HourlyForecast>): LongRange? =
        if (incoming.isEmpty()) null else incoming.minOf { it.dateTime }..incoming.maxOf { it.dateTime }

    fun plan(
        incoming: List<HourlyForecast>,
        stored: List<HourlyForecast>,
        sourceId: String,
        nowMs: Long,
        keep: HourlyOnDemand.KeepUntil?,
    ): Plan {
        val upcoming = incoming.filter { it.dateTime >= nowMs - ElapsedForecastBackfill.ELAPSED_BOUNDARY_MS }
        val trim = keep?.let { HourlyOnDemand.trim(upcoming, it, nowMs, sourceId) { hour -> hour.dateTime } }
            ?: HourlyOnDemand.Trim(upcoming, upcoming, null)
        val storedByHour = stored.groupBy { it.dateTime }.mapValues { (_, rows) -> rows.maxBy { it.fetchedAt } }
        val live = trim.live.map { merge(storedByHour[it.dateTime], it).copy(source = sourceId, fetchedAt = nowMs) }
        val snapshotHours = trim.snapshot.mapTo(HashSet()) { it.dateTime }
        return Plan(live, live.filter { it.dateTime in snapshotHours }, trim.logMessage)
    }

    /** [new] with each nullable field it lacks taken from [existing]. */
    fun merge(existing: HourlyForecast?, new: HourlyForecast): HourlyForecast {
        if (existing == null) return new
        return new.copy(
            cloudCover = new.cloudCover ?: existing.cloudCover,
            cloudCoverLow = new.cloudCoverLow ?: existing.cloudCoverLow,
            cloudCoverMid = new.cloudCoverMid ?: existing.cloudCoverMid,
            cloudCoverHigh = new.cloudCoverHigh ?: existing.cloudCoverHigh,
            precipProbability = new.precipProbability ?: existing.precipProbability,
            precipAmountMm = new.precipAmountMm ?: existing.precipAmountMm,
        )
    }
}
