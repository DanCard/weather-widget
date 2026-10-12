package com.weatherwidget.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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

    /**
     * Starts [block] for a loop restart with [reason]. The previous launch refresh is cancelled
     * unless [launchRefreshSurvivesRestart]. Returns the previous one, for [closeWhenDone].
     */
    fun start(reason: String, block: suspend () -> Unit): Job? {
        val previous = latest
        if (!launchRefreshSurvivesRestart(reason)) previous?.cancel()
        latest = scope.launch { block() }
        return previous
    }

    /** Config cleared: nothing to fetch for. */
    fun cancel() {
        latest?.cancel()
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
        fun key(config: DesktopConfig): String = "${config.displaySource}@${config.lat},${config.lon}"
    }
}
