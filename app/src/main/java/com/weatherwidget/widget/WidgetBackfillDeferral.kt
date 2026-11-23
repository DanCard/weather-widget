package com.weatherwidget.widget

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.observations.BackfillDeferralPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide coordinator for deferring `OBS_HOURLY_BACKFILL` around user widget interactions
 * and active user-initiated forecast syncs (plans/261123-fast-paint-first-forecast-sync-and-backfill-deferral.md).
 *
 * Strictly never cancels a `RUNNING` worker (`AGENTS.md` rule 6): only `ENQUEUED` work is pushed out.
 */
internal object WidgetBackfillDeferral {
    private const val TAG = "WidgetBackfillDeferral"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val policy = BackfillDeferralPolicy(SystemClock::elapsedRealtime)

    /**
     * Last enqueued backfill arguments so an `ENQUEUED` job can be safely rescheduled with a fresh
     * delay on user interaction without inspecting opaque WorkManager InputData.
     */
    @Volatile
    private var lastArgs: PendingArgs? = null

    internal data class PendingArgs(
        val latitude: Double,
        val longitude: Double,
        val lookbackHours: Long,
        val reason: String,
    )

    fun recordEnqueued(latitude: Double, longitude: Double, lookbackHours: Long, reason: String) {
        lastArgs = PendingArgs(latitude, longitude, lookbackHours, reason)
    }

    /**
     * Called on widget user interactions. If an observation backfill is currently `ENQUEUED` (not
     * `RUNNING`), pushes its start out by [BackfillDeferralPolicy.DEFER_DELAY_MS] up to the
     * [BackfillDeferralPolicy.MAX_TOTAL_DEFER_MS] cap.
     */
    fun deferPendingOnInteraction(context: Context) {
        val args = lastArgs ?: return
        val appContext = context.applicationContext
        scope.launch {
            runCatching {
                val wm = WorkManager.getInstance(appContext)
                val infos = wm.getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_OBSERVATION_BACKFILL).get()
                if (infos.any { it.state == WorkInfo.State.RUNNING }) return@launch
                if (infos.none { it.state == WorkInfo.State.ENQUEUED }) return@launch
                val delayMs = policy.deferOnInteraction() ?: return@launch
                val request = WidgetWorkScheduler.buildObservationBackfillRequest(
                    latitude = args.latitude,
                    longitude = args.longitude,
                    lookbackHours = args.lookbackHours,
                    reason = args.reason,
                    attempt = 0,
                    initialDelayMs = delayMs,
                )
                wm.enqueueUniqueWork(
                    WidgetWorkScheduler.WORK_NAME_OBSERVATION_BACKFILL,
                    ExistingWorkPolicy.REPLACE,
                    request,
                )
                val msg = "reason=user_interaction delayMs=$delayMs totalDeferredMs=${policy.totalDeferredMs()}"
                Log.i(TAG, "${BackfillDeferralPolicy.TAG_DEFERRED} $msg")
                WeatherDatabase.getDatabase(appContext).appLogDao().log(
                    BackfillDeferralPolicy.TAG_DEFERRED,
                    msg,
                    "INFO",
                )
            }.onFailure { e ->
                Log.w(TAG, "deferPendingOnInteraction failed: ${e.message}")
            }
        }
    }

    /**
     * Checked at the start of `OBS_HOURLY_BACKFILL_RUN`. Returns true if this run yielded and
     * re-enqueued itself to avoid contending with an active user sync or recent tap.
     */
    suspend fun yieldIfBusy(
        context: Context,
        appLogDao: AppLogDao,
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
        reason: String,
        attempt: Int,
    ): Boolean {
        val (activeInteractions, lastInteractionMs) = WidgetTimedRepaintGate.gate.interactionSnapshot()
        val delayMs = policy.yieldAtStart(activeInteractions, lastInteractionMs) ?: return false
        WidgetWorkScheduler.enqueueObservationBackfillRetry(
            context = context,
            latitude = latitude,
            longitude = longitude,
            lookbackHours = lookbackHours,
            reason = reason,
            attempt = attempt,
            delayMs = delayMs,
        )
        val msg = "reason=busy_at_start delayMs=$delayMs totalDeferredMs=${policy.totalDeferredMs()} activeInteractions=$activeInteractions"
        Log.i(TAG, "${BackfillDeferralPolicy.TAG_DEFERRED} $msg")
        appLogDao.log(BackfillDeferralPolicy.TAG_DEFERRED, msg, "INFO")
        return true
    }
}
