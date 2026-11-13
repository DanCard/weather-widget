package com.weatherwidget.data.local.desktop

import com.weatherwidget.shared.util.SourceRetry

/**
 * Desktop's stored [SourceRetry] in [SourceRetryLog] rows: the daemon arms, the daemon's forecast loop and
 * the popup (another process) read. A forecast success logged after the row ends that retry chain, the
 * way Android's success clears its prefs.
 */
class DesktopSourceRetryState(private val weatherDao: DesktopWeatherDao) {
    fun pending(sourceId: String): SourceRetry.Pending? {
        val (loggedAt, message) = weatherDao.getLatestSourceRetry(sourceId) ?: return null
        val lastSuccess = weatherDao.getLastSuccessfulFetch(sourceId)
        if (lastSuccess != null && lastSuccess >= loggedAt) return null
        val atMs = SourceRetryLog.parseAtMs(message) ?: return null
        val delayMs = SourceRetryLog.parseDelayMs(message) ?: return null
        return SourceRetry.Pending(atMs, delayMs, SourceRetryLog.parseAttempt(message))
    }

    fun retryAtMs(sourceId: String, nowMs: Long): Long? = SourceRetry.retryAtMs(pending(sourceId), nowMs)

    /**
     * The armed retry came due and was run or skipped: marked consumed, keeping its time, delay and attempt
     * so a failure of that run continues the chain (and the cap) instead of starting over.
     */
    fun consume(sourceId: String) {
        val (_, message) = weatherDao.getLatestSourceRetry(sourceId) ?: return
        if (SourceRetryLog.isConsumed(message)) return
        val retry = pending(sourceId) ?: return
        weatherDao.log(SourceRetryLog.TAG, SourceRetryLog.armed(sourceId, retry.atMs, retry.delayMs, retry.attempt, consumed = true), "INFO")
    }

    /** The armed retry of [sourceId] is due at [nowMs] (within [graceMs]) and not yet consumed. */
    fun isDue(sourceId: String, nowMs: Long, graceMs: Long): Boolean {
        val (_, message) = weatherDao.getLatestSourceRetry(sourceId) ?: return false
        if (SourceRetryLog.isConsumed(message)) return false
        return pending(sourceId)?.let { nowMs >= it.atMs - graceMs } == true
    }

    suspend fun arm(sourceId: String, retry: SourceRetry.Pending) =
        weatherDao.log(SourceRetryLog.TAG, SourceRetryLog.armed(sourceId, retry.atMs, retry.delayMs, retry.attempt), "INFO")
}
