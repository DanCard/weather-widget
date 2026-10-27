package com.weatherwidget.data.model

/**
 * Which of an hour's forecast rows stands for that hour — the one rule shared by
 * [HourlyForecastStitcher], `DailyNoonCloudCover`, and `HistorySnapshotRetention` (which must keep
 * exactly the history rows this reads).
 *
 * - **Hour not yet started** (`hourMs >= nowMs`): the newest fetch, so the forecast keeps updating.
 * - **Hour started** (`hourMs < nowMs`): the newest fetch **at or before the hour**
 *   (`fetchedAt <= hourMs`), however old. Later fetches are hindcasts and model assimilations, and never replace the prediction that was in effect when the
 *   hour arrived (user, 2026-10-27: "Freeze wins always"). When none predates the hour (app
 *   installed afterwards), the **earliest** fetch stands in: the closest thing to a prediction, and
 *   deterministic.
 *
 * Every started hour follows the same rule, today or any earlier day, so a past hour never flips
 * at midnight; and each window stops moving once the hour passes, which is what makes pruning to
 * these picks safe. Synthesized rows (`fetchedAt = 0`) qualify as pre-hour but lose to any real
 * fetch. See plans/261027-pre-hour-freeze-retention-and-shared-pick.md.
 *
 * The current hour stays live because a jitter fragment's weeks-old long-range row is "pre-hour"
 * too: with only a post-hour row at the live fragment, freezing the hour in progress handed it the
 * stale 81.3 over the fresh 66.6 (`HourlyStaleFragmentCollapseTest`).
 */
object SettledForecastPick {

    fun <T> pick(rows: List<T>, hourMs: Long, nowMs: Long, fetchedAt: (T) -> Long): T? =
        backfillOrder(rows, hourMs, nowMs, fetchedAt).firstOrNull()

    /**
     * Every row the rule may read for this hour, best first: [pick] is the head, and a null field on
     * it borrows from the first row below that carries one. NWS near-term hours carry no skyCover,
     * so the fill must come from the nearest-in-time prediction rather than whatever row the query
     * happened to list first — a six-day-out snapshot, say. Sorts are stable, so on a `fetchedAt`
     * tie the row listed first wins (the stitcher lists live rows first).
     */
    fun <T> backfillOrder(rows: List<T>, hourMs: Long, nowMs: Long, fetchedAt: (T) -> Long): List<T> {
        if (hourMs >= nowMs) return rows.sortedByDescending(fetchedAt)
        val preHour = rows.filter { fetchedAt(it) <= hourMs }
        return if (preHour.isNotEmpty()) preHour.sortedByDescending(fetchedAt) else rows.sortedBy(fetchedAt)
    }

    fun pick(rows: List<HourlyForecast>, hourMs: Long, nowMs: Long): HourlyForecast? =
        pick(rows, hourMs, nowMs) { it.fetchedAt }

    fun backfillOrder(rows: List<HourlyForecast>, hourMs: Long, nowMs: Long): List<HourlyForecast> =
        backfillOrder(rows, hourMs, nowMs) { it.fetchedAt }
}
