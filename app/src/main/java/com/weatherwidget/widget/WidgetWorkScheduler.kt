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

    /**
     * Carried by an observation backfill the startup cooldown re-queued into
     * [WORK_NAME_STARTUP_DEFERRED]. The deferral finishes the original unique work as SUCCEEDED, so
     * without this the replay is invisible to every "is a backfill pending?" check — emulator
     * 2026-10-09: a request deferred at 18:14:06 read as dropped in the same second and the fetch ran
     * twice.
     */
    private const val DEFERRED_OBSERVATION_BACKFILL_TAG = "observation_backfill"
    /** The current-temp charging loop: delayed heartbeats only. */
    const val WORK_NAME_CURRENT_TEMP = "weather_widget_current_temp"

    /**
     * Run-now current-temp requests (taps, opportunistic job, screen-on catch-up, Settings refresh).
     * Never shares a chain with [WORK_NAME_CURRENT_TEMP]: APPEND makes a request a dependent of the
     * chain's last item, and behind a 16-minute heartbeat "immediate" waited 3+ h on the Pixel
     * (performance/261014-current-temp-immediate-work-queued-behind-delayed-loop.md).
     */
    const val WORK_NAME_CURRENT_TEMP_NOW = "weather_widget_current_temp_now"
    const val WORK_NAME_OBSERVATION_BACKFILL = "weather_widget_observation_backfill"
    /** [enqueueHistoryGapFill]'s own name: a pending recent-pass backfill must not absorb it under KEEP. */
    const val WORK_NAME_HISTORY_GAP_FILL = "weather_widget_history_gap_fill"
    const val WORK_NAME_UI = "weather_widget_one_time_ui"

    /**
     * The sync [PreUnplugFetchScheduler] arms before a predicted unplug; its own name, so no other sync
     * replaces it. The value predates the rename (alarm-driven pre-wake) so a check already enqueued
     * stays addressable.
     */
    const val WORK_NAME_PRE_UNPLUG = "weather_widget_pre_wake"

    /** [enqueueDueWakeSync]'s own name: the one pending due-time sync. */
    const val WORK_NAME_DUE_WAKE = "weather_widget_due_wake"
    private const val DUE_WAKE_PREFS = "due_wake_prefs"
    private const val KEY_DUE_WAKE_AT_MS = "planned_at_ms"
    private const val WORK_NAME_UI_DELAYED_PREFIX = "weather_widget_one_time_ui_delayed_"
    private const val WORK_NAME_FAILURE_BANNER_STAGE_PREFIX = "weather_widget_failure_banner_stage_"

    fun schedulePeriodicSync(context: Context) {
        val tickMinutes = ForecastFetchPolicy.BACKSTOP_TICK_MINUTES
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
        Log.d(TAG, "PERIODIC_REFRESH_SCHEDULE: name=$WORK_NAME_PERIODIC intervalMinutes=$tickMinutes policy=update (backstop)")
    }

    /**
     * The next due-time sync: an unforced sync [delayMs] from now, when the shared
     * [com.weatherwidget.data.remote.ForecastSchedule] says the next forecast (or pre-unplug lead) comes
     * due — Android's form of desktop's forecast-loop sleep. Enqueued after every full sync, so it
     * always reflects the latest plan (plans/261028-screen-off-cadence-and-due-time-wakes.md).
     */
    fun enqueueDueWakeSync(context: Context, delayMs: Long, onlyIfEarlier: Boolean = false): OneTimeWorkRequest? {
        val prefs = com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, DUE_WAKE_PREFS)
        val nowMs = System.currentTimeMillis()
        val atMs = nowMs + delayMs
        // A screen-on re-plan may only bring the wake forward: the planned one can be another source's.
        val planned = prefs.getLong(KEY_DUE_WAKE_AT_MS, 0L).takeIf { it > nowMs }
        if (onlyIfEarlier && planned != null && planned <= atMs) return null
        prefs.edit().putLong(KEY_DUE_WAKE_AT_MS, atMs).apply()
        return enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_DUE_WAKE,
            policy = delayedPolicy(context, WORK_NAME_DUE_WAKE),
            forceRefresh = false,
            reason = "due_wake",
            initialDelayMs = delayMs,
            constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
        )
    }

    /** Whether a due-time sync is planned for the future ([enqueueDueWakeSync]). */
    fun dueWakePending(context: Context): Boolean =
        com.weatherwidget.util.SharedPreferencesUtil.getPrefs(context, DUE_WAKE_PREFS)
            .getLong(KEY_DUE_WAKE_AT_MS, 0L) > System.currentTimeMillis()

    /**
     * REPLACE for a pending delayed sync, so a moved due time moves it — but APPEND_OR_REPLACE when the
     * unique work is running, because the sync that re-plans is often that very work and REPLACE would
     * cancel a running [WeatherWidgetWorker] (AGENTS.md rule 6).
     */
    private fun delayedPolicy(context: Context, uniqueName: String): ExistingWorkPolicy {
        val running = try {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get(2, TimeUnit.SECONDS)
                .any { it.state == androidx.work.WorkInfo.State.RUNNING }
        } catch (e: Exception) {
            Log.w(TAG, "Work state read failed for $uniqueName; appending", e)
            true
        }
        return if (running) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
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

    /**
     * An unforced sync [delayMs] from now, on a charger with a network — the cadence makes it LONG
     * when it lands in the lead before the predicted unplug ([PreUnplugFetchScheduler]). A moved
     * prediction moves the check ([delayedPolicy]).
     */
    fun enqueuePreUnplugSync(context: Context, delayMs: Long): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_PRE_UNPLUG,
            policy = delayedPolicy(context, WORK_NAME_PRE_UNPLUG),
            forceRefresh = false,
            reason = "pre_unplug",
            initialDelayMs = delayMs,
            constraints = Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build(),
        )

    fun cancelPreUnplugSync(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME_PRE_UNPLUG)
    }

    fun enqueueRequiredImmediateSync(
        context: Context,
        forceRefresh: Boolean = true,
        reason: String,
        targetSourceId: String? = null,
        /** See [WeatherWidgetWorker.KEY_USER_REFRESH]. */
        userRefresh: Boolean = false,
        /** See [WeatherWidgetWorker.KEY_ALL_SOURCES]. */
        allSources: Boolean = false,
    ): OneTimeWorkRequest =
        enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            forceRefresh = forceRefresh,
            reason = reason,
            targetSourceId = targetSourceId,
            extraInput = if (userRefresh || allSources) {
                {
                    if (userRefresh) putBoolean(WeatherWidgetWorker.KEY_USER_REFRESH, true)
                    if (allSources) putBoolean(WeatherWidgetWorker.KEY_ALL_SOURCES, true)
                }
            } else {
                null
            },
        )

    /** What [enqueueForcedSync] enqueued; [followUp] when it was chained behind a sync for another source. */
    class ForcedSyncEnqueue(val request: OneTimeWorkRequest, val followUp: Boolean)

    fun enqueueForcedSync(
        context: Context,
        reason: String,
        policy: ExistingWorkPolicy,
        initialDelayMs: Long = 0L,
        targetSourceId: String? = null,
    ): ForcedSyncEnqueue {
        require(policy != ExistingWorkPolicy.REPLACE) {
            "Running-capable widget work must never use REPLACE"
        }
        // KEEP joins whatever one-time sync is pending, and a sync forced for another source never
        // fetches this one: the request was silently dropped (Samsung 2026-10-15: NWS 375 min old,
        // absorbed by an Open-Meteo-only sync). A targeted request queues behind it instead.
        val followUp = policy == ExistingWorkPolicy.KEEP && targetSourceId != null &&
            unfinishedOneTimeSyncTags(context)?.let { needsForcedFollowUp(it, targetSourceId) } == true
        val request = enqueueFullSync(
            context = context,
            uniqueName = WORK_NAME_ONE_TIME,
            policy = if (followUp) ExistingWorkPolicy.APPEND_OR_REPLACE else policy,
            forceRefresh = true,
            reason = reason,
            initialDelayMs = initialDelayMs,
            targetSourceId = targetSourceId,
        )
        return ForcedSyncEnqueue(request, followUp)
    }

    /** Tag naming which source a forced sync fetches ("all" when untargeted); see [needsForcedFollowUp]. */
    internal fun forcedTargetTag(targetSourceId: String?): String = "forced_target:${targetSourceId ?: "all"}"

    /**
     * Whether a forced request for [targetSourceId] must queue behind the pending one-time syncs
     * (their tags) rather than join them: some are pending and none is forced for this source. An
     * untargeted sync does not count: it skips gated sources (Pixel 2026-10-30: OWM, just enabled, was
     * absorbed by one that left it out). If it did fetch the source, the follow-up coalesces under the
     * repository's lock ([ForcedRefreshSatisfaction.isSatisfiedByCompletedFetch]).
     */
    @androidx.annotation.VisibleForTesting
    internal fun needsForcedFollowUp(unfinishedTags: List<Set<String>>, targetSourceId: String): Boolean =
        unfinishedTags.isNotEmpty() &&
            unfinishedTags.none { forcedTargetTag(targetSourceId) in it }

    private fun unfinishedOneTimeSyncTags(context: Context): List<Set<String>>? =
        runCatching {
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME_ONE_TIME).get(1, TimeUnit.SECONDS)
                .filterNot { it.state.isFinished }
                .map { it.tags }
        }.getOrElse {
            Log.w(TAG, "One-time sync tag inspect failed: ${it.message}")
            null
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
    /**
     * True when an observation backfill is ENQUEUED or RUNNING. An inspection failure reads as
     * pending: the caller then waits out its cooldown, which is the old behaviour, rather than
     * stacking requests on a WorkManager it cannot see.
     */
    internal suspend fun hasUnfinishedObservationBackfill(context: Context): Boolean =
        runCatching {
            withContext(Dispatchers.IO) {
                val workManager = WorkManager.getInstance(context)
                workManager.getWorkInfosForUniqueWork(WORK_NAME_OBSERVATION_BACKFILL).get().any { !it.state.isFinished } ||
                    hasDeferredObservationBackfill(workManager)
            }
        }.getOrElse {
            Log.e(TAG, "Observation backfill inspect failed: ${it.message}", it)
            true
        }

    /**
     * Whether a one-time sync is already running or waiting, which a KEEP enqueue would join instead
     * of queuing its own. Blocking (bounded): callers are on worker or receiver coroutines, never main.
     * Null when there is none, or when WorkManager cannot be asked.
     */
    internal fun pendingOneTimeSync(context: Context): com.weatherwidget.shared.util.RefreshNotice.Reason? =
        runCatching {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(WORK_NAME_ONE_TIME).get(1, TimeUnit.SECONDS)
            when {
                infos.any { it.state == androidx.work.WorkInfo.State.RUNNING } ->
                    com.weatherwidget.shared.util.RefreshNotice.Reason.IN_PROGRESS
                infos.any { !it.state.isFinished } -> com.weatherwidget.shared.util.RefreshNotice.Reason.ALREADY_QUEUED
                else -> null
            }
        }.getOrElse {
            Log.w(TAG, "One-time sync inspect failed: ${it.message}")
            null
        }

    /** A backfill parked in the startup-deferred lane (see [DEFERRED_OBSERVATION_BACKFILL_TAG]). */
    private fun hasDeferredObservationBackfill(workManager: WorkManager): Boolean =
        workManager.getWorkInfosForUniqueWork(WORK_NAME_STARTUP_DEFERRED).get()
            .any { !it.state.isFinished && DEFERRED_OBSERVATION_BACKFILL_TAG in it.tags }

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

    private fun buildObservationBackfillRequest(
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
        reason: String,
        attempt: Int,
        initialDelayMs: Long,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
            .setInputData(
                Data.Builder()
                    .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true)
                    .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, latitude)
                    .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, longitude)
                    .putLong(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_HOURS, lookbackHours)
                    .putString(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_REASON, reason)
                    .putInt(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ATTEMPT, attempt)
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

    /**
     * The daily NWS gap-fill pass on its own, for a Refresh tap on fresh data
     * ([WidgetRefreshPolicy.shouldRunHistoryGapFillAfterRefresh]). Need-gated in the shared runner, so
     * a complete 3 days makes no request. KEEP: a second tap while one is pending joins it, and a
     * running worker is never cancelled (AGENTS.md rule 6).
     */
    internal fun enqueueHistoryGapFill(
        context: Context,
        latitude: Double,
        longitude: Double,
        reason: String,
    ): OneTimeWorkRequest {
        val request =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(
                    Data.Builder()
                        .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, true)
                        .putBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_DAILY, true)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LAT, latitude)
                        .putDouble(WeatherWidgetWorker.KEY_BACKFILL_LON, longitude)
                        .putString(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_REASON, reason)
                        .tagTestModeEnqueue()
                        .build(),
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_HISTORY_GAP_FILL,
            ExistingWorkPolicy.KEEP,
            request,
        )
        return request
    }

    /**
     * Retry of an observation backfill that never reached NWS, enqueued by that backfill's own run.
     *
     * APPEND_OR_REPLACE, deliberately unlike [enqueueRequiredObservationBackfill]'s KEEP: the caller
     * is the RUNNING work under this unique name, so KEEP would drop the retry. Appending makes it
     * start only after the current run finishes, never alongside it, and a repaint request in the
     * meantime is still deduplicated by KEEP. The "no APPEND" rule below is about render bursts
     * stacking identical fetches; here one finishing run appends exactly one successor, at most
     * [com.weatherwidget.shared.observations.ObservationBackfillRetryPolicy.RETRY_DELAYS_MS].size times.
     */
    internal fun enqueueObservationBackfillRetry(
        context: Context,
        latitude: Double,
        longitude: Double,
        lookbackHours: Long,
        reason: String,
        attempt: Int,
        delayMs: Long,
    ): OneTimeWorkRequest {
        val request = buildObservationBackfillRequest(latitude, longitude, lookbackHours, reason, attempt, delayMs)
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_OBSERVATION_BACKFILL,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        return request
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
        val request = buildObservationBackfillRequest(latitude, longitude, lookbackHours, reason, attempt = 0, initialDelayMs)

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
        // A replay waiting in the startup-deferred lane is the same work; enqueueing another would
        // fetch twice once the cooldown lapses.
        val deferred = runCatching {
            withContext(Dispatchers.IO) { hasDeferredObservationBackfill(workManager) }
        }.getOrDefault(false)
        val (outcome, detail) =
            if (deferred) {
                BackfillEnqueueOutcome.KEPT_PENDING to "startup_deferred"
            } else {
                decideObservationBackfillEnqueue(pending, System.currentTimeMillis())
            }
        val policy =
            when (outcome) {
                // Nothing is RUNNING on this branch, so REPLACE cannot cancel a live fetch.
                BackfillEnqueueOutcome.REPLACED_OVERDUE -> ExistingWorkPolicy.REPLACE
                BackfillEnqueueOutcome.ENQUEUED,
                BackfillEnqueueOutcome.KEPT_PENDING,
                -> ExistingWorkPolicy.KEEP
            }
        // KEEP only sees this unique name, where a deferred original has already SUCCEEDED, so it
        // would enqueue a second fetch; the deferred replay is the pending work.
        if (!deferred) workManager.enqueueUniqueWork(WORK_NAME_OBSERVATION_BACKFILL, policy, request)
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

    /**
     * Repaint when a failure banner should change stage ([com.weatherwidget.shared.util.FailureBannerStage]).
     * Its own unique name so it never cancels or queues behind other delayed repaints; APPEND_OR_REPLACE
     * because the paint that schedules the next stage is often this very work, still running.
     */
    fun enqueueFailureBannerStageRepaint(context: Context, appWidgetId: Int, initialDelayMs: Long) {
        require(appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID)
        val request = buildUiRequest("failure_banner_stage", initialDelayMs.coerceAtLeast(1L))
        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_NAME_FAILURE_BANNER_STAGE_PREFIX$appWidgetId",
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
        Log.d(TAG, "Failure banner stage repaint enqueued widget=$appWidgetId delayMs=$initialDelayMs id=${request.id}")
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
        constraints: Constraints? = null,
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
                    if (forceRefresh) addTag(forcedTargetTag(targetSourceId))
                    constraints?.let { setConstraints(it) }
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
            // The daily gap fill and the recent pass are different work; coalescing drops one.
            data.getBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_DAILY, false),
            data.getString(WeatherWidgetWorker.KEY_TARGET_SOURCE) ?: "",
            data.getBoolean(WeatherWidgetWorker.KEY_USER_REFRESH, false),
            data.getBoolean(WeatherWidgetWorker.KEY_ALL_SOURCES, false),
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
                .apply {
                    // The recent pass only: a parked daily gap fill must not read as a pending repair.
                    if (inputData.getBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_ONLY, false) &&
                        !inputData.getBoolean(WeatherWidgetWorker.KEY_OBSERVATION_BACKFILL_DAILY, false)
                    ) {
                        addTag(DEFERRED_OBSERVATION_BACKFILL_TAG)
                    }
                }
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
