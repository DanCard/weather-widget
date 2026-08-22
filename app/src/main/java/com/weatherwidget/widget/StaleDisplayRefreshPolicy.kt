package com.weatherwidget.widget

/**
 * Pure decision for "the user is looking at observations — are they stale enough to fetch fresh
 * ones automatically?"
 *
 * Born from the 2026-08-21 Samsung incident (plans/260821-observations-stale-site-autorefresh.md):
 * the Current Observations screen and the hourly-graph station label sat on 7:17 PM data for over
 * an hour while plugged in, because fresh rows had landed under a neighbouring location fragment
 * and repair only happened when the periodic charging loop happened to write the display's key.
 * The screen reloads from the DB whenever inserts land, but nothing ever *fetches* just because
 * what is being looked at is old.
 *
 * Two independent guards keep this from becoming a fetch loop:
 *  - [STALE_FETCH_THRESHOLD_MS] — act only when even the newest *fetch* is at least this old.
 *    A recent fetch with an old `timestamp` means the stations themselves are quiet; refetching
 *    cannot produce newer reports, so that case is a distinct skip ([Decision.QUIET_STATIONS])
 *    rather than a fire.
 *  - [TRIGGER_DEBOUNCE_MS] — after one automatic trigger, stay quiet for this long no matter how
 *    many reloads the insert-flow observer drives.
 *
 * Framework-free by design so thresholds are unit-testable without Robolectric.
 */
object StaleDisplayRefreshPolicy {

    /** Newest fetchedAt older than this = worth fetching again while someone is watching. */
    const val STALE_FETCH_THRESHOLD_MS: Long = 15 * 60 * 1000L

    /**
     * Reported-time lag beyond which stations are presumed quiet: a fetch younger than
     * [STALE_FETCH_THRESHOLD_MS] cannot beat this, so do not bother the network.
     */
    const val QUIET_STATIONS_LAG_MS: Long = 30 * 60 * 1000L

    /** Minimum spacing between automatic triggers, shared with ScreenOnReceiver's resample debounce scale. */
    const val TRIGGER_DEBOUNCE_MS: Long = 10 * 60 * 1000L

    enum class Decision(internal val outcomeToken: String) {
        /** Stale enough to act: resample location, then force-refresh the displayed source. */
        FIRE("fired"),

        /** Data was fetched recently enough — nothing to do. Not logged (the common case). */
        SKIP_FRESH("skipped_fresh"),

        /** Fetched recently but stations report nothing newer — refetching would not help. */
        QUIET_STATIONS("skipped_quiet_stations"),

        /** An automatic trigger already fired inside the debounce window. */
        RECENT_TRIGGER("skipped_recent_trigger"),

        /** Nothing is displayed, so there is no staleness to judge. */
        NO_ROWS("skipped_no_rows"),
    }

    fun evaluate(
        nowMs: Long,
        newestFetchedMs: Long?,
        newestReportedMs: Long?,
        lastTriggerMs: Long,
        staleThresholdMs: Long = STALE_FETCH_THRESHOLD_MS,
        quietStationsLagMs: Long = QUIET_STATIONS_LAG_MS,
        debounceMs: Long = TRIGGER_DEBOUNCE_MS,
    ): Decision {
        if (lastTriggerMs > 0 && nowMs - lastTriggerMs in 0 until debounceMs) {
            return Decision.RECENT_TRIGGER
        }
        if (newestFetchedMs == null || newestReportedMs == null) return Decision.NO_ROWS
        if (nowMs - newestFetchedMs < staleThresholdMs) {
            return if (nowMs - newestReportedMs >= quietStationsLagMs) {
                Decision.QUIET_STATIONS
            } else {
                Decision.SKIP_FRESH
            }
        }
        return Decision.FIRE
    }
}
