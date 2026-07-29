package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import kotlinx.coroutines.CancellationException

/**
 * Isolates best-effort WorkManager hand-offs from cache-backed widget rendering. Scheduling errors
 * are diagnostic events, not reasons to leave already-mutated widget state visually unapplied.
 */
internal class InteractionRefreshRequester(
    private val staleRequest:
        suspend (Context, Long?, String, AppLogDao?) -> Unit =
        { context, freshnessAtMs, reason, appLogDao ->
            RefreshScheduler.refreshIfStale(context, freshnessAtMs, reason, appLogDao)
        },
    private val forcedRequest:
        (Context, String, ExistingWorkPolicy, Long, String?) -> Unit =
        RefreshScheduler::enqueueForcedRefresh,
) {
    suspend fun requestIfStale(
        context: Context,
        refreshContext: WidgetRefreshContextResolver.Resolved,
        reason: String,
    ) {
        try {
            staleRequest(
                context,
                refreshContext.latestSuccessfulOrContentAtMs,
                reason,
                refreshContext.database.appLogDao(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(
                refreshContext.database.appLogDao(),
                "STALE_REFRESH_ENQUEUE_FAIL",
                "reason=stale_on_$reason ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }
    }

    suspend fun requestForced(
        context: Context,
        appLogDao: AppLogDao,
        reason: String,
        targetSourceId: String?,
    ) {
        try {
            forcedRequest(context, reason, ExistingWorkPolicy.KEEP, 0L, targetSourceId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logFailure(
                appLogDao,
                "TOGGLE_REFRESH_ENQUEUE_FAIL",
                "reason=$reason target=$targetSourceId ${e.javaClass.simpleName}: ${e.message}",
                e,
            )
        }
    }

    private suspend fun logFailure(
        appLogDao: AppLogDao,
        tag: String,
        message: String,
        error: Exception,
    ) {
        Log.e(TAG, message, error)
        runCatching { appLogDao.log(tag, message, "ERROR") }
            .onFailure { Log.w(TAG, "$tag breadcrumb write failed", it) }
    }

    private companion object {
        const val TAG = "InteractionRefresh"
    }
}
