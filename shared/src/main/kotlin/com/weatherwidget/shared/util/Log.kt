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
    /**
     * Severity, mapped to platform levels by each [Sink]. [VERBOSE] (below DEBUG, mirroring
     * `android.util.Log.VERBOSE`) is the home for high-frequency per-frame/tick/poll traces — render
     * breadcrumbs and the like. By convention VERBOSE stays VISIBLE in the ephemeral sinks (logcat /
     * desktop console) but is NOT persisted to the queryable DB log: the persistence boundary
     * (`CurrentTemperatureResolver.dbLogger` wiring) drops it. DEBUG and above still persist.
     */
    enum class Priority { VERBOSE, DEBUG, INFO, WARN, ERROR }

    /** Platform output target. Install one via [install]; defaults to [JulSink]. */
    fun interface Sink {
        fun log(priority: Priority, tag: String, msg: String, tr: Throwable?)

        /**
         * Whether [priority] on [tag] would actually be emitted. Lets the lambda-form helpers skip
         * building a message that nothing will print. Defaults to true so existing sinks (and
         * desktop/tests) behave exactly as before.
         */
        fun isLoggable(priority: Priority, tag: String): Boolean = true
    }

    /**
     * Default sink: preserves the historical `java.util.logging` mapping (DEBUG -> FINE, etc.) so
     * any consumer that does not [install] a sink behaves exactly as before.
     */
    object JulSink : Sink {
        override fun log(priority: Priority, tag: String, msg: String, tr: Throwable?) {
            val level = when (priority) {
                Priority.VERBOSE -> java.util.logging.Level.FINER
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

    /** Whether [priority] on [tag] would be emitted by the active sink. */
    fun isLoggable(tag: String, priority: Priority): Boolean = sink.isLoggable(priority, tag)

    /** High-frequency per-frame/tick/poll trace. Visible ephemerally; never persisted to the DB log. */
    fun v(tag: String, msg: String) = sink.log(Priority.VERBOSE, tag, msg, null)

    /**
     * Lambda form of [v] for hot paths: [msg] is only built when the active sink would emit it.
     *
     * Use this wherever a VERBOSE line sits inside a per-row/per-group loop. `android.util.Log.v`
     * writes unconditionally and its arguments are always evaluated, so a plain
     * `Log.v(TAG, "...${list.filter{}.map{}}")` still costs the string AND the intermediate
     * collections on every iteration even when nobody is reading the log — that is exactly what made
     * `DailyForecastSelector` cost ~150-200ms per widget render.
     */
    inline fun v(tag: String, msg: () -> String) {
        if (isLoggable(tag, Priority.VERBOSE)) v(tag, msg())
    }

    fun d(tag: String, msg: String) = sink.log(Priority.DEBUG, tag, msg, null)

    fun i(tag: String, msg: String) = sink.log(Priority.INFO, tag, msg, null)

    fun w(tag: String, msg: String) = sink.log(Priority.WARN, tag, msg, null)

    fun w(tag: String, msg: String, tr: Throwable?) = sink.log(Priority.WARN, tag, msg, tr)

    fun e(tag: String, msg: String) = sink.log(Priority.ERROR, tag, msg, null)

    fun e(tag: String, msg: String, tr: Throwable?) = sink.log(Priority.ERROR, tag, msg, tr)
}
