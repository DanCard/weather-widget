package com.weatherwidget.desktop

import com.weatherwidget.data.local.HistoryPruneSchedule
import com.weatherwidget.shared.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * The daemon's housekeeping timer. It stands alone because the refresh loops stop while nobody is
 * here, which is when this work should happen: tied to a refresh, the prune starved
 * ([HistoryPruneSchedule]). [HistoryPruneSchedule.decide] says whether each check does anything.
 */
object DesktopHousekeepingLoop {
    private const val TAG = "DesktopHousekeepingLoop"

    suspend fun run(prune: suspend (nowMs: Long) -> Unit, clock: () -> Long) {
        delay(HistoryPruneSchedule.FIRST_CHECK_DELAY_MS)
        while (true) {
            try {
                prune(clock())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Housekeeping check failed: ${e.message}")
            }
            delay(HistoryPruneSchedule.CHECK_INTERVAL_MS)
        }
    }
}
