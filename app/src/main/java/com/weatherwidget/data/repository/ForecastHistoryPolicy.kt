package com.weatherwidget.data.repository

/**
 * Cadence policy for forecast-history snapshots.
 *
 * Forecast history (both the daily [com.weatherwidget.data.local.ForecastEntity] timeline and the
 * hourly [com.weatherwidget.data.local.HourlyForecastHistoryEntity]) is captured at most once per
 * bucket: every 4h for the primary API source, every 8h for non-primary sources. The bucket is a
 * floor of wall-clock time, so all fetches within the same window collapse to one snapshot.
 *
 * Pure (no Android deps) so it can be unit-tested directly. Callers pass the primary source id —
 * the first entry of [com.weatherwidget.widget.WidgetStateManager.getVisibleSourcesOrder].
 */
object ForecastHistoryPolicy {
    const val PRIMARY_BUCKET_MS = 4L * 60L * 60L * 1000L      // 4 hours
    const val NON_PRIMARY_BUCKET_MS = 8L * 60L * 60L * 1000L  // 8 hours

    /** Bucket width for [sourceId]: 4h if it is the primary source, otherwise 8h. */
    fun bucketMs(sourceId: String, primarySourceId: String): Long =
        if (sourceId == primarySourceId) PRIMARY_BUCKET_MS else NON_PRIMARY_BUCKET_MS

    /** Floor of [nowMs] to the start of [sourceId]'s current snapshot bucket. */
    fun snapshotBucket(nowMs: Long, sourceId: String, primarySourceId: String): Long {
        val width = bucketMs(sourceId, primarySourceId)
        return (nowMs / width) * width
    }
}
