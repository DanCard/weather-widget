package com.weatherwidget.shared.util

import com.weatherwidget.data.remote.FetchOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a platform keeps the [SynopticFetchGate]'s state: Android prefs, a desktop file. */
interface SynopticBackoffStore {
    fun failStreak(): Int
    fun backoffUntilMs(): Long
    fun save(failStreak: Int, backoffUntilMs: Long)
}

/**
 * The one Synoptic backoff state machine, shared by Android and desktop: skip while a failure's
 * [SynopticBackoff] window runs, clear it on any server answer, escalate it on a rejection.
 *
 * Also owns the two cheap "don't send it again" rules that stop overlapping syncs from turning one
 * stall into two failures (or one success into two downloads):
 *  - **Single-flight** — while a fetch for the same site is running, a second caller awaits that
 *    result instead of sending its own. One request, one outcome, one backoff step.
 *  - **Freshness floor** — skip when a completed fetch for this site finished under [FRESHNESS_FLOOR_MS]
 *    ago. The 2026-10-03 16:49:30 / 16:49:37 pair re-downloaded the full radius response 7 s apart.
 *
 * Log rows (tag → meaning) are the same on both platforms so one `app_logs` query reads either:
 * `SYNOPTIC_FETCH_BACKOFF_SKIP`, `SYNOPTIC_FETCH_BACKOFF_BYPASS`, `SYNOPTIC_FETCH_BACKOFF_SET`,
 * `SYNOPTIC_FETCH_FRESH_SKIP`, `SYNOPTIC_FETCH_JOINED`.
 *
 * Those two rules only work if every caller shares one [Flights]. Pass a process-wide instance when
 * the gate itself is short-lived — on Android it is rebuilt for every `WeatherWidgetWorker` run, and
 * a per-gate default left the overlapping `periodic_60m` / `on_update_stale` pair with empty state.
 */
class SynopticFetchGate(
    private val store: SynopticBackoffStore,
    private val log: suspend (tag: String, message: String, level: String) -> Unit,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val flights: Flights = Flights(),
) {
    /** In-memory single-flight and freshness state; share one across gates for the same process. */
    class Flights {
        internal val mutex = Mutex()
        internal val inFlight = mutableMapOf<String, CompletableDeferred<FetchOutcome<*>?>>()
        internal val lastCompletedMs = mutableMapOf<String, Long>()
    }

    private val mutex get() = flights.mutex
    private val inFlight get() = flights.inFlight
    private val lastCompletedMs get() = flights.lastCompletedMs

    /**
     * Runs [fetch] unless the backoff or a fresh/recent fetch says to skip; returns null when
     * skipped. [context] is prepended to every log row ("reason=full_sync tier=PRIMARY").
     * [siteKey] scopes the single-flight and freshness floor to one location.
     */
    suspend fun <T> run(
        context: String,
        userLocationChange: Boolean = false,
        siteKey: String = "",
        fetch: suspend () -> FetchOutcome<T>,
    ): FetchOutcome<T>? {
        val now = nowMs()

        // Freshness floor: a completed fetch moments ago already has the data this caller wants.
        if (!userLocationChange) {
            val lastCompleted = mutex.withLock { lastCompletedMs[siteKey] } ?: 0L
            val ageMs = now - lastCompleted
            if (lastCompleted != 0L && ageMs < FRESHNESS_FLOOR_MS) {
                log(
                    "SYNOPTIC_FETCH_FRESH_SKIP",
                    "$context site=$siteKey ageSec=${ageMs / 1000}",
                    "DEBUG",
                )
                return null
            }
        }

        // Single-flight: join an in-flight fetch for this site rather than issuing a twin request.
        val owned: CompletableDeferred<FetchOutcome<*>?>?
        val joined: CompletableDeferred<FetchOutcome<*>?>?
        mutex.withLock {
            val existing = inFlight[siteKey]
            if (existing != null) {
                owned = null
                joined = existing
            } else {
                val deferred = CompletableDeferred<FetchOutcome<*>?>()
                inFlight[siteKey] = deferred
                owned = deferred
                joined = null
            }
        }
        if (joined != null) {
            log("SYNOPTIC_FETCH_JOINED", "$context site=$siteKey", "DEBUG")
            @Suppress("UNCHECKED_CAST")
            return joined.await() as FetchOutcome<T>?
        }

        try {
            val outcome = runWithBackoff(now, context, userLocationChange, fetch)
            if (outcome is FetchOutcome.Success || outcome is FetchOutcome.NoData) {
                mutex.withLock { lastCompletedMs[siteKey] = nowMs() }
            }
            owned!!.complete(outcome)
            return outcome
        } catch (e: Throwable) {
            owned!!.complete(null)
            throw e
        } finally {
            // NonCancellable: a cancelled owner (WorkManager stop) must still release the slot. A
            // leaked entry holds a completed `null`, so every later caller would "join" it and skip
            // Synoptic for this site until the process died.
            withContext(NonCancellable) { mutex.withLock { inFlight.remove(siteKey) } }
        }
    }

    private suspend fun <T> runWithBackoff(
        now: Long,
        context: String,
        userLocationChange: Boolean,
        fetch: suspend () -> FetchOutcome<T>,
    ): FetchOutcome<T>? {
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
                // The fetcher already logged SYNOPTIC_FETCH_FAIL; this row tracks the backoff so
                // a long outage is visible from app_logs alone.
                val failureClass = FailureClass.of(outcome.reason)
                when (failureClass) {
                    // Transport: fixed 5-minute wait, no streak escalation. A stall is not a
                    // rejection; doubling would leave Synoptic dark for an hour after one blip.
                    FailureClass.TRANSPORT -> {
                        val backoffMs = SynopticBackoff.TRANSPORT_BACKOFF_MS
                        // Never shorten a longer rejection backoff a location-change bypass ran through.
                        store.save(store.failStreak(), maxOf(store.backoffUntilMs(), now + backoffMs))
                        log(
                            "SYNOPTIC_FETCH_BACKOFF_SET",
                            "$context class=transport backoffMin=${backoffMs / 60_000} " +
                                "streak=${store.failStreak()} error=${outcome.reason}",
                            "DEBUG",
                        )
                    }
                    // Rejection: the 30-min-to-6-h doubling built for quota/auth outages.
                    FailureClass.REJECTION -> {
                        val streak = store.failStreak() + 1
                        val backoffMs = SynopticBackoff.backoffFor(streak)
                        store.save(streak, now + backoffMs)
                        log(
                            "SYNOPTIC_FETCH_BACKOFF_SET",
                            "$context class=rejection streak=$streak backoffMin=${backoffMs / 60_000} " +
                                "error=${outcome.reason}",
                            "DEBUG",
                        )
                    }
                }
            }
        }
        return outcome
    }

    companion object {
        /**
         * How long a completed fetch keeps the next one idle. Two full syncs 7 s apart (the
         * 16:49:30 / 16:49:37 pair) are the same request twice; a sync 10 min later is not.
         */
        const val FRESHNESS_FLOOR_MS = 2 * 60 * 1000L

        /** Stable per-site key for the single-flight map and freshness floor. */
        fun siteKey(latitude: Double, longitude: Double): String =
            "%.3f,%.3f".format(latitude, longitude)
    }
}
