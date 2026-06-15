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
