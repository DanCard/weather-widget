package com.weatherwidget.desktop

import com.weatherwidget.shared.util.SourceRetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * A launch refresh outlives a source change: on 2026-10-10 three NWS fetches were cancelled 1–2 s
 * in, by clicking on to the next source, and the NWS left bar drew dashed two days later. Every
 * other restart (location, resume / network client reset, config cleared) still cancels it: that
 * fetch is for a site or a client that no longer applies.
 * plans/261012-desktop-source-change-keeps-in-flight-fetch.md
 */
internal fun launchRefreshSurvivesRestart(reason: String): Boolean = reason == SOURCE_CYCLE_REASON

/**
 * A launch refresh paints only while its source and site are still the ones displayed. One left
 * running past a source change ([launchRefreshSurvivesRestart]) still stores what it fetched.
 */
internal fun launchRefreshPublishes(fetched: DesktopConfig, current: DesktopConfig?): Boolean =
    current != null &&
        current.displaySource == fetched.displaySource &&
        current.lat == fetched.lat &&
        current.lon == fetched.lon

/**
 * The daemon's launch refreshes, in [scope] beside the fetch loops rather than inside them, so
 * restarting the loops for a source change does not cancel the fetch already in flight.
 */
internal class LaunchRefreshJobs(private val scope: CoroutineScope) {
    @Volatile private var latest: Job? = null
    private val fetching = ConcurrentHashMap<String, Job>()
    private val retryJobs = ConcurrentHashMap.newKeySet<Job>()

    /**
     * Starts [block] for a loop restart with [reason]. The previous launch refresh (and any
     * settle-time retries) are cancelled unless [launchRefreshSurvivesRestart]. Returns the
     * previous launch refresh, for [closeWhenDone].
     */
    fun start(reason: String, block: suspend () -> Unit): Job? {
        val previous = latest
        if (!launchRefreshSurvivesRestart(reason)) {
            previous?.cancel()
            cancelRetryJobs()
        }
        latest = scope.launch { block() }
        return previous
    }

    /**
     * Runs a due `SOURCE_RETRY` during the source-change settle in [scope] beside the fetch loops
     * so a subsequent source change does not cancel it; a non-source restart or [cancel] does.
     */
    fun launchRetry(block: suspend () -> Unit): Job {
        lateinit var job: Job
        job = scope.launch {
            try {
                block()
            } finally {
                retryJobs.remove(job)
            }
        }
        retryJobs.add(job)
        job.invokeOnCompletion { retryJobs.remove(job) }
        return job
    }

    /** The launch refresh started last, for the forecast loop to wait on ([awaitLaunch]). */
    fun current(): Job? = latest

    /** Config cleared: nothing to fetch for. */
    fun cancel() {
        latest?.cancel()
        cancelRetryJobs()
    }

    private fun cancelRetryJobs() {
        val snapshot = retryJobs.toList()
        retryJobs.clear()
        snapshot.forEach { it.cancel() }
    }

    /**
     * Runs [fetch] unless a launch refresh is already fetching [key] (source and site); that one
     * finishes and paints if its source is displayed by then. Null when skipped. Cycling A→B→A
     * otherwise asked A twice.
     */
    suspend fun <T : Any> fetchOnce(key: String, fetch: suspend () -> T): T? {
        val me = currentCoroutineContext().job
        val owner = fetching.compute(key) { _, current -> if (current != null && current.isActive && current !== me) current else me }
        if (owner !== me) return null
        try {
            return fetch()
        } finally {
            fetching.remove(key, me)
        }
    }

    /**
     * Closes a replaced client once [graceMs] has passed and [previous] has finished: a launch
     * refresh left running past a source change still uses it, and a slow fetch outlived the grace.
     */
    fun closeWhenDone(previous: Job?, graceMs: Long, close: () -> Unit) {
        scope.launch {
            delay(graceMs)
            previous?.join()
            runCatching { close() }
        }
    }

    companion object {
        fun key(config: DesktopConfig): String = key(config.displaySource, config.lat, config.lon)

        fun key(sourceId: String, lat: Double, lon: Double): String = "$sourceId@$lat,$lon"

        /**
         * One observations-only fetch at a time per source and site: the observation loop and the
         * panel-click catch-up both fetched at 10:40:18 on 2026-11-06 (two Synoptic calls, a
         * DAILY_HISTORY_RACE). Separate from [key] so an observation fetch never makes a launch
         * forecast refresh skip.
         */
        fun observationKey(config: DesktopConfig): String = "obs:${key(config)}"
    }
}

/**
 * The forecast loop's first wait after a (re)start with [reason]: until [launch] finishes, at most
 * [settleMs], when [forecastLoopWaitsForLaunch]; otherwise the whole [settleMs]. Returns what ended
 * the wait, for `FORECAST_LOOP_START`: `launch_done` or `settle`.
 *
 * During the `settle` branch, any `SOURCE_RETRY` whose deadline (`atMs - graceMs`, [SourceRetry.DUE_GRACE_MS]) falls inside
 * the 5 min settle is served via [onRetryDue] without ending the settle for normal cadence
 * planning. [wake] signals are consumed to re-evaluate [nextRetryAtMs].
 */
internal suspend fun awaitLaunch(
    reason: String,
    launch: Job?,
    settleMs: Long = FORECAST_LOOP_STARTUP_SETTLE_MS,
    wake: ReceiveChannel<String>? = null,
    nextRetryAtMs: (() -> Long?)? = null,
    onRetryDue: (suspend () -> Unit)? = null,
    graceMs: Long = SourceRetry.DUE_GRACE_MS,
    nowMs: () -> Long = System::currentTimeMillis,
): String {
    if (!forecastLoopWaitsForLaunch(reason)) {
        if (wake == null && nextRetryAtMs == null) {
            delay(settleMs)
            return "settle"
        }
        val deadlineMs = nowMs() + settleMs
        while (true) {
            val now = nowMs()
            if (now >= deadlineMs) return "settle"
            val retryAt = nextRetryAtMs?.invoke()
            val dueAt = retryAt?.let { it - graceMs }
            if (dueAt != null && dueAt <= now && onRetryDue != null) {
                onRetryDue()
                if (nextRetryAtMs.invoke() == retryAt && nowMs() == now) {
                    val remaining = (deadlineMs - now).coerceAtLeast(1L)
                    if (wake != null) {
                        withTimeoutOrNull(remaining) { wake.receive() } ?: return "settle"
                    } else {
                        delay(remaining)
                        return "settle"
                    }
                }
                continue
            }
            val targetMs = if (dueAt != null) minOf(deadlineMs, dueAt) else deadlineMs
            val waitMs = (targetMs - now).coerceAtLeast(1L)
            if (wake != null) {
                withTimeoutOrNull(waitMs) { wake.receive() }
            } else {
                delay(waitMs)
            }
        }
    }
    return if (withTimeoutOrNull(settleMs) { launch?.join() ?: Unit } != null) "launch_done" else "settle"
}
