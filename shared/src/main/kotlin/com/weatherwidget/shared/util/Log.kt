package com.weatherwidget.shared.util

import java.util.logging.Logger

/**
 * Drop-in replacement for `android.util.Log` so code shared with the Android app can live in this
 * pure-JVM module by changing only its import line
 * (`android.util.Log` -> `com.weatherwidget.shared.util.Log`).
 *
 * Output is routed through a pluggable [Sink]. This matters because `:shared` is a plain
 * `kotlin.jvm` module with no Android dependency, so it cannot call `android.util.Log` directly —
 * yet on Android the shared diagnostics (e.g. the label-suppression breadcrumbs in
 * `TemperatureLabelResolver`) MUST reach logcat or they are invisible during debugging. The Android
 * app installs [Sink] forwarding to `android.util.Log`; the [default sink][JulSink] keeps the
 * original `java.util.logging` behavior so desktop and unit tests are unchanged.
 *
 * The (tag, msg) signatures mirror the Android API; the optional Throwable on `e`/`w` matches the
 * overloads the shared code actually uses.
 */
object Log {
    /** Severity, mapped to platform levels by each [Sink]. */
    enum class Priority { DEBUG, INFO, WARN, ERROR }

    /** Platform output target. Install one via [install]; defaults to [JulSink]. */
    fun interface Sink {
        fun log(priority: Priority, tag: String, msg: String, tr: Throwable?)
    }

    /**
     * Default sink: preserves the historical `java.util.logging` mapping (DEBUG -> FINE, etc.) so
     * any consumer that does not [install] a sink behaves exactly as before.
     */
    object JulSink : Sink {
        override fun log(priority: Priority, tag: String, msg: String, tr: Throwable?) {
            val level = when (priority) {
                Priority.DEBUG -> java.util.logging.Level.FINE
                Priority.INFO -> java.util.logging.Level.INFO
                Priority.WARN -> java.util.logging.Level.WARNING
                Priority.ERROR -> java.util.logging.Level.SEVERE
            }
            val logger: Logger = Logger.getLogger(tag)
            if (tr != null) logger.log(level, msg, tr) else logger.log(level, msg)
        }
    }

    @Volatile
    private var sink: Sink = JulSink

    /** Replace the active sink. Called once at process startup (Android: forward to logcat). */
    fun install(sink: Sink) {
        this.sink = sink
    }

    /** Restore the default [JulSink]. Primarily for test isolation. */
    fun resetToDefault() {
        this.sink = JulSink
    }

    fun d(tag: String, msg: String) = sink.log(Priority.DEBUG, tag, msg, null)

    fun i(tag: String, msg: String) = sink.log(Priority.INFO, tag, msg, null)

    fun w(tag: String, msg: String) = sink.log(Priority.WARN, tag, msg, null)

    fun w(tag: String, msg: String, tr: Throwable?) = sink.log(Priority.WARN, tag, msg, tr)

    fun e(tag: String, msg: String) = sink.log(Priority.ERROR, tag, msg, null)

    fun e(tag: String, msg: String, tr: Throwable?) = sink.log(Priority.ERROR, tag, msg, tr)
}
