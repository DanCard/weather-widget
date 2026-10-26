package com.weatherwidget.desktop

import com.weatherwidget.shared.observations.ObservationBackfillRetryPolicy
import kotlinx.coroutines.delay

/**
 * Desktop's retry of a wake's NWS gap-fill that never reached NWS, on the schedule Android's worker
 * re-enqueues itself on ([ObservationBackfillRetryPolicy]: 10 s → 1 m → 4 m). Android realizes the waits
 * as delayed WorkManager requests; desktop as coroutine delays in the daemon's scope, so a retry never
 * holds up the rest of the wake (plans/261025-shared-nws-gap-fill.md, phase 2).
 */
internal object ObservationWindowRetry {
    private const val REASON = "obs_window"

    /**
     * Retries [refresh] while it returns [DesktopWeatherRepository.OBS_WINDOW_UNREACHABLE] and the
     * schedule lasts. Before each attempt [stillCurrent] must hold (same repository, source still
     * displayed) or the retry stops: a location or source change owns its own fill. [onStored] runs
     * after an attempt that stored rows. Returns the last outcome.
     */
    suspend fun run(
        firstOutcome: Int,
        refresh: suspend () -> Int,
        stillCurrent: () -> Boolean,
        onStored: suspend () -> Unit,
        log: suspend (tag: String, message: String, level: String) -> Unit,
        wait: suspend (Long) -> Unit = { delay(it) },
    ): Int {
        var outcome = firstOutcome
        var attempt = 0
        while (true) {
            val delayMs = ObservationBackfillRetryPolicy.nextRetryDelayMs(
                unreachable = outcome == DesktopWeatherRepository.OBS_WINDOW_UNREACHABLE,
                attempt = attempt,
            ) ?: return outcome
            attempt++
            log(ObservationBackfillRetryPolicy.LOG_TAG, ObservationBackfillRetryPolicy.logMessage(REASON, attempt, delayMs), "INFO")
            wait(delayMs)
            if (!stillCurrent()) {
                log(ObservationBackfillRetryPolicy.LOG_TAG, "reason=$REASON attempt=$attempt outcome=superseded", "INFO")
                return outcome
            }
            outcome = refresh()
            if (outcome > 0) onStored()
        }
    }
}
