package com.weatherwidget.data.remote

import com.weatherwidget.data.model.ForecastProduct
import java.util.concurrent.ConcurrentHashMap

/**
 * Forecast products a source's provider refused until a known time (a per-product daily quota), in
 * this process.
 *
 * Per product, not per source (user, 2026-10-07): Google's `forecast/hours` and `forecast/days` have
 * separate quotas, and blocking the source as a whole stopped the daily forecast and put "quota used"
 * on the daily view when only the hourly quota was spent. Refresh triggers treat a source as blocked
 * only when **every** forecast product is: with one left, a fetch still has something to get. The API
 * client that saw the refusal records it here ([GoogleWeatherApi]).
 *
 * In-process only. After a restart the first fetch meets the refusal again (one request) and
 * re-records it.
 */
object SourceQuotaBlocks {
    private val blockedUntilMs = ConcurrentHashMap<Pair<String, ForecastProduct>, Long>()

    fun block(sourceId: String, product: ForecastProduct, untilMs: Long) {
        blockedUntilMs.merge(sourceId to product, untilMs, ::maxOf)
    }

    /** The end of [sourceId]'s [product] block, or null when it is not blocked at [nowMs]. */
    fun blockedUntil(sourceId: String, product: ForecastProduct, nowMs: Long): Long? =
        blockedUntilMs[sourceId to product]?.takeIf { it > nowMs }

    /** When every forecast product of [sourceId] is blocked, the earliest of their ends; else null. */
    fun fullyBlockedUntil(sourceId: String, nowMs: Long): Long? {
        val ends = ForecastProduct.entries.map { blockedUntil(sourceId, it, nowMs) ?: return null }
        return ends.min()
    }

    fun isFullyBlocked(sourceId: String, nowMs: Long): Boolean = fullyBlockedUntil(sourceId, nowMs) != null

    /** For tests. */
    fun reset() = blockedUntilMs.clear()
}
