package com.weatherwidget.shared.observations

import com.weatherwidget.data.local.desktop.CurrentTempStatusLog
import com.weatherwidget.data.model.WeatherSource
import com.weatherwidget.data.remote.ApiAccessException
import com.weatherwidget.shared.util.ActualsFeedPolicy
import com.weatherwidget.shared.util.StoredDataVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * One current-conditions run: which feeds to fetch for the visible sources, the low-priority throttle,
 * the per-site freshness gate, per-feed success/failure, and the `CURR_FETCH_*` rows. Android's
 * `CurrentTempRepository.refreshCurrentTemperature` was this; desktop fetched only the displayed
 * source's feed. Both run it now (Phase 3 of plans/261013-shared-current-conditions-fetch.md; user,
 * 2026-10-13: Android's rule).
 *
 * The platform ([Host]) fetches and stores each feed and keeps the stores; this decides and logs.
 */
class CurrentConditionsRun(
    private val host: Host,
    private val version: StoredDataVersion = StoredDataVersion.process,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    interface Host {
        /** Visible sources, best first: the rank the low-priority throttle reads. */
        fun visibleSources(): List<WeatherSource>

        /** Sources displayed right now (any widget / the popup): never throttled. */
        fun displayedSourceIds(): Set<String>

        /** When this site last had a run, 0 when never. Location-scoped: a move must not inherit the cooldown. */
        fun lastRunMs(latitude: Double, longitude: Double): Long
        fun setLastRunMs(latitude: Double, longitude: Double, ms: Long)

        /** When [feedId] was last attempted, 0 when never; whether it is due again is [lowPriorityDue]. */
        fun lastAttemptMs(feedId: String): Long
        fun markAttempt(feedId: String, ms: Long)

        /** Fetch and store [feed]'s current conditions; null when it returned none. Throws on failure. */
        suspend fun fetch(feed: WeatherSource, latitude: Double, longitude: Double): Reading?

        fun recordSuccess(feed: WeatherSource)
        fun recordFailure(feed: WeatherSource, error: Exception)

        suspend fun log(tag: String, message: String, level: String)

        /** The user's actuals-provider choices ([ActualsFeedPolicy.currentTempFeeds]). */
        fun actualsPreference(source: WeatherSource): WeatherSource? = ActualsProviderResolver.preferenceSource()(source)
    }

    data class Reading(
        val source: WeatherSource,
        val temperature: Float,
        val condition: String?,
        val observedAt: Long?,
    )

    /** Which feeds a run fetches, and which it throttled (feed to its best consumer's rank). */
    data class Plan(val targets: List<WeatherSource>, val throttled: Map<WeatherSource, Int>)

    private val mutex = Mutex()

    /**
     * [source] non-null asks for that source alone (a toggle, a screen's Refresh, the battery loop's
     * primary); null asks for every visible source. A [force] or a named source bypasses the throttle;
     * only [force] bypasses the freshness gate. Returns the number of feeds attempted.
     */
    suspend fun run(
        latitude: Double,
        longitude: Double,
        source: WeatherSource?,
        reason: String,
        force: Boolean,
    ): Result<Int> {
        val startMs = nowMs()
        return try {
            val waitStart = nowMs()
            mutex.withLock {
                val waitMs = nowMs() - waitStart
                if (waitMs > 100) host.log("CURR_FETCH_MUTEX", "reason=$reason waitMs=$waitMs", "WARN")

                val lastRun = host.lastRunMs(latitude, longitude)
                val sinceLast = nowMs() - lastRun
                if (!force && sinceLast < FRESHNESS_MS) {
                    host.log(
                        "CURR_FETCH_FRESH_SKIP",
                        "reason=$reason ageMs=$sinceLast freshnessMs=$FRESHNESS_MS lastFetchMs=$lastRun",
                        "INFO",
                    )
                    return Result.success(0)
                }

                val plan = plan(latitude, longitude, source, bypassThrottle = force || source != null)
                if (plan.throttled.isNotEmpty()) {
                    host.log(
                        "CURR_FETCH_THROTTLE_SKIP",
                        "reason=$reason throttled=${plan.throttled.entries.joinToString { "${it.key.id}@rank${it.value}" }} " +
                            "intervalMs=$LOW_PRIORITY_INTERVAL_MS",
                        "INFO",
                    )
                }
                host.log(
                    "CURR_FETCH_START",
                    "reason=$reason targets=${plan.targets.joinToString { it.id }} thread=${Thread.currentThread().name}",
                    "DEBUG",
                )

                var successCount = 0
                for (feed in plan.targets) {
                    val feedStart = nowMs()
                    // Recorded up front so the throttle window starts even when the fetch fails (a 429
                    // must not invite rapid retries against a tight quota).
                    host.markAttempt(feed.id, feedStart)
                    try {
                        val reading = host.fetch(feed, latitude, longitude)
                        if (reading != null) {
                            successCount++
                            // [Host.fetch] stored it (and its day's extremes): cached reads are stale.
                            version.bump("current_conditions feed=${feed.id}")
                            host.recordSuccess(feed)
                        }
                        logSourceResult(reason, feed, reading, null, nowMs() - feedStart)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        host.recordFailure(feed, e)
                        logFailure(feed, e)
                        logSourceResult(reason, feed, null, e, nowMs() - feedStart)
                    }
                }

                host.setLastRunMs(latitude, longitude, nowMs())
                host.log(
                    "CURR_FETCH_COMPLETE",
                    "reason=$reason successCount=$successCount totalMs=${nowMs() - startMs} " +
                        "targets=${plan.targets.joinToString(",") { it.id }} attempted=${plan.targets.size}",
                    "DEBUG",
                )
                Result.success(plan.targets.size)
            }
        } catch (e: CancellationException) {
            host.log("CURR_FETCH_ERROR", "Current temp fetch orchestrator failed (reason=$reason, totalMs=${nowMs() - startMs}): ${e.message}", "ERROR")
            throw e
        } catch (e: Exception) {
            host.log("CURR_FETCH_ERROR", "Current temp fetch orchestrator failed (reason=$reason, totalMs=${nowMs() - startMs}): ${e.message}", "ERROR")
            Result.failure(e)
        }
    }

    /** The feeds a run fetches: shared, so "which feeds" is decided once for both platforms. */
    fun plan(latitude: Double, longitude: Double, source: WeatherSource?, bypassThrottle: Boolean): Plan {
        val visible = host.visibleSources()
        val rankBySource = visible.withIndex().associate { (index, s) -> s to index }
        val requested = source?.let { if (it in visible) listOf(it) else emptyList() } ?: visible
        // A forecast-only source is never its own feed; it gets its provider's, even when that feed
        // is not itself visible (Google borrowing NWS once fetched nothing here).
        val feedConsumers = ActualsFeedPolicy.currentTempFeeds(requested, latitude, longitude) { host.actualsPreference(it) }
        val displayed = host.displayedSourceIds()
        val throttled = LinkedHashMap<WeatherSource, Int>()
        val targets = feedConsumers.keys.filter { feed ->
            val consumers = feedConsumers.getValue(feed)
            val rank = consumers.minOf { rankBySource[it] ?: 0 }
            val skip = !bypassThrottle &&
                consumers.none { it.id in displayed } &&
                rank >= LOW_PRIORITY_RANK &&
                !lowPriorityDue(host.lastAttemptMs(feed.id), nowMs())
            if (skip) throttled[feed] = rank
            !skip
        }
        return Plan(targets, throttled)
    }

    private suspend fun logFailure(feed: WeatherSource, e: Exception) {
        val line = when (e) {
            is ApiAccessException -> "source=${feed.id} code=${e.statusCode?.let { "HTTP_$it" } ?: "ACCESS_ERROR"} detail=${e.detail}"
            else -> "source=${feed.id} error=${e.message}"
        }
        host.log("CURR_FETCH_ERROR", line, "WARN")
    }

    private suspend fun logSourceResult(reason: String, feed: WeatherSource, reading: Reading?, error: Exception?, durationMs: Long) {
        val now = nowMs()
        val ageMin = reading?.observedAt?.let { (now - it) / 60_000L }
        val errorSummary = error?.let { " error=${it.javaClass.simpleName}:${it.message ?: "no_message"}" }.orEmpty()
        host.log(
            "CURR_FETCH_SOURCE_RESULT",
            "reason=$reason source=${feed.id} success=${reading != null} " +
                "temp=${reading?.temperature ?: "none"} observedAt=${time(reading?.observedAt)} " +
                "fetchedAt=${time(now)} observedAgeMin=${ageMin ?: "none"} condition=${reading?.condition ?: "none"} " +
                "durationMs=$durationMs$errorSummary",
            if (reading != null) "INFO" else "WARN",
        )
        if (reading != null) {
            host.log(CurrentTempStatusLog.TAG, CurrentTempStatusLog.ok(feed.id), "INFO")
        } else {
            host.log(
                CurrentTempStatusLog.TAG,
                "source=${feed.id} ok=false class=${error?.javaClass?.simpleName ?: "Unknown"} detail=${error?.message ?: "unknown"}",
                "WARN",
            )
        }
    }

    companion object {
        /** A run within this of the last one at the same site is skipped unless forced. */
        const val FRESHNESS_MS = 300_000L

        /** 0-based rank from which a feed is low priority (the 4th visible source onward). */
        const val LOW_PRIORITY_RANK = 3

        /** A low-priority feed is fetched at most this often (tight free-plan quotas, e.g. Tomorrow.io). */
        const val LOW_PRIORITY_INTERVAL_MS = 3_600_000L

        /**
         * A low-priority feed last attempted at [lastAttemptMs] (0 = never) is due at [nowMs] once
         * [LOW_PRIORITY_INTERVAL_MS] has passed — or when the clock is now before that attempt (a clock
         * set back must not throttle a feed until it catches up; Android's rule, user 2026-10-24).
         */
        fun lowPriorityDue(lastAttemptMs: Long, nowMs: Long): Boolean {
            if (lastAttemptMs == 0L) return true
            val elapsed = nowMs - lastAttemptMs
            return elapsed < 0L || elapsed >= LOW_PRIORITY_INTERVAL_MS
        }

        private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
        private fun time(ms: Long?): String = ms?.let { timeFormat.format(Instant.ofEpochMilli(it)) } ?: "none"
    }
}
