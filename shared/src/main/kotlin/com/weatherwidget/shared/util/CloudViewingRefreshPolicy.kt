package com.weatherwidget.shared.util

/**
 * When cloud data is stale enough to warrant a refresh *while the user is looking at it*.
 *
 * The cloud graph refreshes on the slow full-forecast cadence (60–90+ min), while temperature gets
 * a frequent current-only fetch — so the cloud curve freezes for an hour or more even when the user
 * is actively viewing it. This policy is the "the user is looking at it" half of the fix: a short
 * staleness threshold that the platform watchdogs (Android's cloud-view render path, desktop's
 * screen-on observation loop) check to decide whether to fetch now instead of waiting for the slow
 * loop.
 *
 * Shared by Android and desktop so the two cannot drift on the number.
 */
object CloudViewingRefreshPolicy {

    /**
     * Cloud data older than this is "stale while viewing" and worth a targeted refresh.
     *
     * Cloud is hourly-resolution data, so 15 minutes keeps the actual curve at most ~1 h behind
     * "now" while bounding the refresh rate to at most ~4×/h during active viewing. Shorter barely
     * helps (the upstream value only changes on the hour) and longer reads as frozen at the graph's
     * right edge.
     */
    const val CLOUD_STALE_WHILE_VIEWING_MS = 15 * 60 * 1000L

    /**
     * True when [latestDataAtMs] is present and older than the viewing threshold.
     *
     * Absent (`null`) is **not** stale: it means "no cloud data yet", which is a different
     * condition handled by the missing-data / backfill paths — conflating the two would turn a
     * genuinely-empty source into a fetch-every-paint loop.
     */
    fun isStale(latestDataAtMs: Long?, nowMs: Long): Boolean {
        val at = latestDataAtMs ?: return false
        return nowMs - at > CLOUD_STALE_WHILE_VIEWING_MS
    }
}
