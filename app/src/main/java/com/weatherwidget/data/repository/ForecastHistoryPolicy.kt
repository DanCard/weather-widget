package com.weatherwidget.data.repository

/**
 * Cadence policy for forecast-history snapshots.
 *
 * Forecast history (both the daily [com.weatherwidget.data.local.ForecastEntity] timeline and the
 * hourly [com.weatherwidget.data.local.HourlyForecastHistoryEntity]) is captured at most once per
 * bucket: every 4h for a priority (currently-displayed) source, every 12h for non-priority
 * background sources. The bucket is a floor of wall-clock time, so all fetches within the same
 * window collapse to one snapshot.
 *
 * "Priority" = the set of sources currently shown across the user's widgets
 * ([com.weatherwidget.widget.WidgetStateManager.getActiveDisplaySourceIds]). Keying off the
 * displayed set (rather than the single global first-in-order source) means the source the user is
 * actually looking at always gets the fast lane, and the genuinely-background sources get the slow
 * lane — which is where the DB-write savings come from.
 *
 * Pure (no Android deps) so it can be unit-tested directly.
 */
object ForecastHistoryPolicy {
    const val PRIMARY_BUCKET_MS = 4L * 60L * 60L * 1000L       // 4 hours (priority/displayed source)
    const val NON_PRIMARY_BUCKET_MS = 12L * 60L * 60L * 1000L  // 12 hours (background sources)

    /** Bucket width for [sourceId]: 4h if it is a priority (displayed) source, otherwise 12h. */
    fun bucketMs(sourceId: String, prioritySourceIds: Set<String>): Long =
        if (sourceId in prioritySourceIds) PRIMARY_BUCKET_MS else NON_PRIMARY_BUCKET_MS

    /** Floor of [nowMs] to the start of [sourceId]'s current snapshot bucket. */
    fun snapshotBucket(nowMs: Long, sourceId: String, prioritySourceIds: Set<String>): Long {
        val width = bucketMs(sourceId, prioritySourceIds)
        return (nowMs / width) * width
    }
}
