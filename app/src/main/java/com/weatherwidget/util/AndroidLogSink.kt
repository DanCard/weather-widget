package com.weatherwidget.util

import com.weatherwidget.shared.util.Log
import android.util.Log as AndroidLog

/**
 * Forwards shared-module logging ([com.weatherwidget.shared.util.Log]) to Android's logcat.
 *
 * Installed once at [com.weatherwidget.WeatherWidgetApp.onCreate]. Without this, shared diagnostics
 * route through `java.util.logging` and never reach logcat, leaving label-placement breadcrumbs
 * (`LabelSuppressed`, `LabelAccepted`, etc.) invisible during on-device debugging.
 */
object AndroidLogSink : Log.Sink {
    override fun log(priority: Log.Priority, tag: String, msg: String, tr: Throwable?) {
        when (priority) {
            Log.Priority.VERBOSE -> if (tr != null) AndroidLog.v(tag, msg, tr) else AndroidLog.v(tag, msg)
            Log.Priority.DEBUG -> if (tr != null) AndroidLog.d(tag, msg, tr) else AndroidLog.d(tag, msg)
            Log.Priority.INFO -> if (tr != null) AndroidLog.i(tag, msg, tr) else AndroidLog.i(tag, msg)
            Log.Priority.WARN -> if (tr != null) AndroidLog.w(tag, msg, tr) else AndroidLog.w(tag, msg)
            Log.Priority.ERROR -> if (tr != null) AndroidLog.e(tag, msg, tr) else AndroidLog.e(tag, msg)
        }
    }
}
