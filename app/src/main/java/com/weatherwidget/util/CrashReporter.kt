package com.weatherwidget.util

/**
 * Pure helpers for the app's last-resort crash capture (see WeatherWidgetApp's uncaught-exception
 * handler). Kept free of Android framework calls so the formatting is unit-testable without
 * Robolectric — plain-JUnit stubs `android.util.Log` to no-ops, so a Log-based formatter could not
 * be asserted on. `Throwable.stackTraceToString()` is pure Kotlin and works in plain JUnit.
 */
object CrashReporter {
    /** Tag used for crash rows written to the app_logs table. */
    const val CRASH_TAG = "CRASH"

    /**
     * Formats a crash into a single log message: the crashing thread name followed by the full
     * stack trace (including causes). Used as the `message` of a CRASH-tagged app_logs row.
     */
    fun formatCrashMessage(
        threadName: String,
        throwable: Throwable,
    ): String = "thread=$threadName\n${throwable.stackTraceToString()}"
}
