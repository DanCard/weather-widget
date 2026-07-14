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

    /**
     * Widest history the web fallback will request.
     *
     * Derived, not guessed. The window must reach the station's newest reading whenever we look at
     * it, so it has to cover the longest gap between two looks plus the span over which a reading
     * can still affect what's displayed:
     *
     *   BatteryTier.INTERVAL_MEDIUM_MINUTES (480, the longest scheduled fetch gap)
     *     + ObservationOrigin.BLEND_MAX_AGE_MS (180, the decay neighbourhood a reading still
     *       influences — measured against each candidate timestamp, NOT against now, so an aged-out
     *       reading is still baked into the past of the curve and into daily_history)
     *     + margin
     *   ≈ 11h  →  12h.
     *
     * A tighter cap silently re-creates the bug this policy exists to prevent: clamp below the
     * reading's own age and the web source returns nothing, so its QC flag is never seen and a bad
     * reading can never be healed. 3h would have missed KPAO itself (192 min stale); 6h fails a
     * phone on the 8h low-battery interval. [ObservationFallbackPolicyTest] pins this.
     *
     * The cap is close to a worst-case bound rather than a per-fetch cost: the window is
     * age + margin, so a station 70 min stale asks for ~130 min. Only a station silent longer than
     * the cap — or one we have never seen a reading from — actually requests the maximum.
     */
    const val MAX_WEB_FALLBACK_WINDOW_MINUTES = 12 * 60L

    /** Requested past the station's newest reading, so a reading right at the edge is still inside. */
    private const val WEB_FALLBACK_WINDOW_MARGIN_MINUTES = 60L

    /**
     * How far back to ask the web source for, given how stale the station is.
     *
     * The fallback used to request a flat 60 minutes — but it only fires once a station is already
     * more than [STALE_AFTER_MS] (60 min) stale, so the window was guaranteed to exclude the very
     * reading it existed to re-check. KPAO 2026-07-13: silent for 3h, the 60-minute request returned
     * "no stations found", the Synoptic QC flag on its bogus 50°F reading was never seen, and the
     * unflagged value stayed in the blend, dragging the graph's dot below every station's reading.
     * The window must therefore cover the staleness that triggered the fallback.
     *
     * A station with no known reading ([newestObservationMs] null) gets the full window — we have no
     * idea how far back its data starts.
     */
    fun webFallbackWindowMinutes(newestObservationMs: Long?, nowMs: Long): Long {
        if (newestObservationMs == null) return MAX_WEB_FALLBACK_WINDOW_MINUTES
        val ageMinutes = (nowMs - newestObservationMs).coerceAtLeast(0L) / 60_000L
        return (ageMinutes + WEB_FALLBACK_WINDOW_MARGIN_MINUTES)
            .coerceIn(WEB_FALLBACK_WINDOW_MARGIN_MINUTES, MAX_WEB_FALLBACK_WINDOW_MINUTES)
    }

    /** Reason recorded on the fallback log line, so "station went silent" stays distinct from "station lagged". */
    fun fallbackReason(apiObservationCount: Int): String =
        if (apiObservationCount == 0) "empty" else "stale"
}
