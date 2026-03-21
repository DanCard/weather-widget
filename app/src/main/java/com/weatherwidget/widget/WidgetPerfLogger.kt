package com.weatherwidget.widget

import android.os.SystemClock
import android.util.Log
import com.weatherwidget.data.local.AppLogDao
import com.weatherwidget.data.local.log

object WidgetPerfLogger {
    const val TAG_WIDGET_STARTUP_PERF = "WIDGET_STARTUP_PERF"
    const val TAG_WIDGET_RENDER_PERF = "WIDGET_RENDER_PERF"
    const val TAG_DB_OPEN_PERF = "DB_OPEN_PERF"
    const val TAG_TEMP_PIPELINE_PERF = "TEMP_PIPELINE_PERF"
    const val TAG_WIDGET_PAINT = "WIDGET_PAINT"

    const val STARTUP_SLOW_MS = 200L
    const val WIDGET_RENDER_SLOW_MS = 150L
    const val PIPELINE_SLOW_MS = 120L
    const val DB_OPEN_SLOW_MS = 75L

    fun newToken(prefix: String): String = "$prefix-${SystemClock.elapsedRealtime()}"

    fun kv(vararg parts: Pair<String, Any?>): String =
        parts.joinToString(" ") { (key, value) -> "$key=${value ?: "<null>"}" }

    suspend fun logIfSlow(
        appLogDao: AppLogDao,
        thresholdMs: Long,
        totalMs: Long,
        appLogTag: String,
        message: String,
        debugTag: String = appLogTag,
    ) {
        Log.d(debugTag, message)
        if (totalMs >= thresholdMs) {
            appLogDao.log(appLogTag, message, "INFO")
        }
    }
}
