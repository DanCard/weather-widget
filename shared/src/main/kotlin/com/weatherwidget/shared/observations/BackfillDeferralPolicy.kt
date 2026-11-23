package com.weatherwidget.shared.observations

/**
 * Keeps delayed observation-history backfills out of the way of active widget interactions and
 * user-initiated forecast syncs (plans/261123-fast-paint-first-forecast-sync-and-backfill-deferral.md).
 *
 * Each interaction pushes an ENQUEUED backfill out by [DEFER_DELAY_MS], capped at
 * [MAX_TOTAL_DEFER_MS] from the first deferral so a busy user cannot starve it indefinitely.
 */
class BackfillDeferralPolicy(private val clock: () -> Long) {
    private val lock = Any()
    private var firstDeferredMs: Long? = null
    private var activeUserSyncs = 0

    fun userSyncStarted() = synchronized(lock) { activeUserSyncs++ }

    fun userSyncEnded() = synchronized(lock) {
        if (activeUserSyncs > 0) activeUserSyncs--
    }

    fun onBackfillFinished() = synchronized(lock) { firstDeferredMs = null }

    /**
     * Called when the user interacts with a widget while an `OBS_HOURLY_BACKFILL` job is ENQUEUED.
     * Returns the new delay in ms (`DEFER_DELAY_MS`) to reschedule with, or `null` if total deferral
     * has reached [MAX_TOTAL_DEFER_MS].
     */
    fun deferOnInteraction(): Long? = synchronized(lock) {
        val now = clock()
        val first = firstDeferredMs ?: now.also { firstDeferredMs = it }
        if (now - first >= MAX_TOTAL_DEFER_MS) return null
        DEFER_DELAY_MS
    }

    /**
     * Called when `OBS_HOURLY_BACKFILL_RUN` starts executing. Returns a delay in ms to yield and
     * re-enqueue when a user sync or recent interaction is active (and total deferral is under cap),
     * or `null` to proceed immediately.
     */
    fun yieldAtStart(activeInteractions: Int, lastInteractionMs: Long?): Long? = synchronized(lock) {
        val now = clock()
        val recentInteraction = lastInteractionMs != null && (now - lastInteractionMs) in 0 until INTERACTION_QUIET_MS
        val busy = activeUserSyncs > 0 || activeInteractions > 0 || recentInteraction
        if (!busy) {
            firstDeferredMs = null
            return null
        }
        val first = firstDeferredMs ?: now.also { firstDeferredMs = it }
        if (now - first >= MAX_TOTAL_DEFER_MS) {
            firstDeferredMs = null
            return null
        }
        DEFER_DELAY_MS
    }

    fun totalDeferredMs(): Long = synchronized(lock) {
        val first = firstDeferredMs ?: return 0L
        (clock() - first).coerceAtLeast(0L)
    }

    fun resetForTesting() = synchronized(lock) {
        firstDeferredMs = null
        activeUserSyncs = 0
    }

    companion object {
        const val DEFER_DELAY_MS = 20_000L
        const val MAX_TOTAL_DEFER_MS = 90_000L
        const val INTERACTION_QUIET_MS = 5_000L
        const val TAG_DEFERRED = "OBS_HOURLY_BACKFILL_DEFERRED"
    }
}
