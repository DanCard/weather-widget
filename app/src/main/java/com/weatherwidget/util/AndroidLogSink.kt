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
    /**
     * Defers to logcat's own tag filter. Note `AndroidLog.v/d` write regardless of `isLoggable`, so
     * this only takes effect for the lambda-form helpers ([Log.v] with a message lambda) — which is
     * the point: it turns a hot per-row VERBOSE trace off by default, re-enabled on demand with
     * `adb shell setprop log.tag.<TAG> VERBOSE`.
     */
    override fun isLoggable(priority: Log.Priority, tag: String): Boolean {
        val androidLevel = when (priority) {
            Log.Priority.VERBOSE -> AndroidLog.VERBOSE
            Log.Priority.DEBUG -> AndroidLog.DEBUG
            Log.Priority.INFO -> AndroidLog.INFO
            Log.Priority.WARN -> AndroidLog.WARN
            Log.Priority.ERROR -> AndroidLog.ERROR
        }
        return AndroidLog.isLoggable(tag, androidLevel)
    }

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
