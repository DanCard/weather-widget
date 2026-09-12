package com.weatherwidget.widget

import com.weatherwidget.data.local.LocationMatch

/** Whether a forced refresh made at [requestedAtMs] has since been satisfied by a fetch at [lastSuccessMs]. */
internal object ForcedRefreshSatisfaction {
    fun isSatisfied(requestedAtMs: Long, lastSuccessMs: Long): Boolean =
        requestedAtMs > 0L && lastSuccessMs > requestedAtMs

    /**
     * The network fetch that most recently completed in this process — what a forced request that
     * queued behind it on `ForecastRepository.syncMutex` is compared against.
     *
     * [targetSourceId] is the request's target (null = every visible source); [sourceIds] is what
     * was actually fetched, which for an untargeted fetch excludes throttled non-primary sources.
     */
    data class CompletedFetch(
        val lat: Double,
        val lon: Double,
        val targetSourceId: String?,
        val sourceIds: Set<String>,
        val completedAtMs: Long,
    )

    /**
     * Same question as [isSatisfied], asked under the lock of the fetch that just released it.
     *
     * Completion time, not row stamps: `ForecastEntity.fetchedAt` is set when each entity is built
     * mid-fetch, so a request landing in the last few ms of a fetch is older than every row it
     * produced and would re-fetch (seen on the emulator 2026-09-12 14:46:47 — request 24 ms before
     * `NET_FETCH_COMPLETE`). Also unaffected by the snapshot store deduplicating unchanged rows.
     *
     * Site-scoped: a fetch for the site the phone just left must not satisfy a request for the one
     * it arrived at. Source-scoped: an untargeted request is satisfied only by an untargeted fetch;
     * a targeted one (source toggle) only if that source was actually fetched — an untargeted fetch
     * skips throttled non-primary sources, so "all sources" does not imply "this source".
     */
    fun isSatisfiedByCompletedFetch(
        requestedAtMs: Long,
        lastFetch: CompletedFetch?,
        lat: Double,
        lon: Double,
        targetSourceId: String?,
    ): Boolean {
        if (lastFetch == null || !isSatisfied(requestedAtMs, lastFetch.completedAtMs)) return false
        if (!LocationMatch.sameSite(lastFetch.lat, lastFetch.lon, lat, lon)) return false
        return if (targetSourceId == null) {
            lastFetch.targetSourceId == null
        } else {
            targetSourceId in lastFetch.sourceIds
        }
    }
}
