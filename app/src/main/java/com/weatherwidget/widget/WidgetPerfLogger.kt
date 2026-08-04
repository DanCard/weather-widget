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
    const val TAG_DAILY_INTERACTION_PERF = "DAILY_INTERACTION_PERF"
    const val TAG_WIDGET_PAINT = "WIDGET_PAINT"
    const val TAG_COLD_START_PERF = "COLD_START_PERF"

    const val STARTUP_SLOW_MS = 200L
    const val WIDGET_RENDER_SLOW_MS = 150L
    const val PIPELINE_SLOW_MS = 120L
    const val DAILY_INTERACTION_SLOW_MS = 500L
    const val DB_OPEN_SLOW_MS = 75L
    // Process-start -> first widget paint. Far above a normal cold start (~1-2s even on a
    // debuggable build) so routine starts write nothing; firmly catches the ~20s outlier we could
    // not capture once logcat rotated. Persisted to app_logs (survives rotation, queryable, 72h).
    const val COLD_START_SLOW_MS = 8000L

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
