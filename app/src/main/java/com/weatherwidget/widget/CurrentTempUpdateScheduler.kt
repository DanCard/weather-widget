package com.weatherwidget.widget

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Schedules lightweight current-temperature-only refresh work.
 */
object CurrentTempUpdateScheduler {
    private const val TAG = "CurrentTempScheduler"
    private const val OVERDUE_GRACE_MINUTES = 2L
    private val timestampFormatter: DateTimeFormatter =
        DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneId.systemDefault())

    fun enqueueImmediateUpdate(
        context: Context,
        reason: String,
        opportunistic: Boolean,
        force: Boolean = false,
        targetSourceId: String? = null,
        userInteraction: Boolean = false,
    ) {
        runCatching {
            val constraints =
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val workRequest =
                OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                    .setInputData(
                        Data.Builder()
                            .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, true)
                            .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_OPPORTUNISTIC, opportunistic)
                            .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                            .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, force)
                            .tagTestModeEnqueue()
                            .putBoolean(WeatherWidgetWorker.KEY_USER_INTERACTION, userInteraction)
                            .apply {
                                if (targetSourceId != null) {
                                    putString(WeatherWidgetWorker.KEY_TARGET_SOURCE, targetSourceId)
                                }
                            }
                            .build(),
                    )
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP,
                // APPEND_OR_REPLACE (not REPLACE): never cancel a running current-temp worker — the
                // cancelled coroutine resume segfaults ART on debuggable builds
                // ([[samsung_widget_dead_native_sigsegv]]); the fetch still runs, after any in-flight one.
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                workRequest,
            )
            logSchedulerEvent(
                context = context,
                tag = "CURR_FETCH_WORK_ENQUEUED",
                message =
                    "type=immediate reason=$reason opportunistic=$opportunistic force=$force target=${targetSourceId ?: "all_visible"} " +
                        "userInteraction=$userInteraction " +
                        "policyDelayMinutes=0 dueAt=${formatTime(System.currentTimeMillis())} " +
                        "workId=${workRequest.id}",
            )
            Log.d(
                TAG,
                "enqueueImmediateUpdate: reason=$reason opportunistic=$opportunistic force=$force " +
                    "userInteraction=$userInteraction target=${targetSourceId ?: "all_visible"} id=${workRequest.id}",
            )
        }.onFailure { e ->
            Log.e(TAG, "enqueueImmediateUpdate failed: ${e.message}", e)
        }
    }

    fun scheduleNextChargingUpdate(
        context: Context,
        isScreenInteractive: Boolean = true,
        firstDelayMinutes: Long? = null,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                scheduleNextChargingUpdate(
                    context = context,
                    workManager = WorkManager.getInstance(context),
                    nowMs = System.currentTimeMillis(),
                    isScreenInteractive = isScreenInteractive,
                    firstDelayMinutes = firstDelayMinutes,
                )
            }.onFailure { e ->
                Log.e(TAG, "scheduleNextChargingUpdate failed: ${e.message}", e)
                logSchedulerEvent(
                    context = context,
                    tag = "CURR_FETCH_WORK_STATE",
                    message = "type=charging_loop decision=inspect_failed error=${e.javaClass.simpleName}:${e.message}",
                )
            }
        }
    }

    @VisibleForTesting
    internal suspend fun scheduleNextChargingUpdate(
        context: Context,
        workManager: WorkManager,
        nowMs: Long,
        ignoreRunningWorkId: UUID? = null,
        isScreenInteractive: Boolean = true,
        firstDelayMinutes: Long? = null,
    ) {
        val battery = BatterySnapshotProvider.snapshot(context)
        val isCharging = battery.isCharging
        Log.d(TAG, "scheduleNextChargingUpdate: isCharging=$isCharging battery=${battery.batteryLevel} isScreenInteractive=$isScreenInteractive ignoreRunningWorkId=$ignoreRunningWorkId")

        // Callers (the ui_update_alarm heartbeat among them) ask unconditionally; scheduling a run
        // the policy will block on arrival only produces a `policy_blocked` no-op later.
        val intervalMinutes =
            CurrentTempFetchPolicy.loopIntervalMinutes(isCharging, isScreenInteractive, battery.batteryLevel)
        if (intervalMinutes == null) {
            Log.d(TAG, "scheduleNextChargingUpdate: loop not allowed, nothing scheduled")
            return
        }
        val delayMinutes = firstDelayMinutes?.coerceAtMost(intervalMinutes) ?: intervalMinutes

        // On battery the loop is timed by a non-wakeup alarm, not a delayed WorkManager request:
        // JobScheduler held a ready 15-minute request for 13+ minutes past due on the Pixel
        // (see BatteryObservationAlarm). The alarm enqueues an immediate request when it fires.
        if (!isCharging) {
            val triggerAtMs = nowMs + TimeUnit.MINUTES.toMillis(delayMinutes)
            val armed = BatteryObservationAlarm.arm(context, triggerAtMs, nowMs)
            logSchedulerEvent(
                context = context,
                tag = "CURR_FETCH_WORK_REQUESTED",
                message =
                    "type=battery_alarm reason=${CurrentTempFetchPolicy.loopReason(isCharging = false, overdue = false)} " +
                        "decision=${if (armed) "armed" else "kept_earlier"} delayMinutes=$delayMinutes " +
                        "requestedDueAt=${formatTime(triggerAtMs)}",
            )
            return
        }

        val existingWork =
            withContext(Dispatchers.IO) {
                workManager.getWorkInfosForUniqueWork(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP).get()
            }
                .map(ChargingWorkInfo::fromWorkInfo)
        val decision = decideChargingLoopWork(existingWork, nowMs, ignoreRunningWorkId, intervalMinutes)

        logSchedulerEvent(
            context = context,
            tag = "CURR_FETCH_WORK_STATE",
            message =
                "type=charging_loop decision=${decision.action.logValue} reason=${decision.reason} " +
                    "active=${decision.active?.toLogString() ?: "none"} now=${formatTime(nowMs)} " +
                    "intervalMinutes=$intervalMinutes interactive=$isScreenInteractive",
        )

        when (decision.action) {
            ChargingLoopAction.KEEP -> {
                Log.d(TAG, "scheduleNextChargingUpdate: keeping existing work reason=${decision.reason}")
            }
            ChargingLoopAction.ENQUEUE_DELAYED,
            ChargingLoopAction.REPLACE_DELAYED,
            ChargingLoopAction.REPLACE_IMMEDIATE,
            -> {
                val immediate = decision.action == ChargingLoopAction.REPLACE_IMMEDIATE
                val reason = CurrentTempFetchPolicy.loopReason(isCharging, overdue = immediate)
                val workRequest = buildCurrentTempRequest(reason = reason, delayMinutes = if (immediate) 0 else delayMinutes)
                val policy =
                    when (decision.action) {
                        ChargingLoopAction.ENQUEUE_DELAYED -> ExistingWorkPolicy.APPEND_OR_REPLACE
                        // Replacing a *delayed* (not-yet-running) heartbeat is safe. But an *immediate*
                        // replace can cancel a currently-running current-temp worker, whose cancelled
                        // coroutine resume segfaults ART on debuggable builds
                        // ([[samsung_widget_dead_native_sigsegv]]); APPEND_OR_REPLACE runs after instead.
                        ChargingLoopAction.REPLACE_DELAYED -> ExistingWorkPolicy.REPLACE
                        ChargingLoopAction.REPLACE_IMMEDIATE -> ExistingWorkPolicy.APPEND_OR_REPLACE
                        ChargingLoopAction.KEEP -> ExistingWorkPolicy.KEEP
                    }
                workManager.enqueueUniqueWork(
                    WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP,
                    policy,
                    workRequest,
                )
                val dueAtMs =
                    nowMs + TimeUnit.MINUTES.toMillis(if (immediate) 0 else delayMinutes)
                logSchedulerEvent(
                    context = context,
                    tag = "CURR_FETCH_WORK_REQUESTED",
                    message =
                        "type=charging_loop reason=$reason decision=${decision.action.logValue} " +
                            "policy=${policy.name.lowercase()} delayMinutes=${if (immediate) 0 else delayMinutes} " +
                            "requestedDueAt=${formatTime(dueAtMs)} workId=${workRequest.id}",
                )
                if (decision.action == ChargingLoopAction.REPLACE_DELAYED || decision.action == ChargingLoopAction.REPLACE_IMMEDIATE) {
                    logSchedulerEvent(
                        context = context,
                        tag = "CURR_FETCH_WORK_RECOVERED",
                        message =
                            "type=charging_loop recovery=${decision.action.logValue} reason=${decision.reason} " +
                                "previous=${decision.active?.toLogString() ?: "none"} replacementWorkId=${workRequest.id}",
                    )
                }
                Log.d(TAG, "scheduleNextChargingUpdate: decision=${decision.action} policy=$policy id=${workRequest.id}")
            }
        }
    }

    private fun buildCurrentTempRequest(
        reason: String,
        delayMinutes: Long,
    ) =
        OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .apply {
                if (delayMinutes > 0) {
                    setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                }
            }
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, true)
                    .putBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_OPPORTUNISTIC, false)
                    .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                    .build(),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()

    @VisibleForTesting
    internal fun decideChargingLoopWork(
        workInfos: List<ChargingWorkInfo>,
        nowMs: Long,
        ignoreRunningWorkId: UUID? = null,
        intervalMinutes: Long = CurrentTempFetchPolicy.CHARGING_INTERVAL_MINUTES,
    ): ChargingLoopDecision {
        val active = workInfos.activeCurrentTempWork(ignoreRunningWorkId)
            ?: return ChargingLoopDecision(ChargingLoopAction.ENQUEUE_DELAYED, "no_active_work", null)

        if (active.state == WorkInfo.State.RUNNING) {
            return ChargingLoopDecision(ChargingLoopAction.KEEP, "running", active)
        }

        if (active.state != WorkInfo.State.ENQUEUED) {
            return ChargingLoopDecision(ChargingLoopAction.ENQUEUE_DELAYED, "no_enqueued_or_running_work", active)
        }

        val dueAtMs = active.nextScheduleTimeMs
        if (dueAtMs == null || dueAtMs == Long.MAX_VALUE) {
            return ChargingLoopDecision(ChargingLoopAction.REPLACE_DELAYED, "missing_due_time", active)
        }

        val intervalMs = TimeUnit.MINUTES.toMillis(intervalMinutes)
        val graceMs = TimeUnit.MINUTES.toMillis(OVERDUE_GRACE_MINUTES)
        return when {
            dueAtMs < nowMs - graceMs ->
                ChargingLoopDecision(ChargingLoopAction.REPLACE_IMMEDIATE, "overdue_by_ms=${nowMs - dueAtMs}", active)
            dueAtMs > nowMs + intervalMs + graceMs ->
                ChargingLoopDecision(ChargingLoopAction.REPLACE_DELAYED, "too_far_future_by_ms=${dueAtMs - nowMs}", active)
            else ->
                ChargingLoopDecision(ChargingLoopAction.KEEP, "scheduled_in_ms=${dueAtMs - nowMs}", active)
        }
    }

    private fun List<ChargingWorkInfo>.activeCurrentTempWork(ignoreRunningWorkId: UUID?): ChargingWorkInfo? {
        val unfinished =
            filter {
                !it.state.isFinished &&
                    !(ignoreRunningWorkId != null && it.id == ignoreRunningWorkId && it.state == WorkInfo.State.RUNNING)
            }
        return unfinished.firstOrNull { it.state == WorkInfo.State.RUNNING }
            ?: unfinished.filter { it.state == WorkInfo.State.ENQUEUED }
                .minByOrNull { it.nextScheduleTimeMs ?: Long.MAX_VALUE }
    }

    @VisibleForTesting
    internal data class ChargingWorkInfo(
        val id: UUID,
        val state: WorkInfo.State,
        val runAttemptCount: Int,
        val nextScheduleTimeMs: Long?,
    ) {
        fun toLogString(): String =
            "id=$id,state=$state,attempt=$runAttemptCount,next=${nextScheduleTimeMs?.let(::formatTime) ?: "none"}"

        companion object {
            fun fromWorkInfo(workInfo: WorkInfo): ChargingWorkInfo =
                ChargingWorkInfo(
                    id = workInfo.id,
                    state = workInfo.state,
                    runAttemptCount = workInfo.runAttemptCount,
                    nextScheduleTimeMs = workInfo.nextScheduleTimeMillis,
                )
        }
    }

    @VisibleForTesting
    internal data class ChargingLoopDecision(
        val action: ChargingLoopAction,
        val reason: String,
        val active: ChargingWorkInfo?,
    )

    @VisibleForTesting
    internal enum class ChargingLoopAction(val logValue: String) {
        KEEP("keep"),
        ENQUEUE_DELAYED("enqueue_delayed"),
        REPLACE_DELAYED("replace_delayed"),
        REPLACE_IMMEDIATE("replace_immediate"),
    }

    fun cancel(context: Context) {
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP)
            logSchedulerEvent(
                context = context,
                tag = "CURR_FETCH_WORK_CANCELLED",
                message = "name=${WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP}",
            )
            Log.d(TAG, "cancel: canceled ${WidgetWorkScheduler.WORK_NAME_CURRENT_TEMP}")
        }.onFailure { e ->
            Log.e(TAG, "cancel failed: ${e.message}", e)
        }
    }

    private fun logSchedulerEvent(
        context: Context,
        tag: String,
        message: String,
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            WeatherDatabase.getDatabase(context).appLogDao().log(tag, message, "INFO")
        }
    }

    private fun formatTime(timestampMs: Long): String = timestampFormatter.format(Instant.ofEpochMilli(timestampMs))
}
