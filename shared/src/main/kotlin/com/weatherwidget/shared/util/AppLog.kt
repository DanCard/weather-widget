package com.weatherwidget.shared.util

/**
 * Persistent `app_logs` facade for `:shared`, parallel to [Log] (console / logcat).
 *
 * Each platform installs a non-blocking [Sink] once at process startup:
 * - Android (`AppModule.provideAppLogDao`): launches `appLogDao.log(tag, message, level)` on IO.
 * - Desktop (`DaemonProcess` / `DesktopUiApplication`): enqueues `weatherDao.log(...)` on `DesktopAppLogQueue`.
 *
 * Defaults to a no-op so pure JVM unit tests need no database unless they install a capturing sink.
 * Only DEBUG and above are exposed; VERBOSE stays in ephemeral [Log] sinks and never reaches `app_logs`.
 */
object AppLog {
    fun interface Sink {
        fun log(tag: String, message: String, level: String)
    }

    private val NO_OP = Sink { _, _, _ -> }

    @Volatile
    private var sink: Sink = NO_OP

    fun install(sink: Sink) {
        this.sink = sink
    }

    fun resetToDefault() {
        this.sink = NO_OP
    }

    fun log(tag: String, message: String, level: String = "INFO") {
        if (level == "VERBOSE") return
        sink.log(tag, message, level)
    }

    fun d(tag: String, message: String) = sink.log(tag, message, "DEBUG")

    fun i(tag: String, message: String) = sink.log(tag, message, "INFO")

    fun w(tag: String, message: String) = sink.log(tag, message, "WARN")

    fun e(tag: String, message: String) = sink.log(tag, message, "ERROR")
}
