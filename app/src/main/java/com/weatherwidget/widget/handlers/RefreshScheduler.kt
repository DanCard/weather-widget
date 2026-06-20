package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.config.ForecastHorizon
import com.weatherwidget.widget.BatteryFetchStrategy
import com.weatherwidget.widget.WeatherWidgetProvider
import com.weatherwidget.widget.WeatherWidgetWorker

object RefreshScheduler {
    private const val TAG = "RefreshScheduler"
    private const val STALE_REFRESH_DEBOUNCE_MS = 30 * 1000L

    // Coverage-staleness debounce: longer than the time-staleness one because a coverage gap persists
    // across many renders until the fetch lands, and (for a source that genuinely caps below the
    // baseline) we never want to busy-loop the network. One forced fetch per source per window.
    private const val COVERAGE_REFRESH_DEBOUNCE_MS = 30 * 60 * 1000L

    @Volatile
    private var isRefreshDisabledForTesting = false

    @VisibleForTesting
    internal data class RefreshScheduleDecision(
        val shouldEnqueue: Boolean,
        val policy: ExistingWorkPolicy,
        val reason: String,
        val skipReason: String? = null,
    )

    @VisibleForTesting
    fun setIsRefreshDisabledForTesting(disableRefreshFlag: Boolean) {
        isRefreshDisabledForTesting = disableRefreshFlag
    }

    @VisibleForTesting
    internal fun buildRefreshScheduleDecision(
        latestFetchedAt: Long?,
        nowMs: Long,
        reason: String,
        lastEnqueueForReasonMs: Long?,
    ): RefreshScheduleDecision {
        if (!BatteryFetchStrategy.shouldRefreshStaleData(latestFetchedAt, nowMs)) {
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
                policy = ExistingWorkPolicy.REPLACE,
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
        policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE,
        forecastDays: Int = ForecastHorizon.BASELINE_DAYS,
    ) {
        if (isRefreshDisabledForTesting) {
            Log.d(TAG, "Skipping forced refresh in test mode (reason=$reason)")
            return
        }

        val workRequest =
            OneTimeWorkRequestBuilder<WeatherWidgetWorker>()
                .setInputData(
                    Data.Builder()
                        .putBoolean(WeatherWidgetWorker.KEY_FORCE_REFRESH, true)
                        .putString(WeatherWidgetWorker.KEY_CURRENT_TEMP_REASON, reason)
                        .putInt(WeatherWidgetWorker.KEY_FORECAST_DAYS, forecastDays)
                        .build(),
                )
                .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            WeatherWidgetProvider.WORK_NAME_ONE_TIME,
            policy,
            workRequest,
        )
    }

    /**
     * Forces a forecast refresh when the daily view finds a day *within the baseline horizon* that has
     * no real forecast (it's showing GENERIC_GAP climate-normal filler). The normal fetch cadence is
     * time-based — "fresh" means "fetched recently" — so a short-but-recent cache (e.g. a 7-day batch
     * inherited right after upgrading to the 8-day baseline) is never re-fetched, leaving that day a
     * generic green bar forever. This is the coverage-based complement: it requests [forecastDays] so
     * the gap is filled. Debounced per source so it can't busy-loop the network. Logs only when it
     * actually enqueues (callers may invoke this on every render).
     */
    suspend fun enqueueForecastCoverageRefresh(
        context: Context,
        sourceId: String,
        forecastDays: Int,
        appLogDao: AppLogDao? = null,
    ) {
        if (isRefreshDisabledForTesting) return
        val nowMs = System.currentTimeMillis()
        val prefs = context.getSharedPreferences("widget_refresh", Context.MODE_PRIVATE)
        val key = "last_enqueue_coverage_$sourceId"
        val lastEnqueueMs = prefs.getLong(key, -1L).takeIf { it >= 0L }
        if (lastEnqueueMs != null && nowMs - lastEnqueueMs < COVERAGE_REFRESH_DEBOUNCE_MS) {
            return
        }
        prefs.edit().putLong(key, nowMs).apply()
        appLogDao?.log(
            "COVERAGE_REFRESH_ENQUEUE",
            "source=$sourceId forecastDays=$forecastDays reason=coverage_gap_within_baseline",
        )
        enqueueForcedRefresh(context, reason = "coverage_gap", forecastDays = forecastDays)
    }

    suspend fun refreshIfStale(
        context: Context,
        latestFetchedAt: Long?,
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
            latestFetchedAt = latestFetchedAt,
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
        val ageMin = (nowMs - (latestFetchedAt ?: 0L)) / 1000 / 60
        prefs.edit().putLong("last_enqueue_${decision.reason}", nowMs).apply()
        enqueueForcedRefresh(context, reason = decision.reason, policy = decision.policy)
        appLogDao?.let {
            it.log(
                "STALE_REFRESH_ENQUEUE",
                "reason=${decision.reason} policy=${decision.policy.name} ageMin=$ageMin",
            )
        }
    }
}