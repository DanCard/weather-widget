package com.weatherwidget.widget.handlers

/**
 * Whether a detected observation gap may request a backfill now.
 *
 * The 30-minute cooldown means "a backfill **ran** for this site recently" (started its fetch,
 * whatever the outcome), not "one was asked for".
 * It used to be stamped at enqueue, so any path that dropped the queued work — an instrumented test
 * process owning the app when it came due (`doWork` returns success under testing mode), a killed
 * process, a cancelled worker — left a known gap unrepaired for the full 30 minutes while every check
 * logged `reason=cooldown`. Seen on the emulator 2026-10-09: a 9-hour NWS gap after boot, request
 * enqueued at 17:41:12, consumed by a test run at 17:41:29, never retried.
 * See plans/261009-observation-backfill-cooldown-starts-when-it-runs.md.
 *
 * Pure; the WorkManager lookup is passed in so the rule is testable without it.
 */
internal object HourlyBackfillGate {

    data class Decision(val request: Boolean, val reason: String)

    /**
     * @param requestedAtMs the widget's last request stamp for this site (0 = never).
     * @param attemptedAtMs when a backfill for the site last STARTED its fetch (0 = never); the worker
     *   stamps it before the network call, so an attempt that throws or is killed still counts and a
     *   crashing backfill cannot be re-requested on every repaint. Only work that never reached the
     *   fetch reads as dropped.
     * @param attemptTracked false for paths whose work never stamps an attempt (WeatherAPI's
     *   provider-history refresh): those keep the request-time cooldown.
     * @param isPending whether a backfill is ENQUEUED or RUNNING; consulted only for a recent
     *   request with no attempt since, so the common paths never touch WorkManager.
     */
    suspend fun decide(
        nowMs: Long,
        cooldownMs: Long,
        requestedAtMs: Long,
        attemptedAtMs: Long,
        attemptTracked: Boolean,
        isPending: suspend () -> Boolean,
    ): Decision {
        if (attemptTracked && within(nowMs, attemptedAtMs, cooldownMs)) {
            return Decision(false, "cooldown attemptedAgoMin=${(nowMs - attemptedAtMs) / 60_000L}")
        }
        if (!within(nowMs, requestedAtMs, cooldownMs)) return Decision(true, "due")
        if (!attemptTracked) return Decision(false, "cooldown")
        if (attemptedAtMs >= requestedAtMs) {
            // Attempted after the request but outside the cooldown — cannot happen while the
            // request is itself inside it; kept explicit so the order of checks is not load-bearing.
            return Decision(true, "due")
        }
        return if (isPending()) {
            Decision(false, "pending requestedAgoMin=${(nowMs - requestedAtMs) / 60_000L}")
        } else {
            // Asked for, not queued, never started its fetch: the work was dropped. Ask again.
            Decision(true, "dropped requestedAgoMin=${(nowMs - requestedAtMs) / 60_000L}")
        }
    }

    /**
     * Cheap pre-check for callers that would otherwise load a large observation window just to reach
     * [decide] (CLOUD view, daily history probe). True only when [decide] is certain to skip without
     * a WorkManager lookup; a recent request with no attempt since returns false so the full
     * evaluation can tell "pending" from "dropped".
     */
    fun certainlyCoolingDown(
        nowMs: Long,
        cooldownMs: Long,
        requestedAtMs: Long,
        attemptedAtMs: Long,
        attemptTracked: Boolean,
    ): Boolean =
        if (attemptTracked) {
            within(nowMs, attemptedAtMs, cooldownMs)
        } else {
            within(nowMs, requestedAtMs, cooldownMs)
        }

    private fun within(nowMs: Long, stampMs: Long, cooldownMs: Long): Boolean {
        if (stampMs <= 0L) return false
        val elapsed = nowMs - stampMs
        return elapsed in 0 until cooldownMs
    }
}
