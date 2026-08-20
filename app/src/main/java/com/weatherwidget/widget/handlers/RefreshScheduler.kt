package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.ExistingWorkPolicy
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.widget.BatteryFetchStrategy
import com.weatherwidget.widget.CurrentTempUpdateScheduler
import com.weatherwidget.widget.WidgetWorkScheduler

object RefreshScheduler {
    private const val TAG = "RefreshScheduler"
    private const val STALE_REFRESH_DEBOUNCE_MS = 30 * 1000L

    @Volatile
    private var isRefreshDisabledForTesting = false

    @VisibleForTesting
    internal data class RefreshScheduleDecision(
        val shouldEnqueue: Boolean,
        val policy: ExistingWorkPolicy,
        val reason: String,
        val skipReason: String? = null,
    )

    /**
     * What the last suppressed [enqueueForcedRefresh] would have enqueued. Only recorded while
     * [isRefreshDisabledForTesting] is set, so tests can assert on targeting without a WorkManager
     * harness.
     */
    @VisibleForTesting
    internal data class ForcedRefreshRequest(
        val reason: String,
        val targetSourceId: String?,
    )

    @Volatile
    @VisibleForTesting
    internal var lastForcedRefreshForTesting: ForcedRefreshRequest? = null

    @VisibleForTesting
    fun setIsRefreshDisabledForTesting(disableRefreshFlag: Boolean) {
        isRefreshDisabledForTesting = disableRefreshFlag
        lastForcedRefreshForTesting = null
    }

    @VisibleForTesting
    internal fun buildRefreshScheduleDecision(
        latestSuccessfulOrContentAtMs: Long?,
        nowMs: Long,
        reason: String,
        lastEnqueueForReasonMs: Long?,
    ): RefreshScheduleDecision {
        if (!BatteryFetchStrategy.shouldRefreshStaleData(latestSuccessfulOrContentAtMs, nowMs)) {
            return RefreshScheduleDecision(
                shouldEnqueue = false,
                policy = ExistingWorkPolicy.KEEP,
                reason = reason,
                skipReason = "fresh_data",
            )
        }

        if (reason == "manual_refresh") {
            return RefreshScheduleDecision(
                shouldEnqueue = true,
                // KEEP, not REPLACE: if a sync is already running it produces fresh data; cancelling it
                // to run a duplicate segfaults ART on debuggable builds (see
                // [[samsung_widget_dead_native_sigsegv]]).
                policy = ExistingWorkPolicy.KEEP,
                reason = reason,
            )
        }

        if (lastEnqueueForReasonMs != null && nowMs - lastEnqueueForReasonMs < STALE_REFRESH_DEBOUNCE_MS) {
            return RefreshScheduleDecision(
                shouldEnqueue = false,
                policy = ExistingWorkPolicy.KEEP,
                reason = reason,
                skipReason = "debounced",
            )
        }

        return RefreshScheduleDecision(
            shouldEnqueue = true,
            policy = ExistingWorkPolicy.KEEP,
            reason = reason,
        )
    }

    fun enqueueForcedRefresh(
        context: Context,
        reason: String = "manual_refresh",
        // KEEP by default so a new forced refresh never cancels a running WeatherWidgetWorker (that
        // cancellation segfaults ART on debuggable builds — see [[samsung_widget_dead_native_sigsegv]]).
        policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP,
        initialDelayMs: Long = 0L,
        // When null the repository forces every enabled source; set this to confine the forced
        // fetch to one provider (avoids burning quota on the key-based sources).
        targetSourceId: String? = null,
    ) {
        if (isRefreshDisabledForTesting) {
            Log.d(TAG, "Skipping forced refresh in test mode (reason=$reason, target=$targetSourceId)")
            lastForcedRefreshForTesting = ForcedRefreshRequest(reason, targetSourceId)
            return
        }

        WidgetWorkScheduler.enqueueForcedSync(
            context = context,
            reason = reason,
            policy = policy,
            initialDelayMs = initialDelayMs,
            targetSourceId = targetSourceId,
        )
    }

    suspend fun refreshIfStale(
        context: Context,
        latestSuccessfulOrContentAtMs: Long?,
        reason: String,
        appLogDao: AppLogDao? = null,
    ) {
        if (isRefreshDisabledForTesting) {
            return
        }
        val nowMs = System.currentTimeMillis()
        val staleReason = "stale_on_$reason"
        val prefs = context.getSharedPreferences("widget_refresh", Context.MODE_PRIVATE)
        val lastEnqueueMs = prefs.getLong("last_enqueue_$staleReason", -1L).takeIf { it >= 0L }
        val decision = buildRefreshScheduleDecision(
            latestSuccessfulOrContentAtMs = latestSuccessfulOrContentAtMs,
            nowMs = nowMs,
            reason = staleReason,
            lastEnqueueForReasonMs = lastEnqueueMs,
        )
        if (!decision.shouldEnqueue) {
            appLogDao?.let {
                it.log(
                    "STALE_REFRESH_SKIP",
                    "reason=${decision.reason} skip=${decision.skipReason}",
                )
            }
            return
        }
        val ageMin = (nowMs - (latestSuccessfulOrContentAtMs ?: 0L)) / 1000 / 60
        prefs.edit().putLong("last_enqueue_${decision.reason}", nowMs).apply()
        enqueueForcedRefresh(context, reason = decision.reason, policy = decision.policy)
        // The full sync above fetches weather/hourly, not current observations. The user is looking
        // at the widget, so also refresh the current temperature immediately and bypass the battery
        // gate — a stale location/observation is exactly what this interaction surfaced.
        CurrentTempUpdateScheduler.enqueueImmediateUpdate(
            context = context,
            reason = decision.reason,
            opportunistic = false,
            userInteraction = true,
        )
        appLogDao?.let {
            it.log(
                "STALE_REFRESH_ENQUEUE",
                "reason=${decision.reason} policy=${decision.policy.name} ageMin=$ageMin",
            )
        }
    }
}
