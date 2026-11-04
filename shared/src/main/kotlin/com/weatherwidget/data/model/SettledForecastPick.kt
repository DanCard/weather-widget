package com.weatherwidget.data.model

/**
 * Which of an hour's forecast rows stands for that hour — the one rule shared by
 * [HourlyForecastStitcher], `DailyNoonCloudCover`, `CurrentTemperatureResolver`, and
 * `HistorySnapshotRetention` (which must keep exactly the rows this reads).
 *
 * **Every hour, started or not, takes the newest fetch.** A fetch made during or after the hour may
 * be a hindcast; it still wins. Until 2026-11-04 a started hour froze at the newest fetch at or
 * before it ("Freeze wins always", 2026-10-27), so a device asleep overnight kept its evening
 * forecast — desktop drew NWS 3am as 4% beside its own 03:10 fetch's 25%. User, 2026-11-04: "lets
 * get rid of the freeze rule", everywhere. See plans/261104-remove-hourly-pre-hour-freeze.md.
 *
 * Synthesized rows (`fetchedAt = 0`) lose to any real fetch. Sorts are stable, so on a `fetchedAt`
 * tie the row listed first wins (the stitcher lists live rows first).
 */
object SettledForecastPick {

    fun <T> pick(rows: List<T>, fetchedAt: (T) -> Long): T? = rows.maxByOrNullStable(fetchedAt)

    /**
     * Every row the rule may read for this hour, best first: [pick] is the head, and a null field on
     * it borrows from the first row below that carries one — the next-newest fetch. NWS near-term
     * hours carry no skyCover, so the fill comes from the nearest-in-time fetch rather than whatever
     * row the query happened to list first.
     */
    fun <T> backfillOrder(rows: List<T>, fetchedAt: (T) -> Long): List<T> = rows.sortedByDescending(fetchedAt)

    fun pick(rows: List<HourlyForecast>): HourlyForecast? = pick(rows) { it.fetchedAt }

    fun backfillOrder(rows: List<HourlyForecast>): List<HourlyForecast> = backfillOrder(rows) { it.fetchedAt }

    /** First row with the maximum key, so ties keep list order like the stable sort above. */
    private inline fun <T> List<T>.maxByOrNullStable(key: (T) -> Long): T? {
        var best: T? = null
        var bestKey = Long.MIN_VALUE
        for (row in this) {
            val k = key(row)
            if (best == null || k > bestKey) {
                best = row
                bestKey = k
            }
        }
        return best
    }
}
