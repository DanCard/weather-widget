package com.weatherwidget.shared.util

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Drop-in replacement for `android.util.Log` so code shared with the Android app can be moved
 * into this pure-JVM module by changing only its import line
 * (`android.util.Log` -> `com.weatherwidget.shared.util.Log`).
 *
 * Backed by java.util.logging. The (tag, msg) signatures mirror the Android API; the optional
 * Throwable on `e`/`w` matches the overloads the shared code actually uses.
 */
object Log {
    private fun logger(tag: String): Logger = Logger.getLogger(tag)

    fun d(tag: String, msg: String) = logger(tag).log(Level.FINE, msg)

    fun i(tag: String, msg: String) = logger(tag).log(Level.INFO, msg)

    fun w(tag: String, msg: String) = logger(tag).log(Level.WARNING, msg)

    fun w(tag: String, msg: String, tr: Throwable?) = logger(tag).log(Level.WARNING, msg, tr)

    fun e(tag: String, msg: String) = logger(tag).log(Level.SEVERE, msg)

    fun e(tag: String, msg: String, tr: Throwable?) = logger(tag).log(Level.SEVERE, msg, tr)
}
