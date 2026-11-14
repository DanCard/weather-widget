package com.weatherwidget.data.model

/**
 * Which of an hour's forecast rows stands for that hour — the one rule shared by
 * [HourlyForecastStitcher], `DailyNoonCloudCover`, `CurrentTemperatureResolver`, and
 * `HistorySnapshotRetention` (which must keep every row this can read, for any [HindcastGuard] age).
 *
 * - **Hour not yet started** (`hourMs >= nowMs`): the newest fetch, so the forecast keeps updating.
 * - **Hour started**, with `T` = [HindcastGuard.maxPriorAgeMs]:
 *   1. the newest fetch at or before the hour, if it is at most `T` older than the hour;
 *   2. otherwise (no prior, or only a stale one) the **earliest** fetch after the hour starts — the
 *      closest thing to a prediction, and it stays put as more hindcasts arrive;
 *   3. otherwise (only a stale prior) that prior.
 *   `T = 0` turns the guard off: the newest fetch, as from 2026-11-04 to 2026-11-13.
 *
 * User, 2026-11-13: "don't allow hindcasts unless prior data is missing". The 2026-10-27 freeze had
 * no age limit, so a stale prior won: desktop asleep 19:33–03:10 drew NWS 3am as its 18:45 4% beside
 * its own 03:10 fetch's 25%, and a jitter fragment's weeks-old row beat a fresh one. Both now count
 * as missing. See plans/261113-no-hindcasts-unless-prior-missing.md.
 *
 * "Made at" is the provider's issuance time when known (NWS `updateTime`), else the fetch time
 * (plans/261114-nws-issuance-time-prior.md): a 03:10 fetch of a 02:30 issuance is a prior for 3 am.
 *
 * Synthesized rows (`fetchedAt = 0`) are pre-hour but always stale, so they lose to any later fetch.
 * Sorts are stable, so on a `fetchedAt` tie the row listed first wins (the stitcher lists live rows
 * first).
 */
object SettledForecastPick {

    fun <T> pick(
        rows: List<T>,
        hourMs: Long,
        nowMs: Long,
        maxPriorAgeMs: Long = HindcastGuard.maxPriorAgeMs,
        fetchedAt: (T) -> Long,
    ): T? = backfillOrder(rows, hourMs, nowMs, maxPriorAgeMs, fetchedAt).firstOrNull()

    /**
     * Every row the rule may read for this hour, best first: [pick] is the head, and a null field on
     * it borrows from the first row below that carries one. NWS near-term hours carry no skyCover,
     * so the fill comes from the nearest-in-time fetch rather than whatever row the query happened
     * to list first. For a started hour: the chosen side first (pre-hour newest-first or post-hour
     * earliest-first), then the other side in its own order.
     */
    fun <T> backfillOrder(
        rows: List<T>,
        hourMs: Long,
        nowMs: Long,
        maxPriorAgeMs: Long = HindcastGuard.maxPriorAgeMs,
        fetchedAt: (T) -> Long,
    ): List<T> {
        if (hourMs >= nowMs || maxPriorAgeMs <= 0) return rows.sortedByDescending(fetchedAt)
        val pre = rows.filter { fetchedAt(it) <= hourMs }.sortedByDescending(fetchedAt)
        val post = rows.filter { fetchedAt(it) > hourMs }.sortedBy(fetchedAt)
        val freshPrior = pre.firstOrNull()?.let { hourMs - fetchedAt(it) <= maxPriorAgeMs } == true
        return if (freshPrior || post.isEmpty()) pre + post else post + pre
    }

    /** Rows compete on [HourlyForecast.madeAt]: the issuance when the provider sends one (NWS), else the fetch. */
    fun pick(rows: List<HourlyForecast>, hourMs: Long, nowMs: Long): HourlyForecast? =
        pick(rows, hourMs, nowMs) { it.madeAt }

    fun backfillOrder(rows: List<HourlyForecast>, hourMs: Long, nowMs: Long): List<HourlyForecast> =
        backfillOrder(rows, hourMs, nowMs) { it.madeAt }
}
