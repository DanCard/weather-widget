package com.weatherwidget.widget

import android.appwidget.AppWidgetManager
import android.os.Process
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log
import java.util.concurrent.ConcurrentHashMap

/**
 * Single seam for handler RemoteViews pushes. Diagnostic only: partial-vs-full remains the
 * caller's decision and the push itself is unchanged.
 *
 * Why this exists (see summaries/260714-widget-partial-push-stale.md): partiallyUpdateAppWidget
 * merges into the RemoteViews the framework caches per widget, and is documented to be ignored
 * until that widget has received a full update. The framework drops the cache on reboot and on
 * provider-package update — both of which coincide with a fresh app process. So "a partial push
 * from a process that has not itself pushed full for this widget" is the state where a push can be
 * silently discarded, leaving the launcher rendering the un-bound widget_weather layout, i.e. the
 * XML defaults "Today / --° / --°".
 *
 * On 2026-07-14 a Samsung widget sat on exactly those defaults for ~35 min while every paint
 * logged state=data push=partial. We could not tell whether those pushes came from a cache-backed
 * process because nothing recorded the pid. [pushLogMessage] carries it now.
 */
object WidgetPushDispatcher {

    const val TAG_WIDGET_PUSH = "WIDGET_PUSH"
    private const val TAG = "WidgetPushDispatcher"

    /** Widget ids that have received a full push from *this* process. Cleared when the process dies. */
    private val fullPushedThisProcess: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    /** Widget ids already given a WIDGET_PUSH row this process, keyed by whether it was a full push. */
    private val loggedThisProcess: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * A partial push with no full push behind it in this process — the state where the framework
     * may discard the update entirely. Pure so it is unit-testable without a Context.
     */
    @VisibleForTesting
    internal fun isUnbackedPartial(partialPush: Boolean, hasFullPushedThisProcess: Boolean): Boolean =
        partialPush && !hasFullPushedThisProcess

    /**
     * Whether this push deserves an app_logs row. Only the first push per widget per process and
     * the first *full* push per widget per process qualify (≤2 rows per widget per process).
     * Steady-state pushes stay on Log.v — persisting every one would swamp app_logs the way the
     * CurrentTempResolver logs once did. Pure for testability.
     */
    @VisibleForTesting
    internal fun shouldPersist(isFirstPushForWidget: Boolean, isFirstFullPushForWidget: Boolean): Boolean =
        isFirstPushForWidget || isFirstFullPushForWidget

    @VisibleForTesting
    internal fun pushLogMessage(
        appWidgetId: Int,
        caller: String,
        partialPush: Boolean,
        hasFullPushedThisProcess: Boolean,
        pid: Int,
    ): String = WidgetPerfLogger.kv(
        "widget" to appWidgetId,
        "caller" to caller,
        "push" to if (partialPush) "partial" else "full",
        "pid" to pid,
        "fullThisProcess" to hasFullPushedThisProcess,
        "unbackedPartial" to isUnbackedPartial(partialPush, hasFullPushedThisProcess),
    )

    @VisibleForTesting
    internal fun resetForTest() {
        fullPushedThisProcess.clear()
        loggedThisProcess.clear()
    }

    /**
     * Push [views] for [appWidgetId], partially when [partialPush]. Behaviour is identical to
     * calling AppWidgetManager directly; the only addition is the WIDGET_PUSH breadcrumb.
     */
    suspend fun push(
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        views: RemoteViews,
        partialPush: Boolean,
        caller: String,
        appLogDao: AppLogDao,
    ) {
        val hadFull = fullPushedThisProcess.contains(appWidgetId)
        val message = pushLogMessage(appWidgetId, caller, partialPush, hadFull, Process.myPid())

        if (partialPush) {
            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
        } else {
            appWidgetManager.updateAppWidget(appWidgetId, views)
            fullPushedThisProcess.add(appWidgetId)
        }

        Log.v(TAG, message)
        val firstPush = loggedThisProcess.add("any-$appWidgetId")
        val firstFull = !partialPush && loggedThisProcess.add("full-$appWidgetId")
        if (shouldPersist(firstPush, firstFull)) {
            appLogDao.log(TAG_WIDGET_PUSH, message)
        }
    }
}
