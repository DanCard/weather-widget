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

    /** Widget ids already given a WIDGET_PUSH row this process. Fulls are logged unconditionally. */
    private val loggedThisProcess: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * A partial push with no full push behind it in this process — the state where the framework
     * may discard the update entirely. Pure so it is unit-testable without a Context.
     */
    @VisibleForTesting
    internal fun isUnbackedPartial(partialPush: Boolean, hasFullPushedThisProcess: Boolean): Boolean =
        partialPush && !hasFullPushedThisProcess

    /** True once *this* process has issued a full updateAppWidget for [appWidgetId]. */
    fun hasFullPushedThisProcess(appWidgetId: Int): Boolean =
        fullPushedThisProcess.contains(appWidgetId)

    /**
     * An unbacked partial ([isUnbackedPartial]) that carries a COMPLETE body must be promoted to a
     * full updateAppWidget, or the framework discards it and the launcher keeps rendering the
     * widget_weather XML defaults ("Today / --° / --°"). A header-only partial ([bodyComplete]=false)
     * must NOT be promoted — pushing it full would blank the body it never populated — so its caller
     * is responsible for not emitting it while unbacked (see TemperatureViewHandler's uiOnly gate).
     */
    @VisibleForTesting
    internal fun shouldPromoteToFull(
        partialPush: Boolean,
        bodyComplete: Boolean,
        hasFullPushedThisProcess: Boolean,
    ): Boolean = bodyComplete && isUnbackedPartial(partialPush, hasFullPushedThisProcess)

    /**
     * Whether this push deserves an app_logs row: the first push per widget per process, plus
     * *every* full push. Repeat partials stay on Log.v — persisting those would swamp app_logs the
     * way the CurrentTempResolver logs once did. Pure for testability.
     *
     * Fulls were previously logged once per widget per process too, which is why the 2026-07-22
     * investigation could not tell what pushed a widget back to the bare widget_weather layout: the
     * process had already spent its one full-push row hours earlier. A full push is the destructive
     * one — updateAppWidget replaces the whole view tree — so it is the transition worth a
     * breadcrumb. Measured cost on the 5-widget Samsung: ~400-660 fulls/day against ~28k app_logs
     * rows/day, so this adds ~2% to a table that is already churning at its 50k cap.
     */
    @VisibleForTesting
    internal fun shouldPersist(isFirstPushForWidget: Boolean, isFullPush: Boolean): Boolean =
        isFirstPushForWidget || isFullPush

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
     * Push [views] for [appWidgetId], partially when [partialPush] — except a complete-body
     * ([bodyComplete]) partial with no full push behind it this process is promoted to a full
     * updateAppWidget, since the framework would otherwise discard it (see [shouldPromoteToFull]).
     * Also emits the WIDGET_PUSH breadcrumb.
     */
    suspend fun push(
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        views: RemoteViews,
        partialPush: Boolean,
        caller: String,
        appLogDao: AppLogDao,
        // False only for header-only RemoteViews (current-temp partials that leave the body at its
        // XML defaults). Complete-body pushes stay true so an unbacked partial can be promoted.
        bodyComplete: Boolean = true,
    ) {
        val hadFull = fullPushedThisProcess.contains(appWidgetId)
        // Promote a complete-body unbacked partial to a full update so the framework can't drop it.
        val promoted = shouldPromoteToFull(partialPush, bodyComplete, hadFull)
        val effectivePartial = partialPush && !promoted
        val message = pushLogMessage(appWidgetId, caller, effectivePartial, hadFull, Process.myPid()) +
            if (promoted) " promoted=unbacked_partial" else ""

        if (effectivePartial) {
            appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
        } else {
            appWidgetManager.updateAppWidget(appWidgetId, views)
            fullPushedThisProcess.add(appWidgetId)
        }

        Log.v(TAG, message)
        val firstPush = loggedThisProcess.add("any-$appWidgetId")
        if (shouldPersist(firstPush, isFullPush = !effectivePartial)) {
            appLogDao.log(TAG_WIDGET_PUSH, message)
        }
    }
}
