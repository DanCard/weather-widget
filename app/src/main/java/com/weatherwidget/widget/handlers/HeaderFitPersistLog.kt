package com.weatherwidget.widget.handlers

import android.content.Context
import android.util.Log
import com.weatherwidget.data.local.WeatherDatabase
import com.weatherwidget.data.local.log
import com.weatherwidget.shared.util.HeaderFit
import com.weatherwidget.shared.util.HeaderFitChangeLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Android [HeaderFit.LOG_TAG] line for one header render.
 *
 * Every render goes to logcat at DEBUG (`android.util.Log`, never persisted). When the widget's
 * header buttons change, per [HeaderFitChangeLog], the line is also written to `app_logs` at INFO,
 * so a bug report's log tail shows when and why buttons were hidden. The first render of each
 * widget and view in a process always persists, giving every report a starting point.
 *
 * The latest line per widget and view is also kept in memory for the bug report's widget section,
 * because the persisted change line can scroll out of the report's 300-row log tail.
 */
internal object HeaderFitPersistLog {
    private val changes = HeaderFitChangeLog()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val latest = java.util.concurrent.ConcurrentHashMap<Int, java.util.concurrent.ConcurrentHashMap<String, String>>()

    /** This process's most recent header decision per view for [appWidgetId], e.g. `daily -> view=daily widthDp=…`. */
    fun latestLines(appWidgetId: Int): Map<String, String> = latest[appWidgetId]?.toSortedMap().orEmpty()

    fun record(
        context: Context,
        appWidgetId: Int,
        view: String,
        widthDp: Float,
        headerScale: Float,
        fit: HeaderFit.Plan,
        measured: HeaderFit.Measured,
    ) {
        val fitLine = HeaderFit.logLine(view, widthDp, headerScale, fit, measured)
        latest.getOrPut(appWidgetId) { java.util.concurrent.ConcurrentHashMap() }[view] = fitLine
        val line = "widget=$appWidgetId $fitLine"
        if (!changes.changed("$appWidgetId/$view", fit)) {
            Log.d(HeaderFit.LOG_TAG, line)
            return
        }
        val appContext = context.applicationContext
        scope.launch {
            // log() also mirrors the line to logcat, so the per-render DEBUG line is skipped here.
            try {
                WeatherDatabase.getDatabase(appContext).appLogDao().log(HeaderFit.LOG_TAG, line, "INFO")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // A launch with no handler would crash the process; a lost diagnostic must not.
                Log.e(HeaderFit.LOG_TAG, "Could not persist header fit change: $line", e)
            }
        }
    }
}
