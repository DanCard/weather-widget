package com.weatherwidget.desktop

import com.weatherwidget.data.local.LocationMatch
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop's stores for the shared current-conditions run: the per-site freshness gate and the
 * per-feed low-priority throttle. Android keeps them in prefs (`FetchMetadata`,
 * `WidgetStateManager.markCurrentTempFetched`). In memory here: one daemon process runs the loop, and
 * after a restart the worst case is one extra fetch per feed.
 */
object DesktopCurrentConditionsMemory {
    private val lastRunBySite = ConcurrentHashMap<String, Long>()
    private val lastAttemptByFeed = ConcurrentHashMap<String, Long>()

    private fun site(latitude: Double, longitude: Double) =
        "${LocationMatch.quantize(latitude)},${LocationMatch.quantize(longitude)}"

    fun lastRunMs(latitude: Double, longitude: Double): Long = lastRunBySite[site(latitude, longitude)] ?: 0L

    fun setLastRunMs(latitude: Double, longitude: Double, ms: Long) {
        lastRunBySite[site(latitude, longitude)] = ms
    }

    fun due(feedId: String, intervalMs: Long, nowMs: Long): Boolean =
        nowMs - (lastAttemptByFeed[feedId] ?: 0L) >= intervalMs

    fun markAttempt(feedId: String, nowMs: Long) {
        lastAttemptByFeed[feedId] = nowMs
    }
}
