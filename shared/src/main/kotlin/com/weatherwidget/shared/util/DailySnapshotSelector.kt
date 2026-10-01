package com.weatherwidget.shared.util

/**
 * Selects the forecast snapshot that represents the prediction "as of ~24h ago" for the
 * today column's left bar. Pure Kotlin, no platform dependencies (sibling of
 * [DailyDayValueResolver]). Shared by the Android widget and the desktop app so both
 * platforms pick the same snapshot.
 *
 * Callers pre-filter for validity (e.g. both temps present, matching source) before calling.
 */
object DailySnapshotSelector {
    const val PRIOR_WINDOW_HOURS = 24L

    /**
     * A real "yesterday's forecast" is 24–48h old. Older (a device that fetched nothing for days)
     * is still SHOWN — the user chose that over hiding it — but drawn dashed
     * ([com.weatherwidget.shared.graph.StandInBarStyle]).
     */
    const val STALE_AFTER_HOURS = 48L

    /**
     * @param lastConfirmedAtMillis When a fetch last returned this row's values — NOT when the row was
     *   first written. Android deduplicates unchanged re-fetches (no new row; only `batchFetchedAt` is
     *   re-stamped), so there it is `batchFetchedAt`; `fetchedAt` would age an unchanging forecast into
     *   "stale" while it is being re-confirmed. Desktop writes a row per fetch, so its `fetchedAt` is
     *   already the last confirmation.
     */
    fun isStale(lastConfirmedAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis - lastConfirmedAtMillis > STALE_AFTER_HOURS * 3_600_000L

    /**
     * Forecast "as of ~24h ago": the most-recent candidate whose [fetchedAt] is older than
     * `nowMillis - 24h`, falling back to the earliest available candidate when none are old
     * enough. Mirrors the Android DailyViewLogic today-snapshot selection.
     *
     * @param candidates Already-validity-filtered snapshot candidates.
     * @param nowMillis Current time, epoch millis.
     * @param fetchedAt Accessor for a candidate's fetch time (epoch millis).
     */
    fun <T> selectPriorDaySnapshot(
        candidates: List<T>,
        nowMillis: Long,
        fetchedAt: (T) -> Long,
    ): T? {
        if (candidates.isEmpty()) return null
        val cutoff = nowMillis - PRIOR_WINDOW_HOURS * 3_600_000L
        return candidates.filter { fetchedAt(it) < cutoff }.maxByOrNull(fetchedAt)
            ?: candidates.minByOrNull(fetchedAt)
    }
}
