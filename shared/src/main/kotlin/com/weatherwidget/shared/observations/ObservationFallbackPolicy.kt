package com.weatherwidget.shared.observations

/**
 * When the NWS API's newest observation for a station is missing or stale, the station is re-fetched
 * from the Synoptic (web) source instead. Android and desktop each used to carry their own copy of
 * this rule with different station limits (3 vs 2), so a station at index 2 — KPAO, for this
 * location — got the web fallback on Android but never on desktop, leaving desktop pinned to the
 * stale API value. One policy, one limit, both platforms.
 */
object ObservationFallbackPolicy {

    /**
     * The Synoptic (web) fallback is a heavier out-of-band fetch, so it is limited to the nearest N
     * stations by distance rather than every candidate station.
     */
    const val MAX_WEB_FALLBACK_STATIONS = 3

    /** An API observation older than this is treated as stale and triggers the web fallback. */
    const val STALE_AFTER_MS = 60 * 60 * 1000L

    /**
     * [newestObservationMs] is the newest observation the NWS API returned for the station (null when
     * it returned nothing usable).
     */
    fun isStale(newestObservationMs: Long?, nowMs: Long): Boolean =
        newestObservationMs == null || newestObservationMs < nowMs - STALE_AFTER_MS

    /** [stationIndex] is the station's position in the distance-sorted station list. */
    fun shouldUseWebFallback(stationIndex: Int, newestObservationMs: Long?, nowMs: Long): Boolean =
        stationIndex < MAX_WEB_FALLBACK_STATIONS && isStale(newestObservationMs, nowMs)

    /** Reason recorded on the fallback log line, so "station went silent" stays distinct from "station lagged". */
    fun fallbackReason(apiObservationCount: Int): String =
        if (apiObservationCount == 0) "empty" else "stale"
}
