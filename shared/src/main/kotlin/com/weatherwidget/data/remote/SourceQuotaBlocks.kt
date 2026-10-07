package com.weatherwidget.data.remote

import java.util.concurrent.ConcurrentHashMap

/**
 * Sources whose provider refused us until a known time (a daily quota), in this process.
 *
 * Read by the refresh triggers so a blocked source's staleness does not start a sync: on 2026-10-07
 * Google stayed stale behind a `forecast/hours` 429, and every widget tap forced a fetch of all five
 * sources, four times in 28 minutes. The API client that saw the refusal records it here
 * ([GoogleWeatherApi] on a daily-quota 429); nothing provider-specific lives in the readers.
 *
 * In-process only. After a restart the first fetch meets the refusal again (no network cost beyond
 * that one call) and re-records it.
 */
object SourceQuotaBlocks {
    private val blockedUntilMs = ConcurrentHashMap<String, Long>()

    fun block(sourceId: String, untilMs: Long) {
        blockedUntilMs.merge(sourceId, untilMs, ::maxOf)
    }

    /** The end of [sourceId]'s block, or null when it is not blocked at [nowMs]. */
    fun blockedUntil(sourceId: String, nowMs: Long): Long? =
        blockedUntilMs[sourceId]?.takeIf { it > nowMs }

    fun isBlocked(sourceId: String, nowMs: Long): Boolean = blockedUntil(sourceId, nowMs) != null

    /** For tests. */
    fun reset() = blockedUntilMs.clear()
}
