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
 * **And held while a startup paint is pending.** The clock alone assumes the paint fits inside it.
 * On a boot (Pixel 7 Pro, 2026-11-15) it did not: three syncs started beside the cache-first paint,
 * the paint was still loading when the last broadcast was released, and the cached-app freezer then
 * suspended the process for 49 s — 102 s to first paint
 * (`performance/261115-boot-first-paint-frozen-behind-startup-syncs.md`). So background runs also
 * wait for the paint itself, up to [paintHoldCapMs].
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
    private val paintHoldCapMs: Long = PAINT_HOLD_CAP_MS,
    private val paintRecheckMs: Long = PAINT_RECHECK_MS,
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

    private val paintLock = Any()
    private var pendingPaints = 0
    private var paintPendingSinceElapsedMs = 0L

    /**
     * A startup paint has been claimed and is loading or rendering. Calls nest: overlapping batches
     * (onUpdate beside the package-replaced rebind) hold until the last one settles, and the cap
     * counts from the first.
     */
    fun onStartupPaintPending(nowElapsedMs: Long) {
        synchronized(paintLock) {
            if (pendingPaints == 0) paintPendingSinceElapsedMs = nowElapsedMs
            pendingPaints++
        }
    }

    /** The paint was pushed, failed or was cancelled; call from `finally`. */
    fun onStartupPaintSettled() {
        synchronized(paintLock) {
            if (pendingPaints > 0) pendingPaints--
        }
    }

    /**
     * How long a pending startup paint still holds background runs, or 0. The hold is released in
     * [paintRecheckMs] steps rather than as one wait to the cap, so a deferred run starts soon after
     * the paint lands instead of sitting out the worst case.
     */
    private fun paintHoldMs(nowElapsedMs: Long): Long =
        synchronized(paintLock) {
            if (pendingPaints == 0) return 0L
            val capRemainingMs = paintPendingSinceElapsedMs + paintHoldCapMs - nowElapsedMs
            capRemainingMs.coerceIn(0L, paintRecheckMs)
        }

    private fun clockMs(nowElapsedMs: Long): Long =
        if (!userFacingTriggerSeen) 0L else (endsAtElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    /**
     * Milliseconds a background run must wait before starting, or 0 to run now. The clock part is
     * always 0 until a user-facing trigger has been seen (see the class comment for why); a pending
     * startup paint is itself user-facing and holds regardless.
     */
    fun remainingMs(nowElapsedMs: Long): Long = maxOf(clockMs(nowElapsedMs), paintHoldMs(nowElapsedMs))

    /** Why [remainingMs] is non-zero, for `SYNC_DEFERRED_STARTUP cause=`. */
    fun deferCause(nowElapsedMs: Long): String =
        if (paintHoldMs(nowElapsedMs) > 0L) CAUSE_PAINT_PENDING else CAUSE_COOLDOWN

    @VisibleForTesting
    internal fun endsAtElapsedMs(): Long = endsAtElapsedMs

    companion object {
        /** Fixed cooldown from process start. Chosen by the user 2026-09-10 (30 over 45/60). */
        // 30 s until 2026-10-06; lowered to 16 s so a source that becomes primary at install time fetches sooner.
        // Lowered to 6 s (user's call 2026-11-01) so interaction taps on warm widgets fetch immediately.
        const val COOLDOWN_MS = 6_000L

        /** Once the fixed part has elapsed, this much quiet after the last interaction. */
        const val INTERACTION_QUIET_MS = 6_000L

        /** A deferral shorter than this is churn; re-enqueue at least this far out. */
        const val MIN_DEFERRAL_MS = 1_000L

        /** A startup paint that has not settled by now no longer holds background runs. */
        const val PAINT_HOLD_CAP_MS = 30_000L

        /** How far out a run deferred for a pending paint is replayed to look again. */
        const val PAINT_RECHECK_MS = 3_000L

        const val CAUSE_PAINT_PENDING = "paint_pending"
        const val CAUSE_COOLDOWN = "cooldown"
    }
}
