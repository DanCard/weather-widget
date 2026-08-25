package com.weatherwidget.shared.observations

/**
 * Shared policy for the NWS current-observation secondary leg and the older historical Synoptic
 * substitution. Current fetch-both uses token-free Aviation Weather METARs; historical repair can
 * still consult Synoptic when configured. One station limit and one validity boundary keep Android
 * and desktop from drifting.
 */
object ObservationFallbackPolicy {

    /**
     * The Synoptic (web) fallback is a heavier out-of-band fetch, so it is limited to the nearest N
     * stations by distance rather than every candidate station.
     */
    const val MAX_WEB_FALLBACK_STATIONS = 3

    // --- Fetch-first policy (plan 260721) ------------------------------------------------------
    // The latest-observation path no longer waits for the API to go stale before consulting the
    // web source: the web reading is frequently fresher than api.weather.gov (CDN cache +
    // NWS ingest lag), so for the nearest stations we fetch BOTH sources every data-fetch cycle
    // and display prefer-newest. Backfill paths are deliberately left on the old fallback gate.

    /** Master switch: false reverts the latest path to fallback-only in one line. */
    const val FETCH_BOTH_ENABLED = true

    /** Nearest N stations fetch BOTH sources every cycle and use prefer-newest for display. */
    const val WEB_FETCH_STATIONS = 3

    /**
     * Nearest N stations additionally fetch web purely to log the web-vs-API freshness metric
     * ([shouldLogWebMetrics]); stations in [WEB_FETCH_STATIONS, WEB_METRICS_STATIONS) never feed
     * their web reading into storage or the blend — they exist only to gather data on whether the
     * fetch-both set is worth widening past [WEB_FETCH_STATIONS].
     */
    const val WEB_METRICS_STATIONS = 5

    /**
     * Window requested for a metrics-only web fetch. We only need the single newest reading to
     * compare against the API, not history — but it must span more than one METAR cycle (~1h) or a
     * late/skipped ob leaves the window empty and the metric is blank (same trap as
     * [webFallbackWindowMinutes]). 90 min covers one missed cycle at negligible cost.
     */
    const val METRICS_WINDOW_MINUTES = 90L

    /** Tolerate small upstream/device clock skew, but never let a future report win freshness. */
    const val MAX_WEB_FUTURE_SKEW_MS = 5 * 60 * 1000L

    /** The nearest [WEB_FETCH_STATIONS] fetch both sources unconditionally (no staleness gate). */
    fun shouldFetchWeb(stationIndex: Int): Boolean =
        FETCH_BOTH_ENABLED && stationIndex < WEB_FETCH_STATIONS

    /** The nearest [WEB_METRICS_STATIONS] fetch web at least for the comparison metric. */
    fun shouldLogWebMetrics(stationIndex: Int): Boolean =
        FETCH_BOTH_ENABLED && stationIndex < WEB_METRICS_STATIONS
    // -------------------------------------------------------------------------------------------

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

    /**
     * Reason recorded on the fallback log line, so "station went silent" stays distinct from
     * "station lagged" — and both stay distinct from "we never got an answer".
     *
     * [apiFetchFailed] is the one that costs real debugging time when it is missing. The API call
     * site catches its exception and returns an empty list, so a timeout arrives here looking
     * exactly like a station with no data. Measured 2026-08-21: KNUQ's 72h window timed out at 30 s
     * on the emulator and logged `reason=empty`, which reads as "this station has no history" — it
     * had 196 observations, fetched in 1.6 s from a host on the same network. An infrastructure
     * failure must never be reported as a data condition.
     */
    fun fallbackReason(apiObservationCount: Int, apiFetchFailed: Boolean): String = when {
        apiFetchFailed -> "fetch_failed"
        apiObservationCount == 0 -> "empty"
        else -> "stale"
    }
}
