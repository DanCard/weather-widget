package com.weatherwidget.shared.util

import kotlinx.coroutines.CancellationException

/**
 * A forecast fetch the user forced for ONE source (a toggle, a viewing-stale tap, a source retry):
 * the rule both platforms run (plans/261123-shared-targeted-sync-paint-then-tail.md).
 *
 * Fast path ([onFetched]): the rain-chance freeze ([Host.afterFetch], which must see every fetch while
 * its window is open), then the paint from what is cached, then the post-sync actuals "tail" is
 * requested. Nothing slow sits between the fetch and the paint, and the caller returns right after,
 * so Android's serial sync queue frees for the next toggle.
 *
 * Tail ([runTail]), debounced by [requestTail] so a burst of toggles costs one: observation history
 * for the served sources, the observation feeds, the daily-actuals recompute, and a repaint only when
 * the displayed actuals changed. It yields to a user sync or a fresh interaction, capped by
 * [MAX_YIELD_MS] so a busy user cannot starve it.
 *
 * The platforms supply only I/O and their timer ([Host.scheduleTail]). Nothing is ever replaced or
 * cancelled (AGENTS.md rule 6): a pending tail is kept, and when it comes due it re-arms itself for the
 * rest of [TAIL_DELAY_MS] if a newer request arrived meanwhile (capped by [MAX_PUSH_OUT_MS]).
 */
class TargetedForecastSyncRun(private val clock: () -> Long) {

    enum class TailState { NONE, PENDING, RUNNING }

    /** How [Host.scheduleTail] must enqueue. */
    enum class Enqueue {
        /** Nothing pending or running: schedule one. */
        SCHEDULE,
        /** A tail is waiting: leave it; it settles on the newest request when it comes due. */
        KEEP_PENDING,
        /** A tail is running (or re-arming itself): run one more after it, never cancel it. */
        APPEND_AFTER_RUNNING,
    }

    enum class TailOutcome { RAN, YIELDED }

    interface Host {
        /** `DailyHistoryRun.afterFetch` at the site. */
        suspend fun afterFetch()

        /** Paint from what is stored now (Android: the widgets; desktop: publish the snapshot). */
        suspend fun paint()

        suspend fun tailState(): TailState

        /** Arm the platform timer. [sourceIds] is everything requested so far for this tail. */
        suspend fun scheduleTail(enqueue: Enqueue, delayMs: Long, sourceIds: Set<String>)

        /** The sources on screen now; the tail serves these plus the requested ones. */
        suspend fun visibleSourceIds(): Set<String>

        /** The need-gated NWS history fill (`NwsGapFillRun.dailyForSources`) for [sourceIds]. */
        suspend fun fillObservationHistory(sourceIds: Set<String>)

        /** The observation feeds (METAR, Synoptic, …) behind [sourceIds], each behind its own due gate. */
        suspend fun refreshObservationFeeds(sourceIds: Set<String>)

        /** A value equal before and after iff the displayed daily actuals did not change. */
        suspend fun actualsSignature(): Any?

        /** Daily-actuals recompute plus the daily-history repairs and upkeep. */
        suspend fun recomputeActuals()

        suspend fun repaint(reason: String)

        /** A user-forced sync is running now. */
        fun userSyncActive(): Boolean

        /** The last widget/window interaction on the same clock, or null when the platform does not track one. */
        fun lastInteractionMs(): Long?

        suspend fun log(tag: String, message: String, level: String = "INFO")
    }

    private val lock = Any()
    private var firstRequestMs: Long? = null
    private var lastRequestMs: Long? = null
    private var firstYieldMs: Long? = null
    private val requested = linkedSetOf<String>()

    /** The fast path after a fetch for [sourceId]; [stored] is whether the fetch answered and was persisted. */
    suspend fun onFetched(host: Host, sourceId: String, stored: Boolean) {
        if (stored) {
            step(host, "after_fetch") { host.afterFetch() }
        }
        host.paint()
        host.log(TAG_SYNC, "stage=painted source=$sourceId stored=$stored")
        if (stored) requestTail(host, setOf(sourceId))
    }

    /** Debounced request for a tail serving [sourceIds]. */
    suspend fun requestTail(host: Host, sourceIds: Set<String>) {
        val state = host.tailState()
        val (enqueue, ids) = synchronized(lock) {
            requested += sourceIds
            decide(state, clock()) to requested.toSet()
        }
        host.scheduleTail(enqueue, TAIL_DELAY_MS, ids)
        host.log(TAG_SYNC, "stage=tail_scheduled enqueue=$enqueue delayMs=$TAIL_DELAY_MS sources=${ids.joinToString("|")}")
    }

    /** Debounce decision; callers hold [lock]. */
    private fun decide(state: TailState, nowMs: Long): Enqueue {
        lastRequestMs = nowMs
        return when (state) {
            TailState.NONE -> {
                firstRequestMs = nowMs
                Enqueue.SCHEDULE
            }
            TailState.PENDING -> {
                if (firstRequestMs == null) firstRequestMs = nowMs
                Enqueue.KEEP_PENDING
            }
            TailState.RUNNING -> {
                if (firstRequestMs == null) firstRequestMs = nowMs
                Enqueue.APPEND_AFTER_RUNNING
            }
        }
    }

