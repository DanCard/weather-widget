package com.weatherwidget.widget

/**
 * Decision policy for lazy refresh on charger plug-in.
 */
object PowerConnectedRefreshPolicy {
    const val DEBOUNCE_MS = 2 * 60 * 1000L

    fun shouldEnqueueRefresh(
        nowMs: Long,
        lastRefreshMs: Long,
        debounceMs: Long = DEBOUNCE_MS,
    ): Boolean {
        val elapsedMs = nowMs - lastRefreshMs
        return elapsedMs !in 0 until debounceMs
    }
}
