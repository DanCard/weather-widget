package com.weatherwidget.shared.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.MonthDay
import kotlin.coroutines.cancellation.CancellationException

/**
 * The site's climate normals (the future-day fallback), fetched once and cached, the same way on
 * both platforms: cache first, then a fetch of the rolling window from Open-Meteo's archive, reduced by
 * [ClimateNormals]. The archive is free and keyless, so it is fetched whether or not Open-Meteo is
 * among the user's sources (user, 2026-10-23): normals are site data, not a source's forecast. Was
 * Android's `ClimateNormalsRepository` and desktop's `ensureClimateNormals`; desktop swallowed
 * cancellation (audit plans/261023-duplicate-logic-audit-different-names.md §2).
 *
 * **When** to warm and **what follows a new cache** are this object's too ([ensureForSite]): every
 * routine refresh (full syncs and current-conditions runs) plus every site change runs it **in
 * parallel with** the fetch and paint, never before them, and a newly cached site asks its host to
 * repaint. A failed fetch is retried at the next routine refresh — no dedicated timer, backoff, or
 * cooldown, because the refresh cadence is the retry cadence, a cached site costs one database read,
 * and single-flight keeps overlapping settles to one archive request
 * (plans/261122-climate-normals-warm-and-repaint-shared.md,
 * plans/261122-climate-normals-retry-on-routine-refresh.md).
 */
object ClimateNormalsRun {
    interface Host {
        /** Cached monthly (high, low) for [locationKey]; empty maps when none. */
        suspend fun cached(locationKey: String): Pair<Map<Int, Float>, Map<Int, Float>>

        /** Open-Meteo archive daily highs / lows over [start]..[end] (ISO dates). */
        suspend fun fetchDailyTemps(start: String, end: String): List<com.weatherwidget.data.model.DailyForecast>

        /** Stores this site's normals and drops every other location's (retention: current location only). */
        suspend fun store(locationKey: String, monthlyHigh: Map<Int, Float>, monthlyLow: Map<Int, Float>)

        suspend fun log(tag: String, message: String, level: String)

        /**
         * Normals were just cached for [locationKey]: whatever is on screen was drawn without the
         * fallback days, so repaint (desktop: publish + notify the UI process; Android: mark stored
         * data changed so the paint that follows rereads it). Never called on a cache hit.
         */
        suspend fun onNormalsCached(locationKey: String)
    }

    data class Result(val byMonthDay: Map<MonthDay, Pair<Float, Float>>, val fetched: Boolean)

    /** Throws on a failed fetch (callers that are best-effort use [warm]). */
    suspend fun run(host: Host, latitude: Double, longitude: Double): Result {
        val key = ClimateNormals.locationKey(latitude, longitude)
        val (cachedHigh, cachedLow) = host.cached(key)
        if (cachedHigh.isNotEmpty() && cachedLow.isNotEmpty()) {
            return Result(ClimateNormals.expandMonthlyToDaily(cachedHigh, cachedLow), fetched = false)
        }
        val (start, end) = ClimateNormals.rollingWindow()
        val daily = host.fetchDailyTemps(start, end)
        val (monthlyHigh, monthlyLow) = ClimateNormals.monthlyMeans(daily)
        if (monthlyHigh.isEmpty() || monthlyLow.isEmpty()) {
            host.log("CLIMATE_FETCH_EMPTY", "lat=$latitude lon=$longitude rows=${daily.size}", "WARN")
            return Result(emptyMap(), fetched = false)
        }
        host.store(key, monthlyHigh, monthlyLow)
        host.log("CLIMATE_CACHED", "key=$key months=${monthlyHigh.size}", "INFO")
        return Result(ClimateNormals.expandMonthlyToDaily(monthlyHigh, monthlyLow), fetched = true)
    }

    /** One archive fetch at a time per process: two parallel settles must not both fetch and repaint. */
    private val singleFlight = Mutex()

    /**
     * The one entry point both platforms call when a site settles, **in parallel with** the paint:
     * [warm], then, when it newly cached the site's normals, [Host.onNormalsCached] and
     * `CLIMATE_REPAINT`. Cache-first, so a settled site costs one read; a slow or failing archive
     * costs only background time, since nothing waits on it. Single-flight, so the second of two
     * concurrent calls finds the first's cache. A failing repaint hook is logged
     * (`CLIMATE_REPAINT_FAIL`), never thrown. Returns whether normals were newly cached.
     */
    suspend fun ensureForSite(host: Host, latitude: Double, longitude: Double, reason: String): Boolean {
        val fetched = singleFlight.withLock { warm(host, latitude, longitude).fetched }
        if (!fetched) return false
        val key = ClimateNormals.locationKey(latitude, longitude)
        try {
            host.onNormalsCached(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log("CLIMATE_REPAINT_FAIL", "key=$key reason=$reason error=${e::class.simpleName}:${e.message}", "WARN")
            return true
        }
        host.log("CLIMATE_REPAINT", "key=$key reason=$reason", "INFO")
        return true
    }

    /** Best-effort: a failure is logged (`CLIMATE_WARM_FAIL`), never thrown; cancellation still propagates. */
    suspend fun warm(host: Host, latitude: Double, longitude: Double): Result =
        try {
            run(host, latitude, longitude)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            host.log("CLIMATE_WARM_FAIL", "lat=$latitude lon=$longitude error=${e::class.simpleName}:${e.message}", "WARN")
            Result(emptyMap(), fetched = false)
        }
}