    /**
     * How much longer a tail coming due must wait for the burst to settle: the rest of [TAIL_DELAY_MS]
     * after the newest request, or 0 once that passed or the burst hit [MAX_PUSH_OUT_MS]. Callers hold [lock].
     */
    private fun settleRemainingMs(nowMs: Long): Long {
        val last = lastRequestMs ?: return 0L
        val first = firstRequestMs ?: last
        if (nowMs - first >= MAX_PUSH_OUT_MS) return 0L
        return (TAIL_DELAY_MS - (nowMs - last)).coerceAtLeast(0L)
    }

    /**
     * The tail. [sourceIds] are the ones the scheduled work carried (Android keeps them in WorkManager
     * input, which outlives this process); the in-memory requests are added to them.
     */
    suspend fun runTail(host: Host, sourceIds: Set<String>): TailOutcome {
        val now = clock()
        val settleMs = synchronized(lock) { settleRemainingMs(now) }
        if (settleMs > 0L) {
            val ids = synchronized(lock) { (requested + sourceIds).toSet() }
            host.scheduleTail(Enqueue.APPEND_AFTER_RUNNING, settleMs, ids)
            host.log(TAG_DEFERRED, "reason=settling delayMs=$settleMs")
            return TailOutcome.YIELDED
        }
        val lastInteraction = host.lastInteractionMs()
        val recentInteraction = lastInteraction != null && (now - lastInteraction) in 0 until INTERACTION_QUIET_MS
        val busy = host.userSyncActive() || recentInteraction
        val yieldNow = synchronized(lock) {
            if (!busy) {
                firstYieldMs = null
                false
            } else {
                val first = firstYieldMs ?: now.also { firstYieldMs = it }
                if (now - first >= MAX_YIELD_MS) {
                    firstYieldMs = null
                    false
                } else {
                    true
                }
            }
        }
        if (yieldNow) {
            val ids = synchronized(lock) { (requested + sourceIds).toSet() }
            host.scheduleTail(Enqueue.APPEND_AFTER_RUNNING, YIELD_DELAY_MS, ids)
            host.log(
                TAG_DEFERRED,
                "reason=busy userSync=${host.userSyncActive()} recentInteraction=$recentInteraction " +
                    "delayMs=$YIELD_DELAY_MS yieldedMs=${synchronized(lock) { now - (firstYieldMs ?: now) }}",
            )
            return TailOutcome.YIELDED
        }

        val served = synchronized(lock) {
            val ids = (requested + sourceIds).toSet()
            requested.clear()
            firstRequestMs = null
            lastRequestMs = null
            ids
        } + host.visibleSourceIds()
        val startMs = clock()
        host.log(TAG_RUN, "sources=${served.joinToString("|")}")
        val before = step(host, "signature_before") { host.actualsSignature() }
        step(host, "observation_history") { host.fillObservationHistory(served) }
        step(host, "observation_feeds") { host.refreshObservationFeeds(served) }
        step(host, "recompute") { host.recomputeActuals() }
        val after = step(host, "signature_after") { host.actualsSignature() }
        val changed = before == null || after == null || before != after
        if (changed) step(host, "repaint") { host.repaint(REPAINT_REASON) }
        host.log(TAG_RESULT, "changed=$changed ms=${clock() - startMs}")
        return TailOutcome.RAN
    }

    /** One tail step: a failure is logged and the next step still runs. */
    private suspend fun <T> step(host: Host, name: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log(TAG_FAIL, "step=$name error=${e.javaClass.simpleName}: ${e.message}", "WARN")
            null
        }

    fun resetForTesting() = synchronized(lock) {
        firstRequestMs = null
        lastRequestMs = null
        firstYieldMs = null
        requested.clear()
    }

    companion object {
        /** A tail runs this long after the newest request of a burst. */
        const val TAIL_DELAY_MS = 12_000L

        /** A burst cannot push its tail out further than this from the burst's first request. */
        const val MAX_PUSH_OUT_MS = 60_000L

        /** How long a tail waits before re-checking after it yielded. */
        const val YIELD_DELAY_MS = 20_000L

        /** A tail stops yielding after this long, so a busy user still gets actuals. */
        const val MAX_YIELD_MS = 90_000L

        /** An interaction this recent counts as busy. */
        const val INTERACTION_QUIET_MS = 5_000L

        const val TAG_SYNC = "TARGETED_SYNC"
        const val TAG_RUN = "POST_SYNC_ACTUALS_RUN"
        const val TAG_RESULT = "POST_SYNC_ACTUALS_RESULT"
        const val TAG_DEFERRED = "POST_SYNC_ACTUALS_DEFERRED"
        const val TAG_FAIL = "POST_SYNC_ACTUALS_FAIL"
        const val REPAINT_REASON = "post_sync_actuals"

        /**
         * A user forced exactly one source. Settings "Refresh data" (no target, every source) and
         * routine syncs (not forced) keep the inline order: backfills, recompute, then paint.
         */
        fun isTargeted(forceRefresh: Boolean, uiOnly: Boolean, targetSourceId: String?): Boolean =
            forceRefresh && !uiOnly && targetSourceId != null

        fun encodeSources(ids: Set<String>): String = ids.joinToString(",")

        fun decodeSources(raw: String?): Set<String> =
            raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()
    }
}
