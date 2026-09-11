package com.weatherwidget.widget

import androidx.annotation.VisibleForTesting

/**
 * The post-start quiet period during which background sync work yields to the user.
 *
 * A process that starts because someone is looking at a widget (onUpdate, an action tap, a package
 * replacement, a locale change) has two jobs in its first seconds: paint every widget from cache,
 * and answer the taps that follow. On a debuggable build it does both interpreted, with an empty JIT
 * profile. Measured 2026-09-10 on the Pixel 7 Pro after an install: the WorkManager re-run of a
 * killed forced sync (50 s), an opportunistic fetch and three cache paints per widget all started in
 * the same second as the first tap, and taps then drained one per 8 s watchdog for over a minute
 * (`performance/260910-post-install-cold-start-storm.md`).
 *
 * This is the ordering rule that stops that: a fixed cooldown from process start, extended while
 * the user keeps interacting, during which every non-UI-only worker run is deferred.
 *
 * **Keyed on a user-facing trigger, not on process age alone.** WorkManager and JobScheduler
 * cold-start the process *to run the job*, so process age is ~100 ms on precisely those runs; an
 * age-only guard is what silently killed the on-battery refresh in August
 * (`opportunistic_job_startup_grace_self_defeating`). Until a user-facing trigger has been seen in
 * this process there is no cooldown at all.
 *
 * Time is injected (`elapsedRealtime`-style milliseconds) so the rule is unit-testable.
 */
internal class StartupCooldown(
    private val processStartElapsedMs: Long,
    private val cooldownMs: Long = COOLDOWN_MS,
    private val interactionQuietMs: Long = INTERACTION_QUIET_MS,
) {
    @Volatile
    private var userFacingTriggerSeen = false

    @Volatile
    private var endsAtElapsedMs = processStartElapsedMs + cooldownMs

    /**
     * A user-facing trigger reached the process: onUpdate, a widget action, a package replacement.
     * Extends the cooldown so it ends no sooner than [interactionQuietMs] after the trigger — but
     * only while the cooldown is still running. A tap that arrives after it has lapsed does not
     * restart it: the process is warm by then, and a sync alongside a tap is the ordinary case.
     */
    fun onUserFacingTrigger(nowElapsedMs: Long) {
        userFacingTriggerSeen = true
        if (nowElapsedMs <= endsAtElapsedMs) {
            endsAtElapsedMs = maxOf(endsAtElapsedMs, nowElapsedMs + interactionQuietMs)
        }
    }

    /**
     * Milliseconds a background run must wait before starting, or 0 to run now. Always 0 until a
     * user-facing trigger has been seen (see the class comment for why).
     */
    fun remainingMs(nowElapsedMs: Long): Long =
        if (!userFacingTriggerSeen) 0L else (endsAtElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    @VisibleForTesting
    internal fun endsAtElapsedMs(): Long = endsAtElapsedMs

    companion object {
        /** Fixed cooldown from process start. Chosen by the user 2026-09-10 (30 over 45/60). */
        const val COOLDOWN_MS = 30_000L

        /** Once the fixed part has elapsed, this much quiet after the last interaction. */
        const val INTERACTION_QUIET_MS = 10_000L

        /** A deferral shorter than this is churn; re-enqueue at least this far out. */
        const val MIN_DEFERRAL_MS = 1_000L
    }
}
