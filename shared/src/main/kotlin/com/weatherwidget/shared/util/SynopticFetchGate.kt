package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.FetchOutcome

/** Where a platform keeps the [SynopticFetchGate]'s state: Android prefs, a desktop file. */
interface SynopticBackoffStore {
    fun failStreak(): Int
    fun backoffUntilMs(): Long
    fun save(failStreak: Int, backoffUntilMs: Long)
}

/**
 * The one Synoptic backoff state machine, shared by Android and desktop: skip while a failure's
 * [SynopticBackoff] window runs, clear it on any server answer, escalate it on a failure.
 *
 * Log rows (tag → meaning) are the same on both platforms so one `app_logs` query reads either:
 * `SYNOPTIC_FETCH_BACKOFF_SKIP`, `SYNOPTIC_FETCH_BACKOFF_BYPASS`, `SYNOPTIC_FETCH_BACKOFF_SET`.
 */
class SynopticFetchGate(
    private val store: SynopticBackoffStore,
    private val log: suspend (tag: String, message: String, level: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs [fetch] unless the backoff says to skip; returns null when skipped. [context] is
     * prepended to every log row ("reason=full_sync tier=PRIMARY").
     */
    suspend fun <T> run(
        context: String,
        userLocationChange: Boolean = false,
        fetch: suspend () -> FetchOutcome<T>,
    ): FetchOutcome<T>? {
        val now = nowMs()
        val until = store.backoffUntilMs()
        val active = now < until
        if (active) {
            val state = "streak=${store.failStreak()} backoffRemainingMin=${(until - now) / 60_000}"
            if (SynopticBackoff.shouldSkip(now, until, userLocationChange)) {
                log("SYNOPTIC_FETCH_BACKOFF_SKIP", "$context $state", "DEBUG")
                return null
            }
            log("SYNOPTIC_FETCH_BACKOFF_BYPASS", "$context cause=location_change $state", "INFO")
        }
        val outcome = fetch()
        when (outcome) {
            // A server answer of any kind proves the quota/network is back; clear the streak.
            is FetchOutcome.Success, is FetchOutcome.NoData -> store.save(0, 0L)
            is FetchOutcome.Failed -> {
                // The fetcher already logged SYNOPTIC_FETCH_FAIL; this row tracks the escalating
                // backoff so a long outage is visible from app_logs alone.
                val streak = store.failStreak() + 1
                val backoffMs = SynopticBackoff.backoffFor(streak)
                store.save(streak, now + backoffMs)
                log(
                    "SYNOPTIC_FETCH_BACKOFF_SET",
                    "$context streak=$streak backoffMin=${backoffMs / 60_000} error=${outcome.reason}",
                    "DEBUG",
                )
            }
        }
        return outcome
    }
}
