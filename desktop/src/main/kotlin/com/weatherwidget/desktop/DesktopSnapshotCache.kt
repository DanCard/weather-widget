package com.weatherwidget.desktop

import com.weatherwidget.data.model.ForecastSnapshot

/**
 * In-memory per-source [ForecastSnapshot] cache for the desktop UI process.
 *
 * Cycling between sources in the popup used to run a full SQLite `loadCached()` on every click
 * even when the user had just viewed that source seconds earlier
 * (performance/261118-desktop-api-toggle-latency.md). Keeping one entry per source at the current
 * site makes repeat toggles instant; any daemon data update, location change, or manual refresh
 * invalidates the cache so stale rows never linger.
 */
internal class DesktopSnapshotCache(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
) {
    data class Key(
        val sourceId: String,
        val lat: Double,
        val lon: Double,
        val personalStationWeight: Double,
        val actualsProviderOverride: String?,
    )

    private data class Entry(
        val snapshot: ForecastSnapshot,
        val cachedAtMs: Long,
    )

    private val entries = LinkedHashMap<Key, Entry>()
    private var cachedSite: Pair<Double, Double>? = null

    fun get(key: Key, nowMs: Long = System.currentTimeMillis()): ForecastSnapshot? {
        val entry = entries[key] ?: return null
        if (nowMs - entry.cachedAtMs > maxAgeMs) {
            entries.remove(key)
            return null
        }
        return entry.snapshot
    }

    fun put(key: Key, snapshot: ForecastSnapshot, nowMs: Long = System.currentTimeMillis()) {
        val site = key.lat to key.lon
        if (cachedSite != null && cachedSite != site) {
            entries.clear()
        }
        cachedSite = site
        entries[key] = Entry(snapshot, nowMs)
    }

    fun invalidateAll() {
        entries.clear()
        cachedSite = null
    }

    fun size(): Int = entries.size

    companion object {
        const val DEFAULT_MAX_AGE_MS = 5 * 60 * 1000L

        fun keyFor(config: DesktopConfig, sourceId: String): Key = Key(
            sourceId = sourceId,
            lat = config.lat,
            lon = config.lon,
            personalStationWeight = config.personalStationWeight(),
            actualsProviderOverride = config.settings.actualsProviders[sourceId],
        )
    }
}

/**
 * Deduplicates the daemon's two UI-notification channels (`UiNotifyClient` socket push and the
 * `.data-updated` fallback file watcher). Both fire on every `notifyDataUpdated()`, which used to
 * launch two concurrent `loadCached()` runs in the UI for every single daemon update.
 */
internal class DesktopReloadDeduplicator(
    private val watchSuppressWindowMs: Long = WATCH_SUPPRESS_WINDOW_MS,
) {
    @Volatile
    private var lastSocketPushMs: Long = Long.MIN_VALUE

    fun shouldRun(reason: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        if (reason.startsWith("socket:")) {
            lastSocketPushMs = nowMs
            return true
        }
        if (reason == "watch" && lastSocketPushMs != Long.MIN_VALUE && nowMs - lastSocketPushMs < watchSuppressWindowMs) {
            return false
        }
        return true
    }

    companion object {
        const val WATCH_SUPPRESS_WINDOW_MS = 500L
    }
}
