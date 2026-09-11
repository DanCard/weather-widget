package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Single owner for widget WorkManager names, request construction, and collision policy.
 *
 * Running-capable requests never use REPLACE. KEEP is reserved for redundant work; required
 * follow-ups use APPEND_OR_REPLACE so their input/callback contract cannot be discarded.
 *
 * **One carve-out:** the observation backfill uses KEEP. That rule assumes a required follow-up
 * carries a contract worth queueing behind its predecessor; a backfill carries no callback and its
 * input fully describes idempotent work ("pull N hours of observations for this site"). Appending a
 * second one therefore buys nothing and costs a serial repeat of a 5-station, 72-hour fetch — see
 * [enqueueRequiredObservationBackfill].
 *
 * KEEP's own failure mode is that it defers to pending work forever, including pending work that
 * will never run, and no other caller cancels [WORK_NAME_OBSERVATION_BACKFILL]. So that one lane
 * replaces a request that has gone overdue past [BACKFILL_OVERDUE_GRACE_MS] — the narrowest
 * exception that keeps a stalled queue from disabling observation repair permanently.
 */
object WidgetWorkScheduler {
    const val WORK_NAME_PERIODIC = "weather_widget_update"
    const val WORK_NAME_ONE_TIME = "weather_widget_one_time"
    const val WORK_NAME_STARTUP_DELAYED = "weather_widget_startup_delayed"

    /** Runs that [StartupCooldown] deferred, replayed serially once the cooldown lapses. */
    const val WORK_NAME_STARTUP_DEFERRED = "weather_widget_startup_deferred"
    private const val DEFERRED_SIGNATURE_TAG_PREFIX = "startup_deferred:"
    const val WORK_NAME_CURRENT_TEMP = "weather_widget_current_temp"
    const val WORK_NAME_OBSERVATION_BACKFILL = "weather_widget_observation_backfill"
    const val WORK_NAME_UI = "weather_widget_one_time_ui"
    private const val WORK_NAME_UI_DELAYED_PREFIX = "weather_widget_one_time_ui_delayed_"

    fun schedulePeriodicSync(context: Context) {
        val snapshot = BatterySnapshotProvider.snapshot(context)
        val tickMinutes = ForecastFetchPolicy.periodicTickMinutes(snapshot.isCharging, snapshot.batteryLevel)
        val request =
            PeriodicWorkRequestBuilder<WeatherWidgetWorker>(tickMinutes, TimeUnit.MINUTES)
                .setInputData(
                    Data.Builder()
                        .putString(
                            WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON,
                            "periodic_${tickMinutes}m",
                        )
                        .tagTestModeEnqueue()
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
        val nextWindowStartMs =
            System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(tickMinutes)
        Log.d(
            TAG,
            "PERIODIC_REFRESH_SCHEDULE: name=$WORK_NAME_PERIODIC intervalMinutes=$tickMinutes " +
                "charging=${snapshot.isCharging} battery=${snapshot.batteryLevel} policy=update " +
                "nextWindowStartMs=$nextWindowStartMs",
        )
    }

    fun enqueueRedundantImmediateSync(
        context: Context,
        forceRefresh: Boolean = false,
        reason: String = "unspecified",
        targetSourceId: String? = null,
    ): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = ExistingWorkPolicy.KEEP,
            forceRefresh = forceRefresh,
            reason = reason,
            targetSourceId = targetSourceId,
        )

