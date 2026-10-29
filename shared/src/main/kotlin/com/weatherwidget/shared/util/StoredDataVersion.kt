package com.weatherwidget.shared.util

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts stored-data writes in this process, so an in-memory read of stored rows can tell it is stale.
 *
 * The shared write runners bump it — [ForecastPersistRun], `CurrentConditionsRun` and `NwsGapFillRun`
 * — so every fetch that lands rows does, on both platforms, whichever screen or worker asked. A cache
 * records [current] before it loads and treats any later bump as a miss ([VersionedTtlCache]).
 *
 * Process-local on purpose: desktop's daemon writes in another process, and its UI already reloads on
 * the daemon's notification. On Android the widget interaction cache drew a History refresh's
 * pre-fetch rows for up to 60 s because the repaint after it is UI-only and cleared nothing
 * (2026-10-29, plans/261029-stored-data-version-invalidates-interaction-cache.md).
 */
class StoredDataVersion {
    private val version = AtomicLong()

    fun current(): Long = version.get()

    /** [reason] names the write, for the VERBOSE trace only: this runs on every stored fetch. */
    fun bump(reason: String): Long =
        version.incrementAndGet().also { Log.v(TAG) { "reason=$reason version=$it" } }

    companion object {
        private const val TAG = "STORED_DATA_VERSION"

        /** The process's one version: what the runners bump and the caches read by default. */
        val process = StoredDataVersion()
    }
}
