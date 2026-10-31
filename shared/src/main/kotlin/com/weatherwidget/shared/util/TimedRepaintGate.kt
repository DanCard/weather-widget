package com.weatherwidget.shared.util

import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps repaints that fire on a timer out of the way of a user who is interacting. A timer has no
 * news the user asked for, so it waits for them to stop. On 2026-10-31 a UI-only refresh held widget
 * 345's lock for 2.2 s, and one banner expiry ran 13 paint attempts.
 *
 * Only timed work has a [Trigger]. A resume, location change or user refresh never comes here, and
 * neither does an event that carries new data. Platforms supply a clock and a logger, report
 * interactions, and repaint later when [admit] refuses.
 *
 * plans/261031-taps-not-blocked-by-refresh-or-timed-repaints.md
 */
class TimedRepaintGate(private val clock: () -> Long) {
    enum class Trigger { UI_TICK, NOTICE_EXPIRY, STARTUP_FOLLOW_UP, DESKTOP_FALLBACK }

    private val lock = Any()
    private var activeInteractions = 0
    private var lastInteractionMs: Long? = null
    private val firstDeferredMs = ConcurrentHashMap<Trigger, Long>()

    fun interactionStarted() = synchronized(lock) {
        activeInteractions++
        lastInteractionMs = clock()
    }

    fun interactionEnded() = synchronized(lock) {
        if (activeInteractions > 0) activeInteractions--
        lastInteractionMs = clock()
    }

    /** An instantaneous interaction, such as a desktop click, with no end to report. */
    fun interactionObserved() = synchronized(lock) { lastInteractionMs = clock() }

    /**
     * True to repaint now, false when the caller should defer. A trigger refused for
     * [MAX_DEFER_MS] runs anyway, so a busy user cannot hold repaints off indefinitely.
     */
    suspend fun admit(trigger: Trigger, log: suspend (tag: String, message: String) -> Unit): Boolean {
        val now = clock()
        val (active, quietRemainingMs) = synchronized(lock) {
            activeInteractions to quietRemainingMs(lastInteractionMs, now)
        }
        if (active == 0 && quietRemainingMs <= 0L) {
            firstDeferredMs.remove(trigger)
            return true
        }
        val firstDeferred = firstDeferredMs.putIfAbsent(trigger, now) ?: now
        val deferredMs = now - firstDeferred
        if (deferredMs >= MAX_DEFER_MS) {
            firstDeferredMs.remove(trigger)
            log(TAG_FORCED, "trigger=$trigger deferredMs=$deferredMs active=$active")
            return true
        }
        log(TAG_DEFERRED, "trigger=$trigger active=$active quietRemainingMs=$quietRemainingMs deferredMs=$deferredMs")
        return false
    }

    /** For tests of the processes that hold a gate for their whole lifetime. */
    fun resetForTesting() {
        synchronized(lock) {
            activeInteractions = 0
            lastInteractionMs = null
        }
        firstDeferredMs.clear()
    }

    private fun quietRemainingMs(lastMs: Long?, nowMs: Long): Long {
        if (lastMs == null) return 0L
        val elapsedMs = nowMs - lastMs
        // A wall clock that stepped backwards says nothing about the user.
        return if (elapsedMs < 0L) 0L else QUIET_MS - elapsedMs
    }

    companion object {
        /** How long after the last interaction the user counts as settled. */
        const val QUIET_MS = 3_000L
        const val MAX_DEFER_MS = 30_000L
        const val TAG_DEFERRED = "TIMED_REPAINT_DEFERRED"
        const val TAG_FORCED = "TIMED_REPAINT_FORCED"
    }
}