    fun enqueueRequiredImmediateSync(
        context: Context,
        forceRefresh: Boolean = true,
        reason: String,
        targetSourceId: String? = null,
    ): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            forceRefresh = forceRefresh,
            reason = reason,
            targetSourceId = targetSourceId,
        )

    fun enqueueForcedSync(
        context: Context,
        reason: String,
        policy: ExistingWorkPolicy,
        initialDelayMs: Long = 0L,
        targetSourceId: String? = null,
    ): OneTimeWorkRequest {
        require(policy != ExistingWorkPolicy.REPLACE) {
            "Running-capable widget work must never use REPLACE"
        }
        return enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = policy,
            forceRefresh = true,
            reason = reason,
            initialDelayMs = initialDelayMs,
            targetSourceId = targetSourceId,
        )
    }

    fun enqueueDelayedStartupSync(
        context: Context,
        reason: String,
        initialDelayMs: Long,
    ): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_STARTUP_DELAYED,
            policy = ExistingWorkPolicy.KEEP,
            forceRefresh = false,
            reason = reason,
            initialDelayMs = initialDelayMs,
        )

    fun enqueueRequiredNoHourlyFollowUp(
        context: Context,
        appWidgetId: Int,
        date: String,
        lat: Double,
        lon: Double,
        targetSourceId: String,
    ): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            forceRefresh = true,
            reason = "day_click_no_hourly",
            targetSourceId = targetSourceId,
            extraInput = {
                putInt(WeatherWidgetWorker.KEY_NO_HOURLY_WIDGET_ID, appWidgetId)
                putString(WeatherWidgetWorker.KEY_NO_HOURLY_DATE, date)
                putDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LAT, lat)
                putDouble(WeatherWidgetWorker.KEY_NO_HOURLY_LON, lon)
            },
        )

    /**
     * How long past its own scheduled run time a pending backfill may sit before it is treated as
     * wedged rather than merely waiting.
     *
     * A backfill is enqueued with a ~16-second jittered delay, so `nextScheduleTimeMillis` is
     * essentially "now" at enqueue. Five minutes past that, the job is not waiting on its delay; it
     * is waiting on something that is not coming. The grace is generous enough that an ordinary
     * unmet network constraint does not churn (and re-enqueueing an identical idempotent request
     * would be harmless if it did), and the caller's own 30-minute cooldown bounds how often this
     * can fire at all.
     */
    @androidx.annotation.VisibleForTesting
    internal const val BACKFILL_OVERDUE_GRACE_MS = 5 * 60 * 1000L

    /** What [enqueueRequiredObservationBackfill] actually did, so callers can log the truth. */
    internal enum class BackfillEnqueueOutcome(val logValue: String) {
        /** The unique name was free; this request owns it. */
        ENQUEUED("enqueued"),

        /** An equivalent request is already pending and on schedule; this one was dropped by KEEP. */
        KEPT_PENDING("kept_pending"),

        /** The pending request was overdue past [BACKFILL_OVERDUE_GRACE_MS] and was replaced. */
        REPLACED_OVERDUE("replaced_overdue"),
    }

    internal data class ObservationBackfillEnqueue(
        val request: OneTimeWorkRequest,
        val outcome: BackfillEnqueueOutcome,
        val detail: String,
    )

    /** The subset of `WorkInfo` this decision needs, so the rule is unit-testable. */
    @androidx.annotation.VisibleForTesting
    internal data class PendingBackfillWork(
        val id: java.util.UUID,
        val state: androidx.work.WorkInfo.State,
        val nextScheduleTimeMs: Long,
    )

    /**
     * Whether a fresh backfill request can take the unique name, must yield to the pending one, or
     * should replace a pending one that has stopped making progress.
     *
     * The wedge this exists to break: KEEP silently discards a request whenever *any* unfinished
     * work holds the name, and nothing else in the app ever cancels
     * [WORK_NAME_OBSERVATION_BACKFILL]. So a single workspec that JobScheduler accepts but never
     * dispatches disables observation repair permanently — every later request is dropped, the
     * caller's cooldown re-requests every 30 minutes forever, and the NWS actuals line stays a
     * straight interpolation across the hole. Observed on the emulator 2026-09-10: job 26816 sat
     * `Ready: true` with every constraint satisfied for ten minutes while the graph showed an
     * 8-hour gap, and one forced run filled 1,238 rows.
     *
     * RUNNING is never replaced. The work is idempotent, so there is nothing to gain by cancelling
     * a fetch that is already talking to the network, and cancelling a running worker mid-coroutine
     * is its own hazard ([[samsung_widget_dead_native_sigsegv]]).
     */
    @androidx.annotation.VisibleForTesting
    internal fun decideObservationBackfillEnqueue(
        pending: List<PendingBackfillWork>,
        nowMs: Long,
    ): Pair<BackfillEnqueueOutcome, String> {
        val unfinished = pending.filterNot { it.state.isFinished }
        if (unfinished.isEmpty()) {
            return BackfillEnqueueOutcome.ENQUEUED to "no_pending_work"
        }
        unfinished.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }?.let {
            return BackfillEnqueueOutcome.KEPT_PENDING to "running id=${it.id}"
        }
        // Long.MAX_VALUE is WorkManager's "not scheduled to run again" sentinel; it says nothing
        // about lateness, so it can never make a request look overdue.
        val overdueBy =
            unfinished.map { work ->
                if (work.nextScheduleTimeMs == Long.MAX_VALUE) 0L else nowMs - work.nextScheduleTimeMs
            }
        return if (overdueBy.all { it > BACKFILL_OVERDUE_GRACE_MS }) {
            BackfillEnqueueOutcome.REPLACED_OVERDUE to
                "overdue_min=${overdueBy.min() / 60_000L}m pending=${unfinished.size}"
        } else {
            BackfillEnqueueOutcome.KEPT_PENDING to
                "pending=${unfinished.size} overdue_max=${overdueBy.max() / 60_000L}m"
        }
    }

    /**
     * Enqueues a required observation-history repair, yielding to an equivalent one already pending
     * unless that one has gone overdue — see [decideObservationBackfillEnqueue].
     */
    internal suspend fun enqueueRequiredObservationBackfill(
        context: Context,
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
        reason: String,
        initialDelayMs: Long,
    ): ObservationBackfillEnqueue {
        val request =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(
                    Data.Builder()
                        .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, latitude)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, longitude)
                        .putLong(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_HOURS, lookbackHours)
                        .putString(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_REASON, reason)
                        .tagTestModeEnqueue()
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                .build()

        // KEEP, not APPEND_OR_REPLACE. APPEND makes a pending backfill a *prerequisite* of the next
        // one, so a burst becomes a serial queue of identical 5-station x 72-hour fetches — six of
        // them stacked up in five minutes on the emulator, and the actuals the user was waiting for
        // sat behind the lot. KEEP is safe for the caller's 30-minute cooldown precisely because the
        // request is dropped only when an equivalent one is already pending: the work still happens,
        // it is just not done twice. The one thing KEEP cannot survive is a pending request that
        // never runs, which is what decideObservationBackfillEnqueue watches for.
        val workManager = WorkManager.getInstance(context)
        val pending =
            runCatching {
                withContext(Dispatchers.IO) {
                    workManager.getWorkInfosForUniqueWork(WORK_NAME_OBSERVATION_BACKFILL).get()
                }.map {
                    PendingBackfillWork(
                        id = it.id,
                        state = it.state,
                        nextScheduleTimeMs = it.nextScheduleTimeMillis,
                    )
                }
            }.getOrElse {
                // Never let an inspection failure block the repair; fall back to plain KEEP.
                Log.e(TAG, "Observation backfill inspect failed: ${it.message}", it)
                emptyList()
            }
        val (outcome, detail) =
            decideObservationBackfillEnqueue(pending, System.currentTimeMillis())
        val policy =
            when (outcome) {
                // Nothing is RUNNING on this branch, so REPLACE cannot cancel a live fetch.
                BackfillEnqueueOutcome.REPLACED_OVERDUE -> ExistingWorkPolicy.REPLACE
                BackfillEnqueueOutcome.ENQUEUED,
                BackfillEnqueueOutcome.KEPT_PENDING,
                -> ExistingWorkPolicy.KEEP
            }
        workManager.enqueueUniqueWork(WORK_NAME_OBSERVATION_BACKFILL, policy, request)
        Log.d(
            TAG,
            "Observation backfill outcome=${outcome.logValue} ($detail) policy=$policy " +
                "reason=$reason delayMs=$initialDelayMs id=${request.id}",
        )
        return ObservationBackfillEnqueue(request, outcome, detail)
    }

    fun enqueueUiRepaint(
        context: Context,
        reason: String = "unspecified",
    ): OneTimeWorkRequest {
        val request = buildUiRequest(reason)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_UI,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Log.d(TAG, "UI repaint enqueued reason=$reason id=${request.id}")
        return request
    }

    fun enqueueDelayedUiRepaint(
        context: Context,
        appWidgetId: Int,
        reason: String,
        initialDelayMs: Long,
    ): OneTimeWorkRequest {
        require(appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        require(initialDelayMs > 0L)
        val request =
            buildUiRequest(reason, initialDelayMs)
        WorkManager.getInstance(context).enqueueUniqueWork(
            delayedUiWorkName(appWidgetId),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Log.d(
            TAG,
            "Delayed UI repaint enqueued widget=$appWidgetId reason=$reason " +
                "delayMs=$initialDelayMs id=${request.id}",
        )
        return request
    }

    internal fun delayedUiWorkName(appWidgetId: Int): String =
        "$WORK_NAME_UI_DELAYED_PREFIX$appWidgetId"

    /**
     * Zero-delay requests built here and in [buildUiRequest] intentionally remain ordinary work.
     * Interactive refreshes already paint from cache directly, and expedited CoroutineWorker work
     * requires a foreground-notification contract on Android versions before 12.
     */
    private fun enqueueFullSync(
        context: Context,
        uniqueName: String,
        policy: ExistingWorkPolicy,
        forceRefresh: Boolean,
        reason: String,
        initialDelayMs: Long = 0L,
        targetSourceId: String? = null,
        extraInput: (Data.Builder.() -> Unit)? = null,
    ): OneTimeWorkRequest {
        val data =
            Data.Builder()
                .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, forceRefresh)
                .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                .putLong(WeatherWidgetWorker.KEY_REQUESTED_AT_MS, System.currentTimeMillis())
                .apply {
                    targetSourceId?.let {
                        putString(WeatherWidgetWorker.KEY_TARGET_SOURCE, it)
                    }
                    extraInput?.invoke(this)
                }
                .tagTestModeEnqueue()
                .build()
        val request =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(data)
                .apply {
                    if (initialDelayMs > 0L) {
                        setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                    }
                }
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, policy, request)
        Log.d(
            TAG,
            "Full sync enqueued name=$uniqueName policy=$policy reason=$reason " +
                "force=$forceRefresh delayMs=$initialDelayMs id=${request.id}",
        )
        return request
    }

    /** What [enqueueStartupDeferred] did, so the worker can log the truth. */
    internal enum class DeferredEnqueueOutcome(val logValue: String) {
        ENQUEUED("enqueued"),

        /** An identical deferred run is already pending; this one folds into it. */
        COALESCED("coalesced"),
    }

    /**
     * The identity of a deferred run for coalescing: everything that changes what the worker does,
     * nothing that merely describes it (the reason string, the request time).
     */
    @androidx.annotation.VisibleForTesting
    internal fun deferredSignature(data: Data): String =
        listOf(
            data.getBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, false),
            data.getBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, false),
            data.getBoolean(WeatherWidgetWorker.KEY_CURRENT_TEMP_ONLY, false),
            data.getBoolean(WeatherWidgetWorker.KEY_NONPRIMARY_CURRENT_TEMP_ONLY, false),
            data.getBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, false),
            data.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE) ?: "",
            data.getInt(WeatherWidgetWorker.KEY_NO_HOURLY_WIDGET_ID, -1),
            data.getString(WeatherWidgetWorker.KEY_NO_HOURLY_DATE) ?: "",
        ).joinToString(":")

    /**
     * Re-enqueues a run that the startup cooldown turned away, with its input intact, to execute
     * [delayMs] from now. Deferred runs share one lane and APPEND, so they replay one at a time
     * once the cooldown lapses — the opposite of the storm this exists to prevent. An identical
     * run already waiting in the lane absorbs this one (three refresh taps during the cooldown are
     * one refresh, not three); [excludeId] is the deferring run itself, which is still RUNNING and
     * must not count as "already pending".
     */
    internal fun enqueueStartupDeferred(
        context: Context,
        inputData: Data,
        delayMs: Long,
        excludeId: java.util.UUID,
    ): Pair<DeferredEnqueueOutcome, String> {
        val signature = deferredSignature(inputData)
        val signatureTag = DEFERRED_SIGNATURE_TAG_PREFIX + signature
        val workManager = WorkManager.getInstance(context)
        val alreadyPending =
            runCatching { workManager.getWorkInfosForUniqueWork(WORK_NAME_STARTUP_DEFERRED).get() }
                .getOrDefault(emptyList())
                .any { !it.state.isFinished && it.id != excludeId && signatureTag in it.tags }
        if (alreadyPending) {
            return DeferredEnqueueOutcome.COALESCED to "signature=$signature"
        }
        val data =
            Data.Builder()
                .putAll(inputData)
                .putBoolean(WeatherWidgetWorker.KEY_STARTUP_DEFERRED, true)
                .build()
        val request =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(data)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(signatureTag)
                .build()
        workManager.enqueueUniqueWork(
            WORK_NAME_STARTUP_DEFERRED,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return DeferredEnqueueOutcome.ENQUEUED to "signature=$signature id=${request.id}"
    }

    private fun buildUiRequest(
        reason: String,
        initialDelayMs: Long = 0L,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_UI_ONLY_REFRESH, true)
                    .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                    .tagTestModeEnqueue()
                    .build(),
            )
            .apply {
                if (initialDelayMs > 0L) {
                    setInitialDelay(initialDelayMs, TimeUnit.MILLISECONDS)
                }
            }
            .build()

    private const val TAG = "WidgetWorkScheduler"
}
