package com.weatherwidget.widget.handlers

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Per-tap facts gathered while the tap runs, carried in its coroutine context so the code that
 * learns them (the widget lock) need not know who is measuring.
 */
internal class InteractionTrace : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InteractionTrace>

    /** Summed over every widget-lock acquisition the tap makes. */
    @Volatile
    var lockWaitMs: Long = 0L
}

/**
 * Which widget taps get a persisted `INTERACTION_E2E` row, and what it says.
 *
 * The logcat `INTERACTION_E2E` line is the only end-to-end number (broadcast receipt → done), and
 * logcat on the Fold holds ~40 minutes, so a "slow first cycle through the sources" reported later
 * was unrecoverable: `TOGGLE_API_TIMING` starts inside the widget lock, after process start, Hilt
 * injection and the dispatcher queue (2026-10-19,
 * `performance/261019-fold-first-source-cycle-slow-after-process-start.md`).
 *
 * Kept: the first [EARLY_TAPS] taps of each process — a full cycle through the sources, whatever
 * its speed, so warm-up is visible against the steady state — and any tap slower than
 * [SLOW_E2E_MS]. The row is written after the paint is pushed, never on the path it measures.
 */
internal object InteractionLatency {
    const val TAG = "INTERACTION_E2E"
    const val EARLY_TAPS = 8
    const val SLOW_E2E_MS = 800L

    /** A tap this soon after process start most likely started the process. */
    const val COLD_PROCESS_AGE_MS = 10_000L

    fun shouldPersist(ordinal: Int, e2eMs: Long): Boolean =
        ordinal <= EARLY_TAPS || e2eMs >= SLOW_E2E_MS

    fun format(
        action: String,
        appWidgetId: Int,
        ordinal: Int,
        processAgeAtReceiptMs: Long,
        queueMs: Long,
        lockWaitMs: Long,
        workMs: Long,
        e2eMs: Long,
    ): String {
        val age = if (processAgeAtReceiptMs == Long.MAX_VALUE) "na" else "${processAgeAtReceiptMs}ms"
        val cold = processAgeAtReceiptMs < COLD_PROCESS_AGE_MS
        return "action=$action widget=$appWidgetId e2e=${e2eMs}ms queue=${queueMs}ms " +
            "lockWait=${lockWaitMs}ms work=${workMs}ms tapInProcess=$ordinal procAge=$age cold=$cold"
    }
}
