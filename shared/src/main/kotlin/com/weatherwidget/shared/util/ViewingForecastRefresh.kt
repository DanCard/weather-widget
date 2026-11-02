package com.weatherwidget.shared.util

/**
 * Keeps a source the user just reached for within [ViewingRefreshPolicy.FORECAST_STALE_WHILE_VIEWING_MS]
 * at ≥ 70 % or on a charger. Each platform calls [run] only on a known look (Android: a widget tap;
 * desktop: opening the popup): a screen coming on does not count, because neither says what is being
 * looked at. A refresh it starts shows its messages. The rule, its cooldown and its log line live here; the
 * [Host] supplies state and starts a fetch that is forced for that one source, because a normal sync
 * judges it by the longer background cadence and would fetch nothing.
 */
object ViewingForecastRefresh {
    const val LOG_TAG = "VIEWING_FORECAST"

    interface Host {
        val isCharging: Boolean
        val batteryLevel: Int
        /** Sources on screen that may fetch now. */
        fun viewedSourceIds(): List<String>
        /** The source's last forecast fetch at this site; null = never, which counts as stale. */
        fun lastFetchMs(sourceId: String): Long?
        fun lastRequestMs(sourceId: String): Long?
        fun markRequested(sourceId: String, atMs: Long)
        suspend fun fetch(sourceId: String)
        suspend fun log(tag: String, message: String)
    }

    /** Requests a fetch for each viewed source past the limit; returns the sources it asked for. */
    suspend fun run(host: Host, trigger: String, nowMs: Long): List<String> {
        val limitMs = ViewingRefreshPolicy.forecastIntervalMs(host.isCharging, host.batteryLevel, isScreenOn = true)
        if (limitMs != ViewingRefreshPolicy.FORECAST_STALE_WHILE_VIEWING_MS) return emptyList()
        val stale = host.viewedSourceIds().filter { id ->
            val last = host.lastFetchMs(id)
            val requested = host.lastRequestMs(id)
            (last == null || nowMs - last >= limitMs) &&
                (requested == null || nowMs - requested >= ViewingRefreshPolicy.FORECAST_RETRY_COOLDOWN_MS)
        }
        for (id in stale) {
            host.markRequested(id, nowMs) // Before the fetch: a second trigger racing this one must not double it.
            val ageMin = host.lastFetchMs(id)?.let { "${(nowMs - it) / 60_000L}" } ?: "none"
            host.log(LOG_TAG, "source=$id trigger=$trigger ageMin=$ageMin battery=${host.batteryLevel} charging=${host.isCharging}")
            host.fetch(id)
        }
        return stale
    }
}
