package com.weatherwidget.desktop

import com.weatherwidget.shared.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Routes shared-module logging ([com.weatherwidget.shared.util.Log]) to the console on desktop.
 *
 * Without this the desktop process stays on the default `java.util.logging` sink, whose handler
 * level drops `FINE` (our DEBUG) entirely — so the same diagnostics that are invisible on Android
 * without [AndroidLogSink] (label placement, redundancy window, forecast-midpoint decisions) are
 * also invisible on desktop. Installed first thing in `main()`.
 *
 * DEBUG/VERBOSE are printed by default to mirror Android's logcat (which shows `Log.d`/`Log.v`); set
 * `-Dweatherwidget.desktop.debugLog=false` (or env `WEATHER_DESKTOP_DEBUG_LOG=0`) to silence the
 * per-render DEBUG/VERBOSE chatter while keeping INFO/WARN/ERROR. (VERBOSE is the per-frame/tick trace
 * tier — visible here, but never persisted to the DB log; see Log.Priority.)
 */
object DesktopLogSink : Log.Sink {
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

    private val debugEnabled: Boolean = run {
        System.getProperty("weatherwidget.desktop.debugLog")?.let { return@run it != "false" }
        System.getenv("WEATHER_DESKTOP_DEBUG_LOG")?.let { return@run it != "0" && !it.equals("false", ignoreCase = true) }
        true
    }

    override fun log(priority: Log.Priority, tag: String, msg: String, tr: Throwable?) {
        if ((priority == Log.Priority.DEBUG || priority == Log.Priority.VERBOSE) && !debugEnabled) return
        val stream = if (priority == Log.Priority.WARN || priority == Log.Priority.ERROR) System.err else System.out
        stream.println("${LocalTime.now().format(timeFmt)} ${priority.name.first()}/$tag: $msg")
        tr?.printStackTrace(stream)
    }
}
